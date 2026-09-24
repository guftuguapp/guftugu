package com.guftugu.app.e2ee

import com.guftugu.app.core.crypto.Ecies
import com.guftugu.app.core.crypto.KeystoreKeys
import com.guftugu.app.core.crypto.SoftwareEcdh
import com.guftugu.app.core.util.Base64Url
import com.guftugu.app.core.util.Time
import com.guftugu.app.core.util.Ulid
import com.guftugu.app.data.api.ApiException
import com.guftugu.app.data.api.GuftuguApi
import com.guftugu.app.data.db.ConvKeyEntity
import com.guftugu.app.data.db.ConversationEntity
import com.guftugu.app.data.db.GuftuguDb
import com.guftugu.app.data.prefs.ServerConfigStore
import com.guftugu.app.protocol.Content
import com.guftugu.app.protocol.Envelope
import com.guftugu.app.protocol.KeyRecipientsResponse
import com.guftugu.app.protocol.PostKeysRequest
import com.guftugu.app.protocol.ProtocolJson
import com.guftugu.app.protocol.PublicDevice
import com.guftugu.app.protocol.Signal
import com.guftugu.app.protocol.WrappedKey
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.Signature
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * [ConversationKeyManager] per PROTOCOL §7–§8, §12.
 *
 * Storage: `conv_keys` rows hold each epoch's 32-byte key wrapped by the hardware `guftugu_wrap`
 * key; a 64-entry LRU ([KeyCache]) holds unwrapped keys for the hot paths. One [Mutex] per
 * conversation serialises network reconciliation; a per-conversation timestamp throttles
 * [ensureKeys] to one `key-recipients` round trip per [recipientsCheckIntervalMs] unless forced.
 *
 * Network policy:
 * - `ensureKeys` (throttled) and `onKeysEvent` (forced): `GET key-recipients`, then per [KeyPlan]
 *   either mint + `POST keys`, import via `GET keys`, or wrap the current key for devices lacking it.
 * - `encrypt`/`encryptSignal`: local key first; the network is only consulted when no usable key
 *   is held or the local conversation row says a rotation is pending (a removed member must not
 *   be able to read anything sent after removal).
 * - `decrypt`/`decryptSignal`: local key; if missing, one `GET keys` attempt per
 *   [keysFetchIntervalMs]; still missing → `null` (caller stores the envelope for later).
 * - 403/404 = not a member any more: handled quietly. Everything else surfaces as [E2eeException].
 *
 * Never logs keys, wraps, nonces or plaintext (it does not log at all).
 */
