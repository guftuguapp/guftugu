package com.guftugu.app.data.prefs

import com.guftugu.app.core.crypto.KeystoreKeys
import com.guftugu.app.core.util.Base64Url
import java.io.File
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Small encrypted key/value store for software secrets: the session token, the identity ECDH
 * private key, and anything else that must not sit in plain files.
 *
 * Layout: one JSON file mapping `name → base64url( KeystoreKeys.wrap(valueBytes) )`. Every value
 * is individually AES-GCM encrypted by the hardware-backed `guftugu_wrap` key, so the file is
 * useless off-device. Writes are atomic (temp file + rename). Thread-safe.
 *
 * The wrap/unwrap functions are injectable so the store is testable on a plain JVM.
 */
class SecureStore(
    private val file: File,
    private val wrap: (ByteArray) -> ByteArray = KeystoreKeys::wrap,
    private val unwrap: (ByteArray) -> ByteArray = KeystoreKeys::unwrap,
) {
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private var cache: MutableMap<String, String>? = null

    fun getBytes(key: String): ByteArray? = synchronized(lock) {
        val b64 = load()[key] ?: return null
        val blob = Base64Url.decodeOrNull(b64) ?: return null
        runCatching { unwrap(blob) }.getOrNull()
    }

    fun getString(key: String): String? = getBytes(key)?.toString(Charsets.UTF_8)

    fun putBytes(key: String, value: ByteArray) = synchronized(lock) {
        val map = load()
        map[key] = Base64Url.encode(wrap(value))
        persist(map)
    }

    fun putString(key: String, value: String) = putBytes(key, value.toByteArray(Charsets.UTF_8))

    fun contains(key: String): Boolean = synchronized(lock) { load().containsKey(key) }

    fun remove(key: String) = synchronized(lock) {
        val map = load()
        if (map.remove(key) != null) persist(map)
    }

    fun clear() = synchronized(lock) {
        cache = mutableMapOf()
        if (file.exists()) file.delete()
    }

    private fun load(): MutableMap<String, String> {
        cache?.let { return it }
        val map: MutableMap<String, String> = if (file.exists()) {
            runCatching { json.decodeFromString(serializer, file.readText()).toMutableMap() }.getOrDefault(mutableMapOf())
        } else {
            mutableMapOf()
        }
        cache = map
        return map
    }

    private fun persist(map: Map<String, String>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, map))
        if (!tmp.renameTo(file)) {
            // rename can fail across some filesystems; fall back to copy + delete
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        const val FILE_NAME = "secure_store.json"
        const val KEY_SESSION_TOKEN = "session.token"
        const val KEY_SESSION_EXPIRES_AT = "session.expiresAt"
        const val KEY_ECDH_PRIVATE_PKCS8 = "ecdh.private.pkcs8"
        const val KEY_ECDH_PUBLIC_SPKI = "ecdh.public.spki"
    }
}
