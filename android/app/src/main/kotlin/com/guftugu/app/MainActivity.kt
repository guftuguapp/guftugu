package com.guftugu.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.guftugu.app.notifications.Notifier
import com.guftugu.app.ui.navigation.GuftuguNavGraph
import com.guftugu.app.ui.navigation.IntentBus
import com.guftugu.app.ui.navigation.JoinIntent
import com.guftugu.app.ui.theme.GuftuguTheme

/**
 * The single Activity. A [FragmentActivity] because BiometricPrompt needs one. Hosts the
 * Compose NavHost, parses `guftugu://join` deep links and notification intents into [IntentBus].
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            GuftuguTheme {
                GuftuguNavGraph(graph = GuftuguApp.graph(this))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        JoinIntent.parse(intent.data)?.let { IntentBus.publishJoin(it) }
        intent.getStringExtra(EXTRA_CONV_ID)?.let { IntentBus.publishOpenConversation(it) }
        intent.getStringExtra(EXTRA_INCOMING_CALL_ID)?.let { IntentBus.publishIncomingCall(it) }
        val action = intent.action
        val callId = intent.getStringExtra(EXTRA_CALL_ID)
        if (callId != null && action != null &&
            (action == Notifier.ACTION_ANSWER || action == Notifier.ACTION_DECLINE || action == Notifier.ACTION_HANGUP)
        ) {
            IntentBus.publishCallAction(action, callId)
        } else if (callId != null && action == null) {
            // Tap on the ongoing-call notification: bring the in-call screen to the front.
            IntentBus.publishOpenCall(callId)
        }
        // Extras are one-shot: a configuration change must not republish them.
        intent.removeExtra(EXTRA_CONV_ID)
        intent.removeExtra(EXTRA_INCOMING_CALL_ID)
        intent.removeExtra(EXTRA_CALL_ID)
    }

    companion object {
        const val EXTRA_CONV_ID = "convId"
        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_INCOMING_CALL_ID = "incomingCallId"
    }
}