class ConversationKeyManagerImpl(
    private val api: GuftuguApi,
    private val db: GuftuguDb,
    private val keys: KeystoreKeys = KeystoreKeys,
    private val ecdh: SoftwareEcdh,
    private val serverConfig: ServerConfigStore,
    private val json: Json = ProtocolJson,
    private val recipientsCheckIntervalMs: Long = RECIPIENTS_CHECK_INTERVAL_MS,
    private val keysFetchIntervalMs: Long = KEYS_FETCH_INTERVAL_MS,
    /** Monotonic milliseconds for throttling (never wall-clock: the user can change it). */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) : ConversationKeyManager {

    private class Identity(
        val userId: String,
        val deviceId: String,
        val ecdhPrivate: PrivateKey,
        val ecdhPublicSpki: ByteArray,
    )

    private val cache = KeyCache(KeyCache.DEFAULT_MAX)
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val lastRecipientsCheck = ConcurrentHashMap<String, Long>()
    private val lastKeysFetch = ConcurrentHashMap<String, Long>()

    /** Newest current keyId the server has reported per conversation (from `/keys`, `/key-recipients`, `POST /keys`). */
    private val serverCurrent = ConcurrentHashMap<String, String>()

    @Volatile
    private var identity: Identity? = null

    private val _keyArrivals = MutableSharedFlow<String>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val keyArrivals: Flow<String> = _keyArrivals.asSharedFlow()

    // ---------- ConversationKeyManager ----------

    override suspend fun ensureKeys(convId: String) = ensureKeys(convId, force = false)

    override suspend fun onConversationUpdated(convId: String) = ensureKeys(convId, force = true)

    /**
     * [ensureKeys] with the throttle bypassed (`conversation.updated`, app start after unlock).
     * The throttle window starts at each attempt, successful or not, so an offline phone does
     * not retry the network on every send.
     */
    suspend fun ensureKeys(convId: String, force: Boolean) = guarded {
        lockFor(convId).withLock {
            val last = lastRecipientsCheck[convId]
            if (!force && last != null && clock() - last < recipientsCheckIntervalMs) return@withLock
            lastRecipientsCheck[convId] = clock()
            val recipients = fetchRecipients(convId) ?: return@withLock
            reconcile(convId, recipients, importAll = false)
        }
    }

    override suspend fun onKeysEvent(convId: String, keyId: String) = guarded {
        lockFor(convId).withLock {
            lastRecipientsCheck[convId] = clock()
            val recipients = fetchRecipients(convId) ?: return@withLock
            reconcile(convId, recipients, importAll = true)
        }
    }

    override suspend fun encrypt(convId: String, content: Content, clientId: String): Envelope = guarded {
        val me = identity()
        val (keyId, key) = currentKeyForSending(convId)
        E2eeCodec.encryptContent(json, key, keyId, content, E2eeCodec.contentAad(convId, me.userId, clientId))
    }

    override suspend fun decrypt(convId: String, envelope: Envelope, senderId: String, clientId: String?): Content? = guarded {
        // AAD = convId:senderId:clientId; a message the server delivered without a clientId uses ""
        // (see E2eeCodec.contentAad). The server stores clientId for every e2e message, so this is rare.
        val key = keyForDecrypt(convId, envelope.keyId) ?: return@guarded null
        E2eeCodec.decryptContent(json, key, envelope, E2eeCodec.contentAad(convId, senderId, clientId))
    }

    override suspend fun encryptSignal(convId: String, callId: String, signal: Signal): Envelope = guarded {
        val (keyId, key) = currentKeyForSending(convId)
        E2eeCodec.encryptSignal(json, key, keyId, signal, E2eeCodec.signalAad(convId, callId))
    }

    override suspend fun decryptSignal(convId: String, callId: String, envelope: Envelope): Signal? = guarded {
        val key = keyForDecrypt(convId, envelope.keyId) ?: return@guarded null
        E2eeCodec.decryptSignal(json, key, envelope, E2eeCodec.signalAad(convId, callId))
    }

    override suspend fun hasCurrentKey(convId: String): Boolean = guarded {
        val current = localCurrentKeyId(convId, db.conversations().get(convId)) ?: return@guarded false
        cache.contains(convId, current) || db.convKeys().get(convId, current) != null
    }

    override fun clearCache() {
        cache.clear()
        identity = null
        serverCurrent.clear()
        lastRecipientsCheck.clear()
        lastKeysFetch.clear()
    }

    // ---------- reconciliation ----------

    /** Applies PROTOCOL §7 client rules to one `key-recipients` answer. Must hold the conversation lock. */
    private suspend fun reconcile(convId: String, recipients: KeyRecipientsResponse, importAll: Boolean) {
        val me = identity()
        val current = recipients.currentKeyId
        val holdsCurrent = current != null && loadKey(convId, current) != null
        // Import first so history stays readable even when we are about to rotate.
        if (importAll || (current != null && !holdsCurrent)) importKeys(convId, recipients, me)
        val holdsNow = current != null && loadKey(convId, current) != null
        when (val plan = KeyPlan.of(recipients, me.deviceId, holdsNow)) {
            is KeyPlan.Mint -> mint(convId, plan.devices, plan.previousKeyId, me)
            is KeyPlan.Distribute -> {
                val key = loadKey(convId, plan.keyId) ?: return
                postWraps(convId, plan.keyId, key, plan.devices, me)
                db.conversations().setKeyState(convId, plan.keyId, false)
            }
            is KeyPlan.AwaitKey -> db.conversations().setKeyState(convId, plan.keyId, false)
            KeyPlan.UpToDate -> if (current != null) db.conversations().setKeyState(convId, current, false)
        }
    }

    /** `GET /keys`; verifies and stores every wrap addressed to this device that it does not hold yet. */
    private suspend fun importKeys(convId: String, recipients: KeyRecipientsResponse, me: Identity): Boolean {
        lastKeysFetch[convId] = clock()
        val response = try {
            api.keys(convId)
        } catch (e: ApiException) {
            if (e.isGone) return false
            throw e.toE2ee()
        }
        response.currentKeyId?.let { rememberServerCurrent(convId, it) }
        if (response.items.isEmpty()) return false

        val senderKeys = HashMap<String, ByteArray>(recipients.devices.size * 2)
        for (device in recipients.devices) {
            Base64Url.decodeOrNull(device.devicePublicKey)?.let { senderKeys[device.deviceId] = it }
        }
        val held = HashSet<String>().apply { db.convKeys().keysFor(convId).forEach { add(it.keyId) } }
        val result = E2eeCodec.importWraps(
            convId = convId,
            items = response.items,
            myDeviceId = me.deviceId,
            myEcdhPrivate = me.ecdhPrivate,
            myEcdhPublicSpki = me.ecdhPublicSpki,
            senderPublicKey = senderKeys::get,
            alreadyHeld = held::contains,
        )
        if (result.imported.isEmpty()) return false
        for ((keyId, raw) in result.imported) storeKey(convId, keyId, raw)
        _keyArrivals.tryEmit(convId)
        return true
    }

    /** New epoch: random key, `x_` ULID newer than [previousKeyId], wrapped for every listed device, `POST /keys`. */
    private suspend fun mint(convId: String, devices: List<PublicDevice>, previousKeyId: String?, me: Identity) {
        val raw = Ecies.newConversationKey()
        val keyId = newKeyId(previousKeyId)
        val wraps = wrapAll(raw, convId, keyId, devices, me)
        if (wraps.isEmpty()) throw E2eeException(E2eeException.Reason.CRYPTO, "could not wrap the new key for any device")
        val response = try {
            api.postKeys(convId, PostKeysRequest(keyId, wraps))
        } catch (e: ApiException) {
            if (e.isGone) return
            throw e.toE2ee()
        }
        // Only a key the server accepted may be used for sending.
        storeKey(convId, keyId, raw)
        val current = response.currentKeyId ?: keyId
        rememberServerCurrent(convId, current)
        db.conversations().setKeyState(convId, current, false)
    }

    /** Wrap the current key for devices that lack it and `POST /keys` (idempotent per recipient). */
    private suspend fun postWraps(convId: String, keyId: String, raw: ByteArray, devices: List<PublicDevice>, me: Identity) {
        val wraps = wrapAll(raw, convId, keyId, devices, me)
        if (wraps.isEmpty()) return
        val response = try {
            api.postKeys(convId, PostKeysRequest(keyId, wraps))
        } catch (e: ApiException) {
            if (e.isGone) return
            throw e.toE2ee()
        }
        response.currentKeyId?.let { rememberServerCurrent(convId, it) }
    }

    private suspend fun wrapAll(raw: ByteArray, convId: String, keyId: String, devices: List<PublicDevice>, me: Identity): List<WrappedKey> =
        withContext(Dispatchers.Default) {
            val signer = deviceSigner()
            try {
                E2eeCodec.wrapForDevices(raw, convId, keyId, devices, me.deviceId, signer, Time.serverNowMs())
            } catch (e: GeneralSecurityException) {
                throw E2eeException(E2eeException.Reason.CRYPTO, "wrapping the conversation key failed", e)
            }
        }

    /**
     * `x_` + ULID stamped with server time so it sorts after the server's current key even when
     * the phone clock lags; bumped past [previousKeyId] if it still would not.
     */
    private fun newKeyId(previousKeyId: String?): String {
        val now = Time.serverNowMs()
        val id = Ulid.prefixed(KEY_ID_PREFIX, now)
        if (previousKeyId == null || id > previousKeyId) return id
        val bumped = ((Ulid.timestampOf(previousKeyId) ?: now) + 1).coerceAtLeast(now)
        return Ulid.prefixed(KEY_ID_PREFIX, bumped)
    }

    // ---------- key lookup for encrypt / decrypt ----------

    /**
     * The key to encrypt with. Works offline whenever we hold the newest known epoch and no rotation
     * is pending; otherwise forces one reconciliation and throws [E2eeException] `NO_KEY` if we
     * still hold nothing usable (the message stays in the outbox).
     */
    private suspend fun currentKeyForSending(convId: String): Pair<String, ByteArray> {
        val row = db.conversations().get(convId)
        val localId = localCurrentKeyId(convId, row)
        val rotationPending = row?.keyRotationRequired == true
        if (localId != null && !rotationPending) loadKey(convId, localId)?.let { return localId to it }

        ensureKeys(convId, force = true)

        val id = localCurrentKeyId(convId, db.conversations().get(convId))
            ?: throw E2eeException(E2eeException.Reason.NO_KEY, "no conversation key yet")
        val key = loadKey(convId, id)
            ?: throw E2eeException(E2eeException.Reason.NO_KEY, "waiting for the conversation key")
        return id to key
    }

    /**
     * Local key, else one throttled import attempt (`GET key-recipients` for the senders' public
     * keys + `GET keys`), else null. A read path never mints or distributes keys.
     */
    private suspend fun keyForDecrypt(convId: String, keyId: String): ByteArray? {
        loadKey(convId, keyId)?.let { return it }
        lockFor(convId).withLock {
            loadKey(convId, keyId)?.let { return it } // may have arrived while we waited for the lock
            val last = lastKeysFetch[convId]
            if (last != null && clock() - last < keysFetchIntervalMs) return null
            lastKeysFetch[convId] = clock()
            try {
                val recipients = fetchRecipients(convId) ?: return null
                importKeys(convId, recipients, identity())
            } catch (e: E2eeException) {
                // Network hiccup or missing identity: the caller keeps the envelope; the next keys event / sync retries.
                return null
            }
            return loadKey(convId, keyId)
        }
    }

    /**
     * Newest key epoch we know of for [convId]: the max of what the server last told us, what the
     * sync engine stored on the conversation row, and the newest key we hold. Epoch ids are ULIDs,
     * so the lexicographic max is the newest.
     */
    private suspend fun localCurrentKeyId(convId: String, row: ConversationEntity?): String? {
        var best: String? = serverCurrent[convId]
        val fromRow = row?.currentKeyId
        if (fromRow != null && (best == null || fromRow > best)) best = fromRow
        val fromLocal = db.convKeys().latestKeyId(convId)
        if (fromLocal != null && (best == null || fromLocal > best)) best = fromLocal
        return best
    }

    private suspend fun loadKey(convId: String, keyId: String): ByteArray? {
        cache.get(convId, keyId)?.let { return it }
        val row = db.convKeys().get(convId, keyId) ?: return null
        val raw = try {
            withContext(Dispatchers.Default) { keys.unwrap(row.keyWrapped) }
        } catch (e: GeneralSecurityException) {
            return null // guftugu_wrap changed (reinstall) — treat as missing; a fresh import overwrites the row
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (raw.size != Ecies.CONV_KEY_LENGTH) return null
        cache.put(convId, keyId, raw)
        return raw
    }

    private suspend fun storeKey(convId: String, keyId: String, raw: ByteArray) {
        val wrapped = try {
            withContext(Dispatchers.Default) { keys.wrap(raw) }
        } catch (e: GeneralSecurityException) {
            throw E2eeException(E2eeException.Reason.CRYPTO, "Keystore could not wrap the conversation key", e)
        }
        db.convKeys().upsert(ConvKeyEntity(convId = convId, keyId = keyId, keyWrapped = wrapped, receivedAt = Time.nowMs()))
        cache.put(convId, keyId, raw)
    }

    // ---------- server access ----------

    /** `GET /key-recipients`; null when we are not (or no longer) a member (403/404). */
    private suspend fun fetchRecipients(convId: String): KeyRecipientsResponse? {
        val response = try {
            api.keyRecipients(convId)
        } catch (e: ApiException) {
            if (e.isGone) return null
            throw e.toE2ee()
        }
        response.currentKeyId?.let { rememberServerCurrent(convId, it) }
        return response
    }

    private fun rememberServerCurrent(convId: String, keyId: String) {
        serverCurrent.merge(convId, keyId) { a, b -> if (b > a) b else a }
    }

    // ---------- identity ----------

    private suspend fun identity(): Identity {
        identity?.let { return it }
        val cfg = serverConfig.current.value.takeIf { it.userId != null && it.deviceId != null } ?: serverConfig.snapshot()
        val userId = cfg.userId ?: throw E2eeException(E2eeException.Reason.IDENTITY_MISSING, "not enrolled")
        val deviceId = cfg.deviceId ?: throw E2eeException(E2eeException.Reason.IDENTITY_MISSING, "not enrolled")
        val (priv, pub) = try {
            withContext(Dispatchers.Default) {
                val p = ecdh.privateKey()
                val s = ecdh.publicKeySpkiBase64Url()?.let(Base64Url::decodeOrNull)
                p to s
            }
        } catch (e: GeneralSecurityException) {
            throw E2eeException(E2eeException.Reason.CRYPTO, "identity key unusable", e)
        }
        if (priv == null || pub == null) throw E2eeException(E2eeException.Reason.IDENTITY_MISSING, "identity key missing")
        return Identity(userId, deviceId, priv, pub).also { identity = it }
    }

    private fun deviceSigner(): Signature = try {
        keys.signatureFor(KeystoreKeys.DEVICE)
    } catch (e: IllegalStateException) {
        throw E2eeException(E2eeException.Reason.IDENTITY_MISSING, "device key missing", e)
    } catch (e: GeneralSecurityException) {
        throw E2eeException(E2eeException.Reason.CRYPTO, "device key unusable", e)
    }

    // ---------- plumbing ----------

    private fun lockFor(convId: String): Mutex = locks.getOrPut(convId) { Mutex() }

    private val ApiException.isGone: Boolean
        get() = status == 403 || status == 404

    private fun ApiException.toE2ee(): E2eeException =
        if (isNetwork) E2eeException(E2eeException.Reason.NETWORK, "network error while syncing keys", this)
        else E2eeException(E2eeException.Reason.SERVER, "server error $status $code while syncing keys", this)

    /** Runs [block] on IO and lets only [E2eeException] / cancellation escape. */
    private suspend inline fun <T> guarded(crossinline block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: E2eeException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw e.toE2ee()
        } catch (e: GeneralSecurityException) {
            throw E2eeException(E2eeException.Reason.CRYPTO, "cryptographic operation failed", e)
        } catch (e: Exception) {
            throw E2eeException(E2eeException.Reason.INTERNAL, "key manager failure: ${e.javaClass.simpleName}", e)
        }
    }

    companion object {
        const val RECIPIENTS_CHECK_INTERVAL_MS = 60_000L
        const val KEYS_FETCH_INTERVAL_MS = 10_000L
        const val KEY_ID_PREFIX = "x_"
    }
}
