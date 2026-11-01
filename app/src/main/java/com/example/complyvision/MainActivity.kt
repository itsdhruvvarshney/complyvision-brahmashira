package com.example.complyvision

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isTtsReady by mutableStateOf(false)

    // Triple Volume-Up Hardware Trigger State
    private var volumeUpPressCount = 0
    private var lastVolumeUpTimestamp = 0L
    private var volumeAccessibilityTrigger by mutableIntStateOf(0)

    // Utterances still being spoken: the wake word listener pauses meanwhile (the greeting says the phrase itself)
    private val speaking = mutableSetOf<String>()

    // Asked once: the microphone lets "Khul Ja Sim Sim" open Accessibility Mode while the app is in the recents
    private val micRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        WakeWordService.start(this)
        askForAutoOpen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Android Text-To-Speech Engine
        tts = TextToSpeech(this, this)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF0052B4),
                    secondary = Color(0xFF2E9B3C),
                    tertiary = Color(0xFF0A2239),
                    background = Color(0xFFF2F6FC),
                    surface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ComplyVisionApp(
                        isTtsReady = isTtsReady,
                        volumeTriggerCount = volumeAccessibilityTrigger,
                        onSpeakHinglish = ::speakHinglish,
                        onStopSpeech = ::stopSpeech,
                        onOpenAccessibility = ::openAccessibility
                    )
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val hindiLocale = Locale("hi", "IN")
            val result = tts?.setLanguage(hindiLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale("en", "IN"))
            }
            tts?.setSpeechRate(0.95f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finished(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finished(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
            })
            isTtsReady = true
        }
    }

    private fun speakHinglish(text: String, flush: Boolean = true) {
        if (!isTtsReady) return
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val id = "cv_tts_${System.nanoTime()}"
        synchronized(speaking) {
            if (flush) speaking.clear()
            speaking += id
        }
        WakeWordService.hold("main_tts")
        tts?.speak(text, queueMode, null, id)
    }

    private fun finished(id: String?) {
        val idle = synchronized(speaking) {
            speaking.remove(id)
            speaking.isEmpty()
        }
        if (idle) WakeWordService.release("main_tts")
    }

    private fun stopSpeech() {
        tts?.stop()
        synchronized(speaking) { speaking.clear() }
        WakeWordService.release("main_tts")
    }

    private fun openAccessibility() {
        stopSpeech()
        startActivity(Intent(this, AccessibilityActivity::class.java))
    }

    override fun onStart() {
        super.onStart()
        WakeWordService.visibleActivities++
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("accessibility", MODE_PRIVATE)
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micGranted && !prefs.getBoolean("mic_asked", false)) {
            prefs.edit().putBoolean("mic_asked", true).apply()
            micRequest.launch(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
                else arrayOf(Manifest.permission.RECORD_AUDIO)
            )
        } else {
            // Must start while the app is on screen: Android does not let a microphone service start from the background
            WakeWordService.start(this)
            askForAutoOpen()
        }
    }

    private var autoOpenDialog: android.app.AlertDialog? = null

    /**
     * Android only lets an app bring itself full screen from the background with "Display over other apps";
     * without it "Khul Ja Sim Sim" can only show a notification. Asked on up to three launches, spoken as well.
     */
    private fun askForAutoOpen() {
        val prefs = getSharedPreferences("accessibility", MODE_PRIVATE)
        val asks = prefs.getInt("overlay_asks", 0)
        if (autoOpenDialog != null || asks >= 3 || !WakeWordService.isEnabled(this) ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            WakeWordService.canOpenFromBackground(this)) return
        prefs.edit().putInt("overlay_asks", asks + 1).apply()
        speakHinglish(
            "Khul Ja Sim Sim bolne par app seedha poori screen par khule, iske liye ek baar Display over other apps ki anumati dijiye. Allow button dabaiye.",
            true
        )
        autoOpenDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Open with \"Khul Ja Sim Sim\"")
            .setMessage(
                "So that saying \"Khul Ja Sim Sim\" opens Accessibility Mode full screen from any app, allow " +
                    "ComplyVision to \"Display over other apps\" on the next screen.\n\n" +
                    "Without it Android only shows a notification."
            )
            .setPositiveButton("Allow") { _, _ ->
                stopSpeech()
                startActivity(WakeWordService.overlaySettingsIntent(this))
            }
            .setNegativeButton("Later") { _, _ -> stopSpeech() }
            .setOnDismissListener { autoOpenDialog = null }
            .show()
    }

    override fun onStop() {
        WakeWordService.visibleActivities--
        super.onStop()
    }

    // Detect 3 consecutive Volume-Up presses to open Accessibility Mode anywhere
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val now = System.currentTimeMillis()
            if (now - lastVolumeUpTimestamp > 1800L) {
                volumeUpPressCount = 0
            }
            volumeUpPressCount++
            lastVolumeUpTimestamp = now

            if (volumeUpPressCount >= 3) {
                volumeUpPressCount = 0
                volumeAccessibilityTrigger++
            }
            return true // Prevents system volume popup from blocking the user
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        WakeWordService.release("main_tts")
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

