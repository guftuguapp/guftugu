package com.guftugu.app.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.guftugu.app.calls.CallState
import com.guftugu.app.data.repo.AuthState
import com.guftugu.app.di.AppGraph
import com.guftugu.app.notifications.Notifier
import com.guftugu.app.ui.calls.CallScreen
import com.guftugu.app.ui.calls.IncomingCallScreen
import com.guftugu.app.ui.chat.ChatScreen
import com.guftugu.app.ui.chats.ChatListScreen
import com.guftugu.app.ui.contacts.NewChatScreen
import com.guftugu.app.ui.contacts.NewGroupScreen
import com.guftugu.app.ui.join.EnrollScreen
import com.guftugu.app.ui.join.JoinServerScreen
import com.guftugu.app.ui.media.MediaViewerScreen
import com.guftugu.app.ui.settings.DevicesScreen
import com.guftugu.app.ui.settings.LinkDeviceScreen
import com.guftugu.app.ui.settings.SettingsScreen
import com.guftugu.app.ui.unlock.UnlockScreen

/** All routes (docs/ANDROID_MODULES.md → ui/navigation). Builders produce concrete paths. */
object Routes {
    const val JOIN = "join"
    const val ENROLL = "enroll/{apiUrl}/{code}"
    const val UNLOCK = "unlock"
    const val CHATS = "chats"
    const val CHAT = "chat/{convId}"
    const val NEW_CHAT = "newChat"
    const val NEW_GROUP = "newGroup"
    const val MEDIA = "media/{convId}/{msgId}"
    const val CALL = "call/{callId}"
    const val INCOMING_CALL = "incomingCall/{callId}"
    const val SETTINGS = "settings"
    const val DEVICES = "devices"
    const val LINK_DEVICE = "linkDevice"

    fun enroll(apiUrl: String, code: String) = "enroll/${Uri.encode(apiUrl)}/${Uri.encode(code)}"
    fun chat(convId: String) = "chat/$convId"
    fun media(convId: String, msgId: String) = "media/$convId/$msgId"
    fun call(callId: String) = "call/$callId"
    fun incomingCall(callId: String) = "incomingCall/$callId"

    const val ARG_API_URL = "apiUrl"
    const val ARG_CODE = "code"
    const val ARG_CONV_ID = "convId"
    const val ARG_MSG_ID = "msgId"
    const val ARG_CALL_ID = "callId"
}

private const val FADE_MS = 200

/**
 * Single NavHost. Three roots — `join` (not enrolled), `unlock` (locked / revoked) and `chats`
 * (unlocked) — are always the bottom of the back stack; every auth transition replaces the whole
 * stack (`popUpTo(0)`). Call screens are pushed on top of anything when the [CallState] says so and
 * pop themselves when the call ends. Intents (deep link, notification taps) arrive via [IntentBus].
 */
