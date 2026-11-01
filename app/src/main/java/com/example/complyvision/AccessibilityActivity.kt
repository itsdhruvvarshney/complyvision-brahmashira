package com.example.complyvision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Accessibility Mode for blind and low-vision users: the camera opens straight away and a voice assistant
 * (Gemini, through the analyzer) guides the user to show a product, reads it and answers questions.
 *
 * Opened by the Accessibility card on the home screen, by pressing Volume Up three times in the app, or by
 * saying "Khul Ja Sim Sim" while ComplyVision is in the recents ([WakeWordService]). It also shows over the
 * lock screen so a blind user does not have to unlock the phone first.
 */
class AccessibilityActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SOURCE = "source"

        /** Debug builds: the open conversation, so a test can speak to it by adb broadcast */
        @Volatile var debugInput: ((String) -> Unit)? = null

        private val PERMISSIONS = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    private lateinit var voice: AssistVoice
    private lateinit var camera: AssistCamera
    private lateinit var agent: AssistAgent

    private var granted by mutableStateOf(false)
    private var asked = false
    private var wakeOn by mutableStateOf(true)
    private var autoOpen by mutableStateOf(true)

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasCoreGrants()
        // When granted, onResume (which follows this callback) starts the conversation
        if (!granted) {
            lifecycleScope.launch {
                voice.speak(
                    if (voice.awaitReady() && voice.hindiVoice) "कैमरा और माइक की अनुमति के बिना मैं मदद नहीं कर पाऊँगा। किसी की मदद से सेटिंग्स में अनुमति दीजिए।"
                    else "Without camera and microphone permission I can't help. Please ask someone to allow them in Settings.",
                    "hi"
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        AnalyzerClient.init(this)
        voice = AssistVoice(this)
        camera = AssistCamera(this)
        agent = AssistAgent(lifecycleScope, voice, camera, onExit = ::finish)
        if (BuildConfig.DEBUG) {
            debugInput = { text -> runOnUiThread { agent.inject(text) } }
            agent.debugDump = { label, jpeg ->
                java.io.File(cacheDir, "assist").apply { mkdirs() }.resolve("${System.currentTimeMillis()}_$label.jpg").writeBytes(jpeg)
            }
        }
        granted = hasCoreGrants()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Color.Black, surface = Color.Black)) {
                val ui by agent.ui.collectAsState()
                val torch by camera.torch.collectAsState()
                val listening by WakeWordService.running.collectAsState()
                AccessibilityScreen(
                    ui = ui, torch = torch, granted = granted, camera = camera,
                    wakeOn = wakeOn, wakeRunning = listening, autoOpen = autoOpen,
                    onTap = agent::tap, onDoubleTap = agent::doubleTap, onLongPress = agent::longPress,
                    onClose = ::finish, onTorch = agent::toggleTorch,
                    onWakeToggle = { on ->
                        wakeOn = on
                        WakeWordService.setEnabled(this, on)
                    },
                    onAllowAutoOpen = { startActivity(WakeWordService.overlaySettingsIntent(this)) },
                    onGrant = ::askOrOpenSettings
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        WakeWordService.hold("assistant")   // the microphone belongs to the conversation now
        WakeWordService.visibleActivities++
    }

    override fun onResume() {
        super.onResume()
        wakeOn = WakeWordService.isEnabled(this)
        autoOpen = WakeWordService.canOpenFromBackground(this)
        granted = hasCoreGrants()
        when {
            granted -> {
                WakeWordService.start(this)
                agent.resume()
            }
            !asked -> {
                asked = true
                lifecycleScope.launch {
                    voice.speak(
                        if (voice.awaitReady() && voice.hindiVoice) "कैमरा और माइक की अनुमति चाहिए। अगली स्क्रीन पर अनुमति दीजिए।"
                        else "I need camera and microphone permission. Please allow them on the next screen.",
                        "hi"
                    )
                    permissionRequest.launch(PERMISSIONS)
                }
            }
        }
    }

    override fun onPause() {
        agent.pause()
        super.onPause()
    }

    override fun onStop() {
        WakeWordService.visibleActivities--
        WakeWordService.release("assistant")
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (granted && intent.getStringExtra(EXTRA_SOURCE) == "wake") agent.wakeAgain()
    }

    override fun onDestroy() {
        debugInput = null
        voice.shutdown()
        camera.release()
        super.onDestroy()
    }

    private fun hasCoreGrants() = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun askOrOpenSettings() {
        val blocked = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(it)
        }
        if (asked && blocked) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            asked = true
            permissionRequest.launch(PERMISSIONS)
        }
    }
}

private val Accent = Color(0xFFFFD400)
private val Panel = Color(0xE6000000)

