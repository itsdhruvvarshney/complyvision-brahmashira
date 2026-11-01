package com.example.complyvision

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug builds only, for testing on a device without speaking:
 *   action ...debug.WAKE      - feed a transcript to the running [WakeWordService] as if it had been heard
 *   action ...debug.UTTERANCE - say something to the open Accessibility Mode conversation
 * Both take the words in the string extra "text".
 */
class WakeDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text").orEmpty()
        val delivered = when (intent.action) {
            "com.example.complyvision.debug.UTTERANCE" -> AccessibilityActivity.debugInput?.let { it(text); true } ?: false
            else -> WakeWordService.simulate(text.ifBlank { "khul ja sim sim" })
        }
        Log.i("WakeWord", "Debug ${intent.action} \"$text\" ${if (delivered) "delivered" else "dropped: nothing is listening"}")
    }
}
