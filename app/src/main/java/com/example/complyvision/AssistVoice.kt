package com.example.complyvision

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** What the user said, or why nothing usable was heard */
sealed interface Heard {
    data class Speech(val text: String, val alternatives: List<String>) : Heard
    data object Silence : Heard
    data class Failed(val code: Int) : Heard
}

/**
 * Accessibility Mode's voice: text-to-speech that can be awaited, speech recognition that can be awaited,
 * and the short sounds / vibrations that tell a blind user what the app is doing.
 * Must be used from the main thread.
 */
class AssistVoice(private val context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
    private var recognizer: SpeechRecognizer? = null
    private val pending = mutableMapOf<String, () -> Unit>()
    private val tones = try { ToneGenerator(AudioManager.STREAM_MUSIC, 70) } catch (_: Exception) { null }
    private val shutter = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }

    /** Hindi voice installed: replies can be written in Devanagari. Otherwise they are written in Latin Hinglish. */
    var hindiVoice = false
        private set
    var rate = 1.0f
    val speaking: Boolean get() = tts.isSpeaking

    suspend fun awaitReady(): Boolean {
        val ok = ready.await()
        if (ok && !configured) {
            configured = true
            hindiVoice = tts.isLanguageAvailable(HINDI) >= TextToSpeech.LANG_AVAILABLE
            tts.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finish(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finish(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finish(utteranceId)
            })
        }
        return ok
    }

    private var configured = false

    private fun finish(id: String?) {
        val done = synchronized(pending) { pending.remove(id) }
        done?.invoke()
    }

    private fun prepare(lang: String) {
        tts.language = if (lang == "en" || !hindiVoice) ENGLISH else HINDI
        tts.setSpeechRate(rate)
    }

    /** Speaks and returns when finished; cancelling the caller stops the speech */
    suspend fun speak(text: String, lang: String) {
        if (text.isBlank() || !awaitReady()) return
        prepare(lang)
        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine { cont ->
            synchronized(pending) { pending[id] = { if (cont.isActive) cont.resume(Unit) } }
            cont.invokeOnCancellation {
                synchronized(pending) { pending.remove(id) }
                tts.stop()
            }
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id)
        }
    }

    /** Speaks without waiting (live guidance cues); replaces whatever was being said */
    fun say(text: String, lang: String) {
        if (!ready.isCompleted || text.isBlank()) return
        prepare(lang)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cue")
    }

    fun stopSpeaking() {
        tts.stop()
    }

    /** Listens for one utterance. `onPartial` receives the words as they are recognised. */
    suspend fun listen(lang: String, onPartial: (String) -> Unit): Heard {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return Heard.Failed(SpeechRecognizer.ERROR_CLIENT)
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        val locale = if (lang == "en") "en-IN" else "hi-IN"
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        return suspendCancellableCoroutine { cont ->
            fun done(h: Heard) { if (cont.isActive) cont.resume(h) }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }?.let(onPartial)
                }
                override fun onResults(results: Bundle?) {
                    val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
                    done(if (all.isEmpty()) Heard.Silence else Heard.Speech(all.first(), all))
                }
                override fun onError(error: Int) {
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> done(Heard.Silence)
                        else -> {
                            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) resetRecognizer()
                            done(Heard.Failed(error))
                        }
                    }
                }
            })
            cont.invokeOnCancellation { try { r.cancel() } catch (_: Exception) {} }
            try {
                r.startListening(intent)
            } catch (e: Exception) {
                resetRecognizer()
                done(Heard.Failed(SpeechRecognizer.ERROR_CLIENT))
            }
        }
    }

    private fun resetRecognizer() {
        recognizer?.let { try { it.destroy() } catch (_: Exception) {} }
        recognizer = null
    }

    // ------------------------------------------------------------------ sounds and vibration

    fun listeningTone() = tone(ToneGenerator.TONE_PROP_BEEP, 120)
    fun thinkingTone() = tone(ToneGenerator.TONE_PROP_ACK, 60)
    fun sonar(close: Boolean) = tone(if (close) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 70)
    fun errorTone() = tone(ToneGenerator.TONE_PROP_NACK, 200)

    fun shutterSound() {
        try { shutter.play(MediaActionSound.SHUTTER_CLICK) } catch (_: Exception) {}
    }

    private fun tone(type: Int, ms: Int) {
        try { tones?.startTone(type, ms) } catch (_: Exception) {}
    }

    fun vibrate(vararg pattern: Long) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        try {
            v.vibrate(if (pattern.size == 1) VibrationEffect.createOneShot(pattern[0], VibrationEffect.DEFAULT_AMPLITUDE)
                      else VibrationEffect.createWaveform(longArrayOf(0) + pattern, -1))
        } catch (_: Exception) {}
    }

    fun shutdown() {
        resetRecognizer()
        tts.stop()
        tts.shutdown()
        tones?.release()
        shutter.release()
    }

    private companion object {
        val HINDI = Locale("hi", "IN")
        val ENGLISH = Locale("en", "IN")
    }
}