@Composable
private fun AccessibilityScreen(
    ui: AssistUi,
    torch: Boolean,
    granted: Boolean,
    camera: AssistCamera,
    wakeOn: Boolean,
    wakeRunning: Boolean,
    autoOpen: Boolean,
    onTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    onClose: () -> Unit,
    onTorch: () -> Unit,
    onWakeToggle: (Boolean) -> Unit,
    onAllowAutoOpen: () -> Unit,
    onGrant: () -> Unit
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraView(camera)
            GuideBox(ui)
        }

        // The whole screen is one large button: tap = talk / take the photo, double tap = repeat, hold = help
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() }, onDoubleTap = { onDoubleTap() }, onLongPress = { onLongPress() })
                }
                .semantics {
                    contentDescription = "Camera assistant. Activate to talk. Long press for help."
                    onClick(label = "Talk") { onTap(); true }
                    onLongClick(label = "Help") { onLongPress(); true }
                }
        )

        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                RoundButton(if (torch) "Torch\nON" else "Torch", "Flashlight ${if (torch) "on" else "off"}", onTorch, active = torch)
                PhaseChip(ui.phase)
                RoundButton("✕", "Close accessibility mode", onClose)
            }
            WakeSetup(wakeOn, wakeRunning, autoOpen, onWakeToggle, onAllowAutoOpen)
            Spacer(Modifier.weight(1f))
            if (!granted) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp).background(Panel, RoundedCornerShape(20.dp)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Camera and microphone are needed", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onGrant, colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth().height(64.dp)) {
                        Text("Allow", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                SpeechPanel(ui)
            }
        }
    }
}

@Composable
private fun CameraView(camera: AssistCamera) {
    val owner = LocalLifecycleOwner.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER   // same framing as the frames sent for guidance
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
    DisposableEffect(owner) {
        camera.bind(owner, view)
        onDispose { camera.unbind() }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}

/** Outline of the object the guidance found, drawn over the letter-boxed 9:16 preview */
@Composable
private fun GuideBox(ui: AssistUi) {
    val box = ui.box ?: return
    if (ui.phase != AssistPhase.GUIDING || box.size != 4) return
    val colour = if (ui.ready) Color(0xFF22C55E) else Accent
    Canvas(Modifier.fillMaxSize()) {
        val scale = minOf(size.width / 9f, size.height / 16f)
        val w = 9f * scale
        val h = 16f * scale
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        drawRoundRect(
            colour,
            topLeft = Offset(left + box[0] * w, top + box[1] * h),
            size = Size((box[2] - box[0]) * w, (box[3] - box[1]) * h),
            cornerRadius = CornerRadius(18f, 18f),
            style = Stroke(width = 10f)
        )
    }
}

@Composable
private fun SpeechPanel(ui: AssistUi) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(Panel, RoundedCornerShape(22.dp))
            .border(2.dp, Accent.copy(alpha = 0.6f), RoundedCornerShape(22.dp))
            .padding(18.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        if (ui.phase == AssistPhase.GUIDING && ui.cue.isNotBlank()) {
            Text(ui.cue, color = if (ui.ready) Color(0xFF4ADE80) else Accent, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 36.sp)
            Spacer(Modifier.height(8.dp))
        }
        if (ui.said.isNotBlank()) {
            Text(ui.said, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 29.sp, maxLines = 7)
        }
        if (ui.heard.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text("You: ${ui.heard}", color = Color(0xFF7DD3FC), fontSize = 19.sp, lineHeight = 25.sp, maxLines = 3)
        }
        Spacer(Modifier.height(8.dp))
        Text("Tap anywhere to talk  •  Double tap to repeat  •  Hold for help", color = Color(0xFFBDBDBD), fontSize = 13.sp)
    }
}

@Composable
private fun PhaseChip(phase: AssistPhase) {
    val (label, colour) = when (phase) {
        AssistPhase.STARTING -> "Starting" to Color(0xFF9CA3AF)
        AssistPhase.SPEAKING -> "Speaking" to Color(0xFF60A5FA)
        AssistPhase.LISTENING -> "Listening…" to Color(0xFF4ADE80)
        AssistPhase.THINKING -> "Thinking…" to Color(0xFFC084FC)
        AssistPhase.GUIDING -> "Guiding" to Accent
        AssistPhase.CAPTURING -> "Taking photo" to Accent
        AssistPhase.CHECKING -> "Checking label…" to Color(0xFFF97316)
        AssistPhase.IDLE -> "Tap to talk" to Color.White
    }
    Row(
        Modifier.background(Panel, RoundedCornerShape(50)).border(2.dp, colour, RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(12.dp).background(colour, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RoundButton(text: String, description: String, onClick: () -> Unit, active: Boolean = false) {
    Box(
        Modifier
            .size(64.dp)
            .background(if (active) Accent else Panel, CircleShape)
            .border(2.dp, Accent, CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                onClick { onClick(); true }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (active) Color.Black else Color.White, fontSize = if (text.length > 2) 12.sp else 24.sp,
            fontWeight = FontWeight.Bold, lineHeight = 13.sp)
    }
}

/** For a sighted helper: the wake phrase switch and the one-time "Display over other apps" permission */
@Composable
private fun WakeSetup(on: Boolean, running: Boolean, autoOpen: Boolean, onToggle: (Boolean) -> Unit, onAllowAutoOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(Panel, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("\"Khul Ja Sim Sim\" opens this screen", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        !on -> "Off"
                        running -> "On - works while ComplyVision is in recents"
                        else -> "On - starts when the microphone is allowed"
                    },
                    color = Color(0xFFBDBDBD), fontSize = 12.sp
                )
            }
            Switch(
                checked = on, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Accent),
                modifier = Modifier.semantics { contentDescription = "Khul Ja Sim Sim wake phrase" }
            )
        }
        if (on && !autoOpen) {
            TextButton(onClick = onAllowAutoOpen, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text("Allow opening from other apps (Display over other apps) ›", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
