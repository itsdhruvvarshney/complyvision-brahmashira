package com.example.complyvision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

enum class AssistPhase { STARTING, SPEAKING, LISTENING, THINKING, GUIDING, CAPTURING, CHECKING, IDLE }

/** What the Accessibility screen shows (large text for low-vision users and for anyone helping) */
data class AssistUi(
    val phase: AssistPhase = AssistPhase.STARTING,
    val said: String = "",
    val heard: String = "",
    val cue: String = "",
    /** Object found in the last guidance frame: x0, y0, x1, y1 on 0..1 of the upright camera frame */
    val box: List<Float>? = null,
    val ready: Boolean = false,
    val target: String? = null
)

/** A photo of the current item; every side photographed is sent together so the reader sees the whole pack */
class AssistPhoto(val jpeg: ByteArray, val label: String, val width: Int, val height: Int)

private sealed interface Step {
    data object Greet : Step
    data object Listen : Step
    data class Turn(val now: JSONObject, val captures: List<AssistPhoto> = emptyList(), val goal: String = "") : Step
    data class Guide(val target: String, val goal: String) : Step
    data class Look(val target: String, val goal: String) : Step
    data object Compliance : Step
    data class Say(val text: String, val then: Step?) : Step
    data object Exit : Step
}

/**
 * The Accessibility Mode conversation. The analyzer's Gemini "brain" decides what to say and what to do next
 * (listen, guide the user to show something, take a photo, run the compliance check, torch, exit); this class
 * carries those decisions out with the camera, speaker and microphone, one step at a time.
 *
 * Gestures (whole screen): tap = talk now / take the photo now while guiding, double tap = repeat, long press = help.
 */