@Composable
fun GuftuguNavGraph(
    graph: AppGraph,
    navController: NavHostController = rememberNavController(),
) {
    val authState by graph.authRepository.state.collectAsStateWithLifecycle()
    val start = remember { startDestination(authState) }

    // Reading the manager here also builds it (lazy), so call invites are observed as soon as the UI is up.
    val callState by graph.callManager.state.collectAsStateWithLifecycle()
    val inCall = callState !is CallState.Idle

    // ---- auth routing: NotEnrolled → join, Locked/Revoked → unlock, Unlocked → chats ----
    LaunchedEffect(authState, inCall) {
        val target = startDestination(authState)
        val current = navController.currentRoute() ?: return@LaunchedEffect
        when (authState) {
            is AuthState.Unlocked -> {
                // Only the auth roots move forward to chats; an unlock while already inside the app changes nothing.
                if (isAuthEntry(current) && current != target) navController.replaceStack(target)
            }
            else -> {
                // Locked out from anywhere → back to the root, except while a call is up (the call
                // screens would be torn down mid-call); the effect re-runs when the call ends.
                if (inCall && isCallRoute(current)) return@LaunchedEffect
                if (current != target) navController.replaceStack(target)
            }
        }
    }

    // ---- deep link guftugu://join → enroll (only meaningful when not enrolled) ----
    val join by IntentBus.join.collectAsStateWithLifecycle()
    LaunchedEffect(join, authState) {
        val j = join ?: return@LaunchedEffect
        when (authState) {
            is AuthState.NotEnrolled -> {
                IntentBus.consumeJoin()
                if (navController.currentRoute() != Routes.ENROLL) navController.navigate(Routes.enroll(j.apiUrl, j.code))
            }
            // Already enrolled (or revoked: the Unlock/Join screens explain "Start over"): the link is not usable.
            else -> IntentBus.consumeJoin()
        }
    }

    // ---- notification taps: open a conversation (after unlock) ----
    val openConv by IntentBus.openConversation.collectAsStateWithLifecycle()
    LaunchedEffect(openConv, authState) {
        val id = openConv ?: return@LaunchedEffect
        if (authState !is AuthState.Unlocked) return@LaunchedEffect
        IntentBus.consumeOpenConversation()
        val alreadyThere = navController.currentRoute() == Routes.CHAT &&
            navController.currentBackStackEntry?.arguments?.getString(Routes.ARG_CONV_ID) == id
        if (!alreadyThere) navController.navigate(Routes.chat(id)) { launchSingleTop = true }
    }

    // ---- calls: follow the manager's state from anywhere ----
    LaunchedEffect(callState) {
        val current = navController.currentRoute()
        when (val s = callState) {
            is CallState.Incoming ->
                if (current != Routes.INCOMING_CALL) navController.navigate(Routes.incomingCall(s.call.callId))
            is CallState.Outgoing ->
                if (current != Routes.CALL) navController.navigate(Routes.call(s.call.callId))
            is CallState.Active ->
                if (current != Routes.CALL) {
                    navController.navigate(Routes.call(s.call.callId)) {
                        popUpTo(Routes.INCOMING_CALL) { inclusive = true } // no-op when not on the stack
                    }
                }
            is CallState.Ended -> Unit // the screens show the outcome for a moment, then pop themselves
            CallState.Idle -> if (current != null && isCallRoute(current)) navController.popBackStack()
        }
    }

    // Full-screen incoming-call notification (or the manager while ringing) → incomingCall/{id}.
    val incoming by IntentBus.incomingCall.collectAsStateWithLifecycle()
    LaunchedEffect(incoming, callState) {
        val id = incoming ?: return@LaunchedEffect
        IntentBus.consumeIncomingCall()
        if (callState is CallState.Incoming && navController.currentRoute() != Routes.INCOMING_CALL) {
            navController.navigate(Routes.incomingCall(id))
        }
    }

    // Tap on the ongoing-call notification / outgoing call started → call/{id}.
    val openCall by IntentBus.openCall.collectAsStateWithLifecycle()
    LaunchedEffect(openCall, callState) {
        val id = openCall ?: return@LaunchedEffect
        IntentBus.consumeOpenCall()
        if (inCall && callState !is CallState.Ended && navController.currentRoute() != Routes.CALL) {
            navController.navigate(Routes.call(id))
        }
    }

    // Notification action buttons. Decline / hang up need no UI; Answer goes through the
    // IncomingCallScreen (permission prompt), which consumes the action itself.
    val callAction by IntentBus.callAction.collectAsStateWithLifecycle()
    LaunchedEffect(callAction, callState) {
        val (action, id) = callAction ?: return@LaunchedEffect
        when (action) {
            Notifier.ACTION_DECLINE -> { IntentBus.consumeCallAction(); graph.callManager.reject() }
            Notifier.ACTION_HANGUP -> { IntentBus.consumeCallAction(); graph.callManager.hangup() }
            Notifier.ACTION_ANSWER ->
                if (callState is CallState.Incoming && navController.currentRoute() != Routes.INCOMING_CALL) {
                    navController.navigate(Routes.incomingCall(id))
                } else if (callState !is CallState.Incoming) {
                    IntentBus.consumeCallAction() // stale: the call is gone
                }
        }
    }

    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = start,
            enterTransition = { fadeIn(tween(FADE_MS)) },
            exitTransition = { fadeOut(tween(FADE_MS)) },
            popEnterTransition = { fadeIn(tween(FADE_MS)) },
            popExitTransition = { fadeOut(tween(FADE_MS)) },
        ) {
            composable(Routes.JOIN) {
                JoinServerScreen(
                    onJoin = { apiUrl, code -> navController.navigate(Routes.enroll(apiUrl, code)) },
                )
            }
            composable(
                Routes.ENROLL,
                arguments = listOf(
                    navArgument(Routes.ARG_API_URL) { type = NavType.StringType },
                    navArgument(Routes.ARG_CODE) { type = NavType.StringType },
                ),
            ) { entry ->
                EnrollScreen(
                    apiUrl = Uri.decode(entry.arguments?.getString(Routes.ARG_API_URL).orEmpty()),
                    code = Uri.decode(entry.arguments?.getString(Routes.ARG_CODE).orEmpty()),
                    onBack = { navController.popBackStack() },
                    onEnrolled = { navController.replaceStackIfNotThere(Routes.CHATS) },
                )
            }
            composable(Routes.UNLOCK) {
                UnlockScreen(onUnlocked = { navController.replaceStackIfNotThere(Routes.CHATS) })
            }
            composable(Routes.CHATS) {
                ChatListScreen(
                    onOpenChat = { navController.navigate(Routes.chat(it)) },
                    onNewChat = { navController.navigate(Routes.NEW_CHAT) },
                    onNewGroup = { navController.navigate(Routes.NEW_GROUP) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.CHAT, arguments = listOf(navArgument(Routes.ARG_CONV_ID) { type = NavType.StringType })) { entry ->
                val convId = entry.arguments?.getString(Routes.ARG_CONV_ID).orEmpty()
                ChatScreen(
                    convId = convId,
                    onBack = { navController.popBackStack() },
                    onOpenMedia = { msgId -> navController.navigate(Routes.media(convId, msgId)) },
                    // The call-state observer above navigates too; the guard avoids a duplicate entry.
                    onCall = { callId -> if (navController.currentRoute() != Routes.CALL) navController.navigate(Routes.call(callId)) },
                )
            }
            composable(Routes.NEW_CHAT) {
                NewChatScreen(
                    onBack = { navController.popBackStack() },
                    onOpenChat = { convId ->
                        navController.navigate(Routes.chat(convId)) { popUpTo(Routes.CHATS) }
                    },
                )
            }
            composable(Routes.NEW_GROUP) {
                NewGroupScreen(
                    onBack = { navController.popBackStack() },
                    onCreated = { convId -> navController.navigate(Routes.chat(convId)) { popUpTo(Routes.CHATS) } },
                )
            }
            composable(
                Routes.MEDIA,
                arguments = listOf(
                    navArgument(Routes.ARG_CONV_ID) { type = NavType.StringType },
                    navArgument(Routes.ARG_MSG_ID) { type = NavType.StringType },
                ),
            ) { entry ->
                MediaViewerScreen(
                    convId = entry.arguments?.getString(Routes.ARG_CONV_ID).orEmpty(),
                    msgId = entry.arguments?.getString(Routes.ARG_MSG_ID).orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.CALL, arguments = listOf(navArgument(Routes.ARG_CALL_ID) { type = NavType.StringType })) { entry ->
                CallScreen(
                    callId = entry.arguments?.getString(Routes.ARG_CALL_ID).orEmpty(),
                    onEnded = { navController.popIfCurrent(Routes.CALL) },
                )
            }
            composable(Routes.INCOMING_CALL, arguments = listOf(navArgument(Routes.ARG_CALL_ID) { type = NavType.StringType })) { entry ->
                val callId = entry.arguments?.getString(Routes.ARG_CALL_ID).orEmpty()
                IncomingCallScreen(
                    callId = callId,
                    onAnswered = {
                        if (navController.currentRoute() != Routes.CALL) {
                            navController.navigate(Routes.call(callId)) { popUpTo(Routes.INCOMING_CALL) { inclusive = true } }
                        }
                    },
                    onDismissed = { navController.popIfCurrent(Routes.INCOMING_CALL) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onDevices = { navController.navigate(Routes.DEVICES) },
                    onLinkDevice = { navController.navigate(Routes.LINK_DEVICE) },
                    // The auth observer also reroutes on Locked; this makes it immediate and idempotent.
                    onLoggedOut = { navController.replaceStackIfNotThere(Routes.UNLOCK) },
                )
            }
            composable(Routes.DEVICES) {
                DevicesScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.LINK_DEVICE) {
                LinkDeviceScreen(onBack = { navController.popBackStack() })
            }
        }
        // WhatsApp-style passcode moments (create after a week, re-enter every 30 days).
        if (authState is AuthState.Unlocked && !inCall) com.guftugu.app.ui.passcode.PasscodeGate(graph)
    }

}

private fun startDestination(state: AuthState): String = when (state) {
    is AuthState.NotEnrolled -> Routes.JOIN
    is AuthState.Locked, is AuthState.Revoked -> Routes.UNLOCK // Unlock renders the "removed from server" view
    is AuthState.Unlocked -> Routes.CHATS
}

/** Screens a successful unlock / enrolment leaves from. */
private fun isAuthEntry(route: String): Boolean = route == Routes.JOIN || route == Routes.ENROLL || route == Routes.UNLOCK

private fun isCallRoute(route: String): Boolean = route == Routes.CALL || route == Routes.INCOMING_CALL

private fun NavHostController.currentRoute(): String? = currentBackStackEntry?.destination?.route

/** Make [route] the only entry on the back stack. */
private fun NavHostController.replaceStack(route: String) {
    navigate(route) { popUpTo(0) { inclusive = true }; launchSingleTop = true }
}

private fun NavHostController.replaceStackIfNotThere(route: String) {
    if (currentRoute() != route) replaceStack(route)
}

/** Pop only when [route] is still on top (an auth reroute may already have replaced the stack). */
private fun NavHostController.popIfCurrent(route: String) {
    if (currentRoute() == route) popBackStack()
}