/** Opened by "Learn More" on the home screen */
const val PROJECT_REPO_URL = "https://github.com/itsdhruvvarshney/complyvision-brahmashira"

// All App Screens (Preserving full navigation structure)
enum class AppScreen {
    SPLASH,
    MAIN_HOME,
    CONSUMER_DASHBOARD,
    BUSINESS_PORTAL_HOME,
    ACCESSIBILITY_HOME,
    ORG_PRE_VERIFICATION_HOME,
    OFFICER_LOGIN,
    INSPECTOR_DASHBOARD,
    AUTHORITY_DASHBOARD
}

@Composable
fun ComplyVisionApp(
    isTtsReady: Boolean,
    volumeTriggerCount: Int,
    onSpeakHinglish: (String, Boolean) -> Unit,
    onStopSpeech: () -> Unit,
    onOpenAccessibility: () -> Unit
) {
    val screenStack = remember { mutableStateListOf(AppScreen.SPLASH) }
    var officerSession by remember { mutableStateOf<OfficerSession?>(null) }
    var consumerSession by remember { mutableStateOf<ConsumerSession?>(null) }
    var orgSession by remember { mutableStateOf<OrgSession?>(null) }
    val currentScreen = screenStack.last()

    // Ensure voice instructions are announced once when Main Home Screen opens
    var hasAnnouncedHomeVoice by remember { mutableStateOf(false) }

    fun navigateTo(screen: AppScreen) {
        onStopSpeech()
        if (screen == AppScreen.ACCESSIBILITY_HOME) {
            // Accessibility Mode is its own activity (AccessibilityActivity): camera + voice assistant
            onOpenAccessibility()
            return
        }
        screenStack.add(screen)
    }

    fun navigateBack() {
        if (screenStack.size > 1) {
            onStopSpeech()
            screenStack.removeAt(screenStack.lastIndex)
        }
    }

    fun logoutOfficer() {
        officerSession = null
        navigateBack()
    }

    fun logoutOrg() {
        if (orgSession == null) return
        orgSession = null
        navigateBack()
    }

    fun logoutConsumer() {
        if (consumerSession == null) return
        consumerSession = null
        navigateBack()
    }

    // Open Accessibility Mode automatically when Volume-Up is pressed 3 times
    LaunchedEffect(volumeTriggerCount) {
        if (volumeTriggerCount > 0) {
            navigateTo(AppScreen.ACCESSIBILITY_HOME)
        }
    }

    // Speak Hinglish instruction 3 times when MAIN_HOME screen appears
    LaunchedEffect(currentScreen, isTtsReady) {
        if (currentScreen == AppScreen.MAIN_HOME && isTtsReady && !hasAnnouncedHomeVoice) {
            hasAnnouncedHomeVoice = true
            onSpeakHinglish(
                "Namaste! ComplyVision mein aapka swagat hai. Accessibility mode kholne ke liye Khul Ja Sim Sim boliye, ya teen baar volume up button dabayein.",
                true
            )
            onSpeakHinglish(
                "Accessibility mode ko kholne ke liye teen baar volume up button dabayein.",
                false
            )
            onSpeakHinglish(
                "Accessibility mode ko kholne ke liye teen baar volume up button dabayein.",
                false
            )
        }
    }

    BackHandler(enabled = screenStack.size > 1 && currentScreen != AppScreen.MAIN_HOME) {
        navigateBack()
    }

    when (currentScreen) {
        AppScreen.SPLASH -> AnimatedSplashScreen(
            onSplashFinished = {
                screenStack.clear()
                screenStack.add(AppScreen.MAIN_HOME)
            }
        )

        AppScreen.MAIN_HOME -> MainHomeScreen(onNavigate = ::navigateTo)

        AppScreen.CONSUMER_DASHBOARD -> consumerSession.let { session ->
            if (session == null) {
                ConsumerAuthScreen(onBack = ::navigateBack, onAuthenticated = { consumerSession = it })
            } else {
                ConsumerPortalScreen(consumer = session, onLogout = ::logoutConsumer)
            }
        }

        AppScreen.BUSINESS_PORTAL_HOME -> BusinessPortalHomeScreen(
            onNavigate = ::navigateTo,
            onBack = ::navigateBack
        )

        AppScreen.ORG_PRE_VERIFICATION_HOME -> orgSession.let { session ->
            if (session == null) {
                OrganizationAuthScreen(onBack = ::navigateBack, onLoggedIn = { orgSession = it })
            } else {
                OrganizationPortalScreen(org = session, onLogout = ::logoutOrg)
            }
        }

        // Never pushed: navigateTo opens AccessibilityActivity instead
        AppScreen.ACCESSIBILITY_HOME -> LaunchedEffect(Unit) { navigateBack() }

        AppScreen.OFFICER_LOGIN -> OfficerLoginScreen(
            onBack = ::navigateBack,
            onLoggedIn = { session ->
                officerSession = session
                // Replace the login page so Back from a dashboard never returns to it
                screenStack.removeAt(screenStack.lastIndex)
                screenStack.add(
                    if (session.role == OfficerRole.SUPREME_AUTHORITY) AppScreen.AUTHORITY_DASHBOARD
                    else AppScreen.INSPECTOR_DASHBOARD
                )
            }
        )

        AppScreen.INSPECTOR_DASHBOARD -> officerSession?.let { session ->
            InspectorDashboardScreen(session = session, onLogout = ::logoutOfficer)
        } ?: LaunchedEffect(Unit) { navigateBack() }

        AppScreen.AUTHORITY_DASHBOARD -> officerSession?.let { session ->
            AuthorityShellScreen(session = session, onLogout = ::logoutOfficer)
        } ?: LaunchedEffect(Unit) { navigateBack() }
    }
}