class AssistAgent(
    private val scope: CoroutineScope,
    private val voice: AssistVoice,
    private val camera: AssistCamera,
    private val onExit: () -> Unit
) {
    private val _ui = MutableStateFlow(AssistUi())
    val ui: StateFlow<AssistUi> = _ui

    private val history = ArrayDeque<JSONObject>()
    private var memory = ""
    private var lang = "hi"
    private val photos = mutableListOf<AssistPhoto>()
    private var job: Job? = null
    private var started = false
    private var lastSpoken = ""
    private var silences = 0
    private var micFailures = 0
    private var serverFailures = 0
    private var torchAnnounced = false
    @Volatile private var captureNow = false

    /** Debug builds: keeps each photo taken so it can be inspected */
    var debugDump: ((label: String, jpeg: ByteArray) -> Unit)? = null

    // ------------------------------------------------------------------ lifecycle and gestures

    fun resume() {
        if (job?.isActive == true) return
        if (!started) {
            started = true
            run(Step.Greet)
        } else {
            run(Step.Listen)
        }
    }

    fun pause() {
        job?.cancel()
        job = null
        voice.stopSpeaking()
        phase(AssistPhase.IDLE)
    }

    fun tap() {
        if (_ui.value.phase == AssistPhase.GUIDING) {
            captureNow = true
            return
        }
        voice.stopSpeaking()
        run(Step.Listen)
    }

    fun doubleTap() = run(Step.Say(lastSpoken.ifBlank { t("अभी तक कुछ नहीं कहा।", "Abhi tak kuch nahin kaha.", "I haven't said anything yet.") }, Step.Listen))

    fun longPress() = run(Step.Say(t(
        "बात करने के लिए स्क्रीन पर एक बार टैप कीजिए। दो बार टैप करने पर मैं पिछली बात दोहराऊँगा। फ़ोटो के समय टैप करने पर तुरंत फ़ोटो ले लूँगा। बंद करने के लिए बंद करो बोलिए।",
        "Baat karne ke liye screen par ek baar tap kijiye. Do baar tap karne par main pichhli baat dohraunga. Photo ke samay tap karne par turant photo le lunga. Band karne ke liye band karo boliye.",
        "Tap the screen once to talk. Double tap and I will repeat the last thing I said. While I am guiding you, a tap takes the photo at once. Say close or stop to leave."
    ), Step.Listen))

    /** "Khul Ja Sim Sim" again while already open */
    fun wakeAgain() {
        voice.stopSpeaking()
        run(Step.Say(t("हाँ, बोलिए।", "Haan, boliye.", "Yes, I'm listening."), Step.Listen))
    }

    fun toggleTorch() {
        camera.setTorch(!camera.torch.value)
    }

    /** Debug builds: treat [text] as if the user had said it (see WakeDebugReceiver) */
    fun inject(text: String) {
        voice.stopSpeaking()
        _ui.update { it.copy(heard = text) }
        remember("user", text)
        run(Step.Turn(JSONObject().put("type", "user").put("text", text)))
    }

    private fun run(first: Step) {
        val previous = job
        job = scope.launch {
            previous?.cancelAndJoin()
            var step: Step? = first
            while (step != null && isActive) {
                step = try {
                    perform(step)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Step failed: $step", e)
                    voice.errorTone()
                    Step.Say(t("कुछ गड़बड़ हो गई। फिर से बोलिए।", "Kuch gadbad ho gayi. Phir se boliye.", "Something went wrong. Please say that again."), Step.Listen)
                }
            }
            if (isActive) phase(AssistPhase.IDLE)
        }
    }

    private suspend fun perform(step: Step): Step? = when (step) {
        // Spoken locally (no server round trip) so the user hears something the moment the screen opens
        Step.Greet -> {
            voice.awaitReady()
            val greeting = t(
                "नमस्ते, मैं ComplyVision साथी हूँ। कैमरा चालू है। बताइए, क्या देखना है? बात करने के लिए कभी भी स्क्रीन पर टैप कर सकते हैं।",
                "Namaste, main ComplyVision Saathi hoon. Camera chalu hai. Bataiye, kya dekhna hai? Baat karne ke liye kabhi bhi screen par tap kar sakte hain.",
                "Hello, I am ComplyVision Saathi. The camera is on. What would you like me to look at? You can tap the screen any time to talk."
            )
            remember("assistant", greeting)
            Step.Say(greeting, Step.Listen)
        }
        Step.Listen -> listen()
        is Step.Turn -> turn(step)
        is Step.Guide -> guide(step.target, step.goal)
        is Step.Look -> look(step.target, step.goal)
        Step.Compliance -> compliance()
        is Step.Say -> {
            say(step.text)
            step.then
        }
        Step.Exit -> {
            onExit()
            null
        }
    }

    // ------------------------------------------------------------------ steps

    private suspend fun say(text: String) {
        if (text.isBlank()) return
        _ui.update { it.copy(phase = AssistPhase.SPEAKING, said = text, cue = "", box = null) }
        lastSpoken = text
        voice.speak(text, lang)
    }

    private suspend fun listen(): Step? {
        _ui.update { it.copy(phase = AssistPhase.LISTENING, heard = "", cue = "", box = null) }
        voice.listeningTone()
        delay(180)   // keep the tone out of the recording
        return when (val heard = voice.listen(lang) { partial -> _ui.update { it.copy(heard = partial) } }) {
            is Heard.Speech -> {
                silences = 0
                micFailures = 0
                _ui.update { it.copy(heard = heard.text) }
                remember("user", heard.text)
                Step.Turn(JSONObject().put("type", "user").put("text", heard.text)
                    .put("alternatives", JSONArray(heard.alternatives.drop(1))))
            }
            Heard.Silence -> {
                silences++
                if (silences < 2) Step.Listen
                else {
                    silences = 0
                    Step.Say(t(
                        "जब भी बात करनी हो, स्क्रीन पर कहीं भी टैप कीजिए।",
                        "Jab bhi baat karni ho, screen par kahin bhi tap kijiye.",
                        "Whenever you want to talk, tap anywhere on the screen."
                    ), null)
                }
            }
            is Heard.Failed -> {
                micFailures++
                when {
                    heard.code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        Step.Say(t("माइक की अनुमति नहीं है।", "Mic ki anumati nahin hai.", "Microphone permission is missing."), null)
                    micFailures < 3 -> {
                        delay(700)
                        Step.Listen
                    }
                    else -> {
                        micFailures = 0
                        Step.Say(t(
                            "आवाज़ सुनने में दिक्कत हो रही है। इंटरनेट जाँचिए और स्क्रीन पर टैप करके फिर बोलिए।",
                            "Awaaz sunne mein dikkat ho rahi hai. Internet jaanchiye aur screen par tap karke phir boliye.",
                            "I am having trouble hearing you. Check the internet, then tap the screen and speak again."
                        ), null)
                    }
                }
            }
        }
    }

    private suspend fun turn(step: Step.Turn): Step? {
        _ui.update { it.copy(phase = AssistPhase.THINKING, cue = "", box = null) }
        val ticking = scope.launch {
            while (isActive) {
                delay(1_400)
                voice.thinkingTone()
            }
        }
        val response = try {
            val body = JSONObject()
                .put("now", step.now)
                .put("history", JSONArray(history.toList()))
                .put("memory", memory)
                .put("lang", lang)
                .put("script", if (voice.hindiVoice) "devanagari" else "latin")
                .put("today", LocalDate.now().toString())
                .put("goal", step.goal)
            if (step.captures.isEmpty()) {
                camera.frame(512)?.let { body.put("frame", base64(AssistCamera.jpeg(it, 70))) }
            } else {
                body.put("captures", JSONArray(step.captures.map { JSONObject().put("image", base64(it.jpeg)).put("label", it.label) }))
            }
            AnalyzerClient.postJson("/assist/turn", body, 75_000)
        } catch (e: AnalyzerException) {
            return serverTrouble(e, step)
        } finally {
            ticking.cancel()
        }
        serverFailures = 0

        memory = response.optString("memory", memory)
        response.optString("lang").takeIf { it == "hi" || it == "en" }?.let { lang = it }
        if (!response.isNull("speech_rate")) voice.rate = response.optDouble("speech_rate", 1.0).toFloat()
        if (response.optBoolean("new_object") && step.captures.isEmpty()) photos.clear()
        val speak = response.optString("speak").trim()
        if (speak.isNotEmpty()) remember("assistant", speak)

        val target = response.optString("target").takeIf { it.isNotBlank() && it != "null" }
        val goal = response.optString("goal").takeIf { it.isNotBlank() && it != "null" } ?: "identify the item"
        val next: Step? = when (response.optString("action")) {
            "guide" -> Step.Guide(target ?: "any_side", goal)
            "look" -> Step.Look(target ?: "scene", goal)
            "compliance" -> Step.Compliance
            "torch_on" -> { camera.setTorch(true); Step.Listen }
            "torch_off" -> { camera.setTorch(false); Step.Listen }
            "exit" -> Step.Exit
            else -> Step.Listen
        }
        return if (speak.isEmpty()) next else Step.Say(speak, next)
    }

    /** Offline / busy server: say so, retry twice, then wait for a tap */
    private suspend fun serverTrouble(e: AnalyzerException, retry: Step.Turn): Step? {
        Log.w(TAG, "Assistant call failed: ${e.failure} ${e.message}")
        voice.errorTone()
        // HTTP 404: the analyzer answering is an older version without the assistant; retrying cannot help
        if (e.message.orEmpty().contains("HTTP 404")) {
            serverFailures = 0
            return Step.Say(t(
                "ComplyVision सर्वर पुराना है, उसमें यह सुविधा नहीं है। सर्वर को अपडेट करके फिर से चालू कीजिए।",
                "ComplyVision server purana hai, usmein yeh suvidha nahin hai. Server ko update karke phir se chalu kijiye.",
                "The ComplyVision server is out of date and does not have the assistant. Please update and restart it."
            ), null)
        }
        serverFailures++
        if (serverFailures > 2) {
            serverFailures = 0
            return Step.Say(t(
                "अभी ComplyVision सर्वर नहीं मिल रहा। थोड़ी देर बाद स्क्रीन पर टैप करके फिर कोशिश कीजिए।",
                "Abhi ComplyVision server nahin mil raha. Thodi der baad screen par tap karke phir koshish kijiye.",
                "I can't reach the ComplyVision server right now. Tap the screen to try again in a little while."
            ), null)
        }
        say(if (e.failure == AnalyzerFailure.OFFLINE) t(
            "सर्वर से जुड़ नहीं पा रहा। इंटरनेट जाँचिए, मैं फिर कोशिश कर रहा हूँ।",
            "Server se jud nahin pa raha. Internet jaanchiye, main phir koshish kar raha hoon.",
            "I can't connect to the server. Please check the internet; I am trying again."
        ) else t("एक पल रुकिए, फिर से कोशिश कर रहा हूँ।", "Ek pal rukiye, phir se koshish kar raha hoon.", "One moment, trying again."))
        delay(2_500L * serverFailures)
        return retry
    }

    /**
     * Live guidance: frames go to the vision model, which locates the object; the analyzer turns that into a
     * short cue ("thoda baayen", "paas laaiye", "palatiye"). Two good frames in a row (or one while the picture is
     * steady) take the photo. A tap takes it immediately; after 45 s the best effort is used.
     */
    private suspend fun guide(target: String, goal: String): Step? {
        captureNow = false
        _ui.update { it.copy(phase = AssistPhase.GUIDING, target = target, cue = "", box = null, ready = false) }
        val started = System.currentTimeMillis()
        var readyStreak = 0
        var errors = 0
        var lastCue = ""
        var lastCueAt = 0L
        var timedOut = true
        while (System.currentTimeMillis() - started < GUIDE_TIMEOUT_MS) {
            if (captureNow) {
                timedOut = false
                break
            }
            val m = camera.metrics.value
            if (m.brightness < 40 && camera.hasFlash && !camera.torch.value) {
                camera.setTorch(true)
                if (!torchAnnounced) {
                    torchAnnounced = true
                    voice.speak(t("रोशनी कम है, मैंने टॉर्च जला दी है।", "Roshni kam hai, maine torch jala di hai.",
                        "It is dark, so I turned on the flashlight."), lang)
                }
            }
            val frame = camera.frame(512)
            if (frame == null) {
                delay(300)
                continue
            }
            val r = try {
                AnalyzerClient.postJson("/assist/frame", JSONObject()
                    .put("image", base64(AssistCamera.jpeg(frame, 72)))
                    .put("target", target).put("goal", goal).put("lang", lang), 15_000)
            } catch (e: AnalyzerException) {
                Log.w(TAG, "Guidance frame failed: ${e.message}")
                if (++errors >= 3) break else { delay(600); continue }
            }
            if (!r.optBoolean("available", true)) {
                if (++errors >= 3) break else { delay(600); continue }
            }
            errors = 0
            val box = r.optJSONArray("box")?.let { a -> List(a.length()) { a.optDouble(it).toFloat() } }
            val ready = r.optBoolean("ready")
            val cue = r.optString("say")
            _ui.update { it.copy(cue = cue, box = box, ready = ready) }

            if (ready) {
                readyStreak++
                if (readyStreak >= 2 || camera.metrics.value.motion < 6f) {
                    voice.say(cue, lang)
                    timedOut = false
                    break
                }
                voice.sonar(true)
            } else {
                readyStreak = 0
                val now = System.currentTimeMillis()
                if (cue.isNotBlank() && (cue != lastCue || now - lastCueAt > 4_000) && !(voice.speaking && cue == lastCue)) {
                    voice.say(cue, lang)
                    voice.vibrate(25)
                    lastCue = cue
                    lastCueAt = now
                } else if (!voice.speaking) {
                    voice.sonar(r.optDouble("score", 0.0) > 0.6)
                }
            }
        }
        val photo = shoot(target) ?: return Step.Say(t(
            "फ़ोटो नहीं ले पाया। कैमरा ठीक से चल नहीं रहा, फिर से कोशिश करते हैं।",
            "Photo nahin le paaya. Camera theek se chal nahin raha, phir se koshish karte hain.",
            "I couldn't take the photo. Let's try again."
        ), Step.Listen)
        photos += photo
        while (photos.size > 3) photos.removeAt(0)
        val event = if (timedOut) "guide_timeout" else "capture"
        remember("event", "photo taken: $target" + if (timedOut) " (guidance timed out, best effort)" else "")
        return Step.Turn(JSONObject().put("type", "event").put("event", event).put("target", target).put("goal", goal),
            photos.toList(), goal)
    }

    private suspend fun look(target: String, goal: String): Step? {
        val photo = shoot(target) ?: return Step.Say(t("फ़ोटो नहीं ले पाया।", "Photo nahin le paaya.", "I couldn't take the photo."), Step.Listen)
        val sendAlong = if (target == "scene") listOf(photo) else {
            photos += photo
            while (photos.size > 3) photos.removeAt(0)
            photos.toList()
        }
        remember("event", "photo taken without guidance: $target")
        return Step.Turn(JSONObject().put("type", "event").put("event", "look").put("target", target).put("goal", goal), sendAlong, goal)
    }

    private suspend fun shoot(label: String): AssistPhoto? {
        _ui.update { it.copy(phase = AssistPhase.CAPTURING) }
        camera.focusCentre()
        delay(450)
        val bmp = camera.photo(1600) ?: return null
        voice.shutterSound()
        voice.vibrate(40, 60, 40)
        val jpeg = AssistCamera.jpeg(bmp, 85)
        debugDump?.invoke(label, jpeg)
        return AssistPhoto(jpeg, label, bmp.width, bmp.height)
    }

    /** ComplyVision's full label check (OCR + rule engine + Gemini) on the photos of the current item */
    private suspend fun compliance(): Step? {
        if (photos.isEmpty()) {
            return Step.Turn(JSONObject().put("type", "event").put("event", "compliance_result")
                .put("data", JSONObject().put("error", "no photos of the product have been taken yet")))
        }
        _ui.update { it.copy(phase = AssistPhase.CHECKING) }
        val ticking = scope.launch {
            var n = 0
            while (isActive) {
                delay(2_000)
                if (++n % 8 == 0) voice.say(t("जाँच चल रही है, थोड़ा इंतज़ार कीजिए।", "Jaanch chal rahi hai, thoda intezaar kijiye.",
                    "Still checking, please wait."), lang)
                else if (!voice.speaking) voice.thinkingTone()
            }
        }
        val data = try {
            val images = photos.mapIndexed { i, p ->
                val preview = BitmapFactory.decodeByteArray(p.jpeg, 0, p.jpeg.size, BitmapFactory.Options().apply { inSampleSize = 8 })
                    ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                ValidatedImage(p.jpeg, "image/jpeg", "accessibility_${i + 1}_${p.label}.jpg", p.width, p.height, preview)
            }
            val report = AnalyzerClient.analyze(images, mapOf(
                "mode" to "consumer", "source" to "accessibility",
                "photo_labels" to photos.joinToString(",") { it.label }
            )) { }
            compact(report)
        } catch (e: AnalyzerException) {
            JSONObject().put("error", e.message ?: "the check could not be completed")
        } finally {
            ticking.cancel()
        }
        remember("event", "compliance check finished")
        return Step.Turn(JSONObject().put("type", "event").put("event", "compliance_result").put("data", data))
    }

    /** The parts of a full report the brain needs to explain the result in a few sentences */
    private fun compact(report: JSONObject): JSONObject {
        val summary = report.optJSONObject("summary") ?: JSONObject()
        val rules = report.optJSONArray("rules") ?: JSONArray()
        val failed = JSONArray()
        val review = JSONArray()
        for (i in 0 until rules.length()) {
            val r = rules.optJSONObject(i) ?: continue
            val line = r.optString("title") + (r.optString("reason").takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
            when (r.optString("status")) {
                "FAIL" -> if (failed.length() < 6) failed.put(line.take(220))
                "REVIEW" -> if (review.length() < 4) review.put(line.take(220))
            }
        }
        val out = JSONObject()
        for (k in listOf("verdict", "score", "passed", "failed", "review", "headline", "category", "pass_threshold")) {
            if (summary.has(k)) out.put(k, summary.get(k))
        }
        out.put("failed_rules", failed).put("review_rules", review)
        report.optJSONObject("intelligence")?.optJSONObject("overall")?.let { out.put("consumer_overview", it) }
        return out
    }

    // ------------------------------------------------------------------ helpers

    private fun remember(role: String, text: String) {
        history.addLast(JSONObject().put("role", role).put("text", text.take(600)))
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    private fun phase(p: AssistPhase) = _ui.update { it.copy(phase = p) }

    /** Local phrases: Devanagari Hindi, Hinglish in Latin letters (no Hindi voice installed), English */
    private fun t(hi: String, hiLatin: String, en: String) = when {
        lang == "en" -> en
        voice.hindiVoice -> hi
        else -> hiLatin
    }

    private fun base64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val TAG = "AssistAgent"
        const val MAX_HISTORY = 60
        const val GUIDE_TIMEOUT_MS = 45_000L
    }
}
