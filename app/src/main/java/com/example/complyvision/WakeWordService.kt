package com.example.complyvision

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Listens for "Khul Ja Sim Sim" while ComplyVision is in the recents and the screen is on, and opens
 * Accessibility Mode ([AccessibilityActivity]) when it hears it.
 *
 * It is a microphone foreground service (started from a visible activity, as Android requires) that runs the
 * system speech recogniser in a loop and checks every partial and final result with [WakePhrase]. It pauses
 * while the screen is off, during calls, while Accessibility Mode itself is open and while the app is talking
 * ([hold] / [release]), so it never hears its own voice.
 *
 * Opening an activity from the background needs "Display over other apps"; without it the service shows a
 * full-screen / heads-up notification and says aloud that the notification will open Accessibility Mode.
 */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        private const val TAG = "WakeWord"
        private const val CHANNEL_LISTEN = "accessibility_wake"
        private const val CHANNEL_ALERT = "accessibility_wake_alert"
        private const val NOTIFICATION_ID = 4101
        private const val ALERT_ID = 4102
        private const val ACTION_STOP = "com.example.complyvision.wake.STOP"
        private const val PREFS = "accessibility"
        private const val KEY_ENABLED = "wake_enabled"
        private const val KEY_MUTED = "wake_muted_streams"
        private const val WATCHDOG_MS = 15_000L

        private val holds = MutableStateFlow<Set<String>>(emptySet())
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        /** Number of ComplyVision activities on screen (an activity can be opened directly while one is) */
        @Volatile var visibleActivities = 0

        @Volatile private var instance: WakeWordService? = null

        /** Stop listening until [release] with the same reason (several holds can overlap) */
        fun hold(reason: String) = holds.update { it + reason }
        fun release(reason: String) = holds.update { it - reason }

        fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
            if (enabled) start(context) else context.stopService(Intent(context, WakeWordService::class.java))
        }

        fun hasMicPermission(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** Opening Accessibility Mode from the background works only with "Display over other apps" */
        fun canOpenFromBackground(context: Context) = Settings.canDrawOverlays(context)

        fun overlaySettingsIntent(context: Context) =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Starts listening if enabled and allowed. Call it from a visible activity (microphone services can't start from the background). */
        fun start(context: Context) {
            if (!isEnabled(context) || !hasMicPermission(context) || !SpeechRecognizer.isRecognitionAvailable(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not start the wake word listener", e)
            }
        }

        /** Debug builds: pretend the recogniser heard [text] (adb broadcast, see WakeDebugReceiver) */
        fun simulate(text: String): Boolean = instance?.let { svc ->
            svc.handler.post { svc.check(listOf(text), "debug") }
            true
        } ?: false

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var language = "hi-IN"
    private var screenOn = true
    private var errorStreak = 0
    private var retryAt = 0L
    private var cooldownUntil = 0L
    private val mutedStreams = mutableSetOf<Int>()
    private var tts: TextToSpeech? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action != Intent.ACTION_SCREEN_OFF
            evaluate()
        }
    }

    private val watchdog = Runnable {
        Log.w(TAG, "Recogniser stopped answering; recreating it")
        destroyRecognizer()
        schedule(500)
    }

    private val tick = Runnable { evaluate() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, listeningNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
        } catch (e: Exception) {
            // Started from the background or without the microphone permission
            Log.w(TAG, "Cannot run the microphone service", e)
            stopSelf()
            return
        }
        instance = this
        _running.value = true
        restoreMutedStreams()
        screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { holds.collect { evaluate() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        evaluate()
        // Not sticky: Android may not restart a microphone service from the background anyway
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        instance = null
        _running.value = false
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        destroyRecognizer()
        unmuteBeep()
        tts?.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ listening loop

    private fun shouldListen(): Boolean =
        isEnabled(this) && hasMicPermission(this) && screenOn && holds.value.isEmpty() &&
            System.currentTimeMillis() >= cooldownUntil && !inCall()

    private fun inCall() = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
        audio.mode == AudioManager.MODE_RINGTONE

    /** Starts or stops the recogniser to match the current conditions */
    private fun evaluate() {
        handler.removeCallbacks(tick)
        if (!shouldListen()) {
            if (listening || recognizer != null) {
                destroyRecognizer()   // frees the microphone for Accessibility Mode, calls and other apps
            }
            // Calls and cool-downs end on their own: look again shortly
            if (isEnabled(this) && screenOn && holds.value.isEmpty()) handler.postDelayed(tick, 3_000)
            return
        }
        val wait = retryAt - System.currentTimeMillis()
        if (wait > 0) {
            handler.postDelayed(tick, wait)
            return
        }
        if (!listening) startListening()
    }

    private fun startListening() {
        val r = recognizer ?: try {
            SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(this)
                recognizer = it
            }
        } catch (e: Exception) {
            Log.w(TAG, "No speech recogniser", e)
            schedule(10_000)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            // No silence-length extras: recognisers disagree on their type (Long vs Integer) and the defaults suit a short phrase
        }
        listening = true
        muteBeep()
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            destroyRecognizer()
            schedule(2_000)
            return
        }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
        // Some recognisers never call onReadyForSpeech: never leave a stream muted for long
        handler.postDelayed({ unmuteBeep() }, 1_500)
    }

    /** Listen again after [delayMs] (when conditions still allow it) */
    private fun schedule(delayMs: Long) {
        listening = false
        handler.removeCallbacks(watchdog)
        retryAt = System.currentTimeMillis() + delayMs
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, delayMs)
    }

    private fun destroyRecognizer() {
        handler.removeCallbacks(watchdog)
        listening = false
        recognizer?.let {
            try { it.cancel() } catch (_: Exception) {}
            try { it.destroy() } catch (_: Exception) {}
        }
        recognizer = null
        unmuteBeep()
    }

    override fun onReadyForSpeech(params: Bundle?) {
        handler.postDelayed({ unmuteBeep() }, 250)
    }

    override fun onBeginningOfSpeech() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        check(texts, "partial")
    }

    override fun onResults(results: Bundle?) {
        errorStreak = 0
        val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        if (!check(texts, "final")) schedule(100)
    }

    override fun onError(error: Int) {
        if (!listening) return
        val delay = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                errorStreak = 0
                100L
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                Log.w(TAG, "Microphone permission was revoked")
                stopSelf()
                return
            }
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                if (language == "hi-IN") language = "en-IN"
                destroyRecognizer()
                500L
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                destroyRecognizer()
                backoff(1_000L)
            }
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> backoff(30_000L)
            // ERROR_AUDIO (microphone busy), network and server errors
            else -> backoff(2_000L)
        }
        schedule(delay)
    }

    /** 1 s, 2 s, 4 s ... up to 30 s while the same problem repeats */
    private fun backoff(base: Long): Long {
        errorStreak++
        return (base shl (errorStreak - 1).coerceAtMost(5)).coerceAtMost(30_000L)
    }

    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    /** True (and Accessibility Mode is opened) when one of the transcripts is the wake phrase */
    private fun check(texts: List<String>, kind: String): Boolean {
        if (texts.isEmpty() || !WakePhrase.matchesAny(texts)) return false
        Log.i(TAG, "Wake phrase heard ($kind): ${texts.first()}")
        destroyRecognizer()
        cooldownUntil = System.currentTimeMillis() + 4_000
        vibrate()
        openAccessibility()
        evaluate()
        return true
    }

    // ------------------------------------------------------------------ opening Accessibility Mode

    private fun accessibilityIntent() = Intent(this, AccessibilityActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        .putExtra(AccessibilityActivity.EXTRA_SOURCE, "wake")

    private fun openAccessibility() {
        val intent = accessibilityIntent()
        when {
            visibleActivities > 0 -> startSafely(intent)
            Settings.canDrawOverlays(this) -> startWithOverlay(intent)
            else -> {
                // Android blocks background activity starts: offer the notification (full screen when locked)
                startSafely(intent)
                showOpenNotification()
                say(
                    if (language == "hi-IN") "Accessibility mode kholne ke liye notification par tap kijiye."
                    else "Tap the notification to open Accessibility mode."
                )
            }
        }
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open Accessibility Mode", e)
            showOpenNotification()
        }
    }

    /**
     * Android 15 only lets an app with "Display over other apps" start an activity from the background while one
     * of its overlay windows is visible, so a 1-pixel transparent overlay is shown for a moment.
     */
    @SuppressLint("RtlHardcoded")
    private fun startWithOverlay(intent: Intent) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = View(this)
        val params = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        val added = try {
            wm.addView(view, params)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Overlay not allowed", e)
            false
        }
        startSafely(intent)
        if (added) handler.postDelayed({ try { wm.removeView(view) } catch (_: Exception) {} }, 2_000)
    }

    private fun showOpenNotification() {
        val pi = PendingIntent.getActivity(
            this, 1, accessibilityIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Accessibility Mode")
            .setContentText("Tap to open the ComplyVision camera assistant")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ALERT_ID, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notifications are blocked", e)
        }
    }

    /** Spoken hint for the notification fallback; the user can't see that a notification appeared */
    private fun say(text: String) {
        hold("service_tts")
        val speak = { engine: TextToSpeech ->
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "wake")
            handler.postDelayed({ release("service_tts") }, 4_000)
        }
        tts?.let(speak) ?: run {
            var engine: TextToSpeech? = null
            engine = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    engine?.language = Locale("en", "IN")
                    engine?.let(speak)
                } else {
                    release("service_tts")
                }
            }
            tts = engine
        }
    }

    private fun vibrate() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        try { v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1)) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ recogniser start beep

    /**
     * Many speech services beep every time listening starts, which in a loop would beep every few seconds.
     * The notification / system streams (and media, when nothing is playing) are muted for the instant the
     * recogniser starts. Which streams were muted is saved so a crash can't leave them muted.
     */
    private fun muteBeep() {
        val streams = mutableListOf(AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)
        if (!audio.isMusicActive) streams += AudioManager.STREAM_MUSIC
        for (s in streams) {
            if (s in mutedStreams) continue
            try {
                if (!audio.isStreamMute(s)) {
                    audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0)
                    mutedStreams += s
                }
            } catch (_: Exception) {
                // Do Not Disturb forbids changing the notification stream: just accept the beep
            }
        }
        prefs(this).edit().putString(KEY_MUTED, mutedStreams.joinToString(",")).apply()
    }

    private fun unmuteBeep() {
        if (mutedStreams.isEmpty()) return
        for (s in mutedStreams) {
            try { audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) {}
        }
        mutedStreams.clear()
        prefs(this).edit().remove(KEY_MUTED).apply()
    }

    private fun restoreMutedStreams() {
        prefs(this).getString(KEY_MUTED, null)?.split(",")?.mapNotNull { it.toIntOrNull() }?.let {
            mutedStreams += it
            unmuteBeep()
        }
    }

    // ------------------------------------------------------------------ notifications

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_LISTEN, "Khul Ja Sim Sim listener", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while ComplyVision listens for \"Khul Ja Sim Sim\" to open Accessibility Mode"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "Open Accessibility Mode", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Opens Accessibility Mode after \"Khul Ja Sim Sim\" when the app can't open itself"
        })
    }

    private fun listeningNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 2, accessibilityIntent(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 3, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_LISTEN)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Say \"Khul Ja Sim Sim\"")
            .setContentText("to open ComplyVision Accessibility Mode")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "Open now", open)
            .addAction(0, "Stop listening", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