/**
 * Helper to safely load drawable by name without compile errors if an image isn't added yet
 */
@Composable
fun rememberDrawableId(name: String): Int {
    val context = LocalContext.current
    return remember(name) {
        context.resources.getIdentifier(name, "drawable", context.packageName)
    }
}

/**
 * Exact Main Home Screen matching reference image_8aa3c4.jpg
 */
@Composable
fun MainHomeScreen(onNavigate: (AppScreen) -> Unit) {
    val context = LocalContext.current
    val heroBgId = rememberDrawableId("hero_banner_bg")
    val consumerBgId = rememberDrawableId("card_consumer_bg")
    val businessBgId = rememberDrawableId("card_business_bg")
    val accessBgId = rememberDrawableId("card_accessibility_bg")
    val footerBgId = rememberDrawableId("footer_banner_bg")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFF3F8FE), Color(0xFFEAF2FC), Color(0xFFF6F9FD))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
        ) {
            // 1. TOP BAR: Logo + Brand Title + Notification Bell with Red Dot
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.complyvision_logo),
                        contentDescription = "ComplyVision Logo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Box {
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(
                                        SpanStyle(
                                            color = Color(0xFF071D36),
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    ) {
                                        append("Comply")
                                    }
                                    withStyle(
                                        SpanStyle(
                                            color = Color(0xFF0052B4),
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    ) {
                                        append("Vision")
                                    }
                                },
                                fontSize = 25.sp,
                                letterSpacing = (-0.5).sp
                            )
                            // Green leaf accent above the second 'i' in Vision
                            Canvas(
                                modifier = Modifier
                                    .offset(x = 128.dp, y = (-2).dp)
                                    .size(10.dp)
                            ) {
                                val path = Path().apply {
                                    moveTo(0f, size.height)
                                    lineTo(size.width * 0.3f, 0f)
                                    lineTo(size.width, 0f)
                                    lineTo(size.width * 0.7f, size.height)
                                    close()
                                }
                                drawPath(path = path, color = Color(0xFF4CAF50))
                            }
                        }

                        Text(
                            text = "Your AI assistant for product\ncompliance and safety",
                            fontSize = 11.5.sp,
                            color = Color(0xFF4A5B70),
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            // 2. HERO BANNER SECTION (Hand Scanning + Left Text Overlay)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(305.dp)
            ) {
                if (heroBgId != 0) {
                    Image(
                        painter = painterResource(id = heroBgId),
                        contentDescription = "Product Scanner Hero",
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.CenterEnd,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Fallback gradient if hero_banner_bg is not yet placed in res/drawable
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color(0xFFF3F8FE), Color(0xFFD6E9FF))
                                )
                            )
                    )
                }

                // Soft Left-to-Right White Fade so left typography is ultra-crisp like reference
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.68f)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFFF5F9FF),
                                    Color(0xFFF5F9FF).copy(alpha = 0.92f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.64f)
                        .padding(start = 18.dp, top = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    // Pill Badge: SAFER PRODUCTS • HEALTHIER INDIA
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, Color(0xFFDCE6F2)),
                        shadowElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SAFER PRODUCTS",
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF071D36)
                            )
                            Text(
                                text = "  •  ",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF2E9B3C)
                            )
                            Text(
                                text = "HEALTHIER INDIA",
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF071D36)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Big Dual-Color Headline: Because Every Consumer Matters.
                    Text(
                        text = buildAnnotatedString {
                            withStyle(
                                SpanStyle(
                                    color = Color(0xFF071D36),
                                    fontWeight = FontWeight.ExtraBold
                                )
                            ) {
                                append("Because\nEvery\n")
                            }
                            withStyle(
                                SpanStyle(
                                    color = Color(0xFF0052B4),
                                    fontWeight = FontWeight.ExtraBold
                                )
                            ) {
                                append("Consumer\nMatters.")
                            }
                        },
                        fontSize = 31.sp,
                        lineHeight = 33.sp,
                        letterSpacing = (-0.6).sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Small green accent bar under Matters.
                    Box(
                        modifier = Modifier
                            .width(42.dp)
                            .height(3.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF389E48))
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Scan. Verify. Be Informed.",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF071D36)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Get instant insights about product\ncompliance, ingredients, and\nsafety standards using AI.",
                        fontSize = 11.sp,
                        color = Color(0xFF3E5064),
                        lineHeight = 14.5.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(2.5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF389E48))
                    )
                }
            }

            // 3. FOUR STATS PILLS ROW (1M+, 50K+, 98%, Safer Communities)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "1M+",
                    subtitle = "Products Checked",
                    iconType = 0
                )
                StatMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "50K+",
                    subtitle = "Happy Users",
                    iconType = 1
                )
                StatMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "98%",
                    subtitle = "Accuracy Rate",
                    iconType = 2
                )
                StatMetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Safer",
                    subtitle = "Communities",
                    iconType = 3
                )
            }

            // 4. CHOOSE YOUR MODE SECTION HEADER
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Choose Your Mode",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF071D36)
                )
            }

            // 5. THREE SIDE-BY-SIDE MODE CARDS (Exact match to reference image)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Card 1: General Consumer
                ChooseModeCard(
                    modifier = Modifier.weight(1f),
                    title = "General Consumer",
                    description = "Scan and check product details, ingredients, compliance and more.",
                    bgDrawableId = consumerBgId,
                    fallbackBgColors = listOf(Color(0xFFE7F3FF), Color(0xFFC7E4FF)),
                    badgeColor = Color(0xFF0066CC),
                    iconType = 0,
                    onClick = { onNavigate(AppScreen.CONSUMER_DASHBOARD) }
                )

                // Card 2: Business Portal
                ChooseModeCard(
                    modifier = Modifier.weight(1f),
                    title = "Business Portal",
                    description = "For manufacturers, retailers and compliance teams.",
                    bgDrawableId = businessBgId,
                    fallbackBgColors = listOf(Color(0xFFE6F9EE), Color(0xFFC6F0D8)),
                    badgeColor = Color(0xFF15803D),
                    iconType = 1,
                    onClick = { onNavigate(AppScreen.BUSINESS_PORTAL_HOME) }
                )

                // Card 3: Accessibility Mode (Opens by tap OR 3x Volume Up!)
                ChooseModeCard(
                    modifier = Modifier.weight(1f),
                    title = "Accessibility Mode",
                    description = "Voice-guided experience for visually impaired users.",
                    bgDrawableId = accessBgId,
                    fallbackBgColors = listOf(Color(0xFFF2EAFF), Color(0xFFE2D1FF)),
                    badgeColor = Color(0xFF5B21B6),
                    iconType = 2,
                    onClick = { onNavigate(AppScreen.ACCESSIBILITY_HOME) }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 6. FOOTER NATURE BANNER ("Building a Safer, Healthier Tomorrow")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(108.dp)
                    .shadow(6.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
            ) {
                if (footerBgId != 0) {
                    Image(
                        painter = painterResource(id = footerBgId),
                        contentDescription = "Building a Safer Tomorrow",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(Color(0xFF0B3B24), Color(0xFF1E6B3A))
                                )
                            )
                    )
                }

                // Dark gradient overlay on left for white text legibility
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF041E11).copy(alpha = 0.85f),
                                    Color(0xFF041E11).copy(alpha = 0.45f),
                                    Color(0xFF041E11).copy(alpha = 0.55f)
                                )
                            )
                        )
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Building a Safer,\nHealthier Tomorrow",
                                color = Color.White,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                lineHeight = 19.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Through technology, transparency\nand consumer awareness.",
                                color = Color.White.copy(alpha = 0.88f),
                                fontSize = 10.sp,
                                lineHeight = 13.sp
                            )
                        }

                        Surface(
                            color = Color(0xFF0F3823).copy(alpha = 0.75f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, Color(0xFF81C784)),
                            modifier = Modifier.clickable {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_REPO_URL)))
                                } catch (_: android.content.ActivityNotFoundException) {
                                    // No browser installed: nothing to open the link with
                                }
                            }
                        ) {
                            Text(
                                text = "Learn More  →",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun StatMetricCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    iconType: Int
) {
    Surface(
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        shadowElevation = 3.dp,
        border = BorderStroke(0.8.dp, Color(0xFFE5EEF8))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        if (iconType == 1) Color(0xFFE3F2FD) else Color(0xFFE8F5E9)
                    ),
                contentAlignment = Alignment.Center
            ) {
                StatVectorIcon(iconType = iconType, modifier = Modifier.size(16.dp))
            }

            Spacer(modifier = Modifier.width(5.dp))

            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF071D36),
                    maxLines = 1
                )
                Text(
                    text = subtitle,
                    fontSize = 7.5.sp,
                    color = Color(0xFF5A6B80),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ChooseModeCard(
    modifier: Modifier = Modifier,
    title: String,
    description: String,
    bgDrawableId: Int,
    fallbackBgColors: List<Color>,
    badgeColor: Color,
    iconType: Int,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(195.dp)
            .shadow(5.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(1.dp, Color.White)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Card Background Image (Milk/Fruits, Boxes, or Purple Mic)
            if (bgDrawableId != 0) {
                Image(
                    painter = painterResource(id = bgDrawableId),
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(fallbackBgColors))
                )
            }

            // Content Column
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
            ) {
                // Top Circular Icon Badge
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .shadow(4.dp, CircleShape)
                        .clip(CircleShape)
                        .background(badgeColor)
                        .border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    ModeVectorIcon(iconType = iconType, modifier = Modifier.size(20.dp))
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF071D36),
                    lineHeight = 14.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = description,
                    fontSize = 9.sp,
                    color = Color(0xFF334155),
                    lineHeight = 11.5.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.weight(1f))

                // Bottom-Left Small White Circle Arrow Button '>'
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .shadow(2.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "›",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF071D36),
                        modifier = Modifier.offset(y = (-1).dp)
                    )
                }
            }
        }
    }
}

// ============================================================================
// CUSTOM VECTOR ICONS (Zero external library dependencies -> Zero compile errors)
// ============================================================================

@Composable
fun ModeVectorIcon(iconType: Int, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (iconType) {
            0 -> { // Shopping Cart Icon (General Consumer)
                val path = Path().apply {
                    moveTo(w * 0.12f, h * 0.22f)
                    lineTo(w * 0.26f, h * 0.22f)
                    lineTo(w * 0.36f, h * 0.65f)
                    lineTo(w * 0.78f, h * 0.65f)
                    lineTo(w * 0.86f, h * 0.34f)
                    lineTo(w * 0.30f, h * 0.34f)
                }
                drawPath(path, Color.White, style = stroke)
                drawCircle(Color.White, radius = w * 0.08f, center = Offset(w * 0.4f, h * 0.82f))
                drawCircle(Color.White, radius = w * 0.08f, center = Offset(w * 0.74f, h * 0.82f))
            }
            1 -> { // Storefront Icon (Business Portal)
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(w * 0.22f, h * 0.42f),
                    size = Size(w * 0.56f, h * 0.42f),
                    cornerRadius = CornerRadius(4f, 4f),
                    style = stroke
                )
                val roof = Path().apply {
                    moveTo(w * 0.16f, h * 0.42f)
                    lineTo(w * 0.28f, h * 0.2f)
                    lineTo(w * 0.72f, h * 0.2f)
                    lineTo(w * 0.84f, h * 0.42f)
                    close()
                }
                drawPath(roof, Color.White, style = stroke)
            }
            else -> { // Accessibility Person Icon
                drawCircle(Color.White, radius = w * 0.11f, center = Offset(w * 0.5f, h * 0.22f))
                drawLine(
                    color = Color.White,
                    start = Offset(w * 0.2f, h * 0.42f),
                    end = Offset(w * 0.8f, h * 0.42f),
                    strokeWidth = w * 0.1f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color.White,
                    start = Offset(w * 0.5f, h * 0.42f),
                    end = Offset(w * 0.5f, h * 0.64f),
                    strokeWidth = w * 0.1f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color.White,
                    start = Offset(w * 0.5f, h * 0.64f),
                    end = Offset(w * 0.34f, h * 0.88f),
                    strokeWidth = w * 0.1f,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = Color.White,
                    start = Offset(w * 0.5f, h * 0.64f),
                    end = Offset(w * 0.66f, h * 0.88f),
                    strokeWidth = w * 0.1f,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
fun StatVectorIcon(iconType: Int, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        when (iconType) {
            0 -> { // Green Shield
                val shield = Path().apply {
                    moveTo(w * 0.5f, h * 0.1f)
                    lineTo(w * 0.85f, h * 0.25f)
                    lineTo(w * 0.85f, h * 0.55f)
                    cubicTo(w * 0.85f, h * 0.78f, w * 0.5f, h * 0.92f, w * 0.5f, h * 0.92f)
                    cubicTo(w * 0.5f, h * 0.92f, w * 0.15f, h * 0.78f, w * 0.15f, h * 0.55f)
                    lineTo(w * 0.15f, h * 0.25f)
                    close()
                }
                drawPath(shield, Color(0xFF2E9B3C))
            }
            1 -> { // Blue Users
                drawCircle(Color(0xFF0052B4), radius = w * 0.18f, center = Offset(w * 0.5f, h * 0.32f))
                drawArc(
                    color = Color(0xFF0052B4),
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = Offset(w * 0.18f, h * 0.55f),
                    size = Size(w * 0.64f, h * 0.45f)
                )
            }
            2 -> { // Green Checklist Document
                drawRoundRect(
                    color = Color(0xFF2E9B3C),
                    topLeft = Offset(w * 0.2f, h * 0.12f),
                    size = Size(w * 0.6f, h * 0.76f),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
            else -> { // Green Leaf
                val leaf = Path().apply {
                    moveTo(w * 0.2f, h * 0.8f)
                    cubicTo(w * 0.2f, h * 0.25f, w * 0.8f, h * 0.15f, w * 0.85f, h * 0.15f)
                    cubicTo(w * 0.85f, h * 0.7f, w * 0.4f, h * 0.85f, w * 0.2f, h * 0.8f)
                    close()
                }
                drawPath(leaf, Color(0xFF2E9B3C))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceholderModuleScreen(
    title: String,
    subtitle: String,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("← Back", fontWeight = FontWeight.Bold, color = Color(0xFF0052B4))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0052B4)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                fontSize = 14.sp,
                color = Color(0xFF64748B),
                textAlign = TextAlign.Center
            )
        }
    }
}