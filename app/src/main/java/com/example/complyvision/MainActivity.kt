package com.example.complyvision

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF0052B4),
                    secondary = Color(0xFF2E9B3C),
                    tertiary = Color(0xFF0A2239),
                    background = Color(0xFFF4F9FD),
                    surface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ComplyVisionApp()
                }
            }
        }
    }
}

// All App Screens (Starting with Animated Splash Screen!)
enum class AppScreen {
    SPLASH,
    MAIN_HOME,
    CONSUMER_DASHBOARD,
    BUSINESS_PORTAL_HOME,
    ACCESSIBILITY_HOME,
    LM_ROLE_SELECTION,
    PRE_FSSAI_HOME,
    BIS_VERIFICATION_HOME,
    INSPECTOR_LOGIN,
    AUTHORITY_DASHBOARD
}

@Composable
fun ComplyVisionApp() {
    val screenStack = remember { mutableStateListOf(AppScreen.SPLASH) }
    val currentScreen = screenStack.last()

    fun navigateTo(screen: AppScreen) {
        screenStack.add(screen)
    }

    fun navigateBack() {
        if (screenStack.size > 1) {
            screenStack.removeAt(screenStack.lastIndex)
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

        AppScreen.CONSUMER_DASHBOARD -> PlaceholderModuleScreen(
            title = "General Consumer Portal",
            subtitle = "Front Claim vs. Back Label & Ingredient Verification",
            onBack = ::navigateBack
        )

        AppScreen.BUSINESS_PORTAL_HOME -> BusinessPortalHomeScreen(
            onNavigate = ::navigateTo,
            onBack = ::navigateBack
        )

        AppScreen.LM_ROLE_SELECTION -> LegalMetrologyRoleScreen(
            onNavigate = ::navigateTo,
            onBack = ::navigateBack
        )

        AppScreen.PRE_FSSAI_HOME -> PlaceholderModuleScreen(
            title = "Pre-FSSAI Verification",
            subtitle = "Preliminary Food Label & FSSAI Requirement Check",
            onBack = ::navigateBack
        )

        AppScreen.BIS_VERIFICATION_HOME -> PlaceholderModuleScreen(
            title = "BIS Verification",
            subtitle = "Preliminary Bureau of Indian Standards Check",
            onBack = ::navigateBack
        )

        AppScreen.ACCESSIBILITY_HOME -> PlaceholderModuleScreen(
            title = "Accessibility Mode",
            subtitle = "Voice-Guided Scanning & Hindi/English TTS Support",
            onBack = ::navigateBack
        )

        AppScreen.INSPECTOR_LOGIN -> PlaceholderModuleScreen(
            title = "Inspector Login",
            subtitle = "Authenticate with Login ID & Password to view assigned tasks",
            onBack = ::navigateBack
        )

        AppScreen.AUTHORITY_DASHBOARD -> PlaceholderModuleScreen(
            title = "Supreme Authority Dashboard",
            subtitle = "Inspector Monitoring, Task Oversight & Performance Analytics",
            onBack = ::navigateBack
        )
    }
}

@Composable
fun AnimatedSplashScreen(onSplashFinished: () -> Unit) {
    val fullBrandName = "ComplyVision"
    val fullTagline = "Because Every Consumer Matters."

    var typedBrandChars by remember { mutableIntStateOf(0) }
    var typedTaglineChars by remember { mutableIntStateOf(0) }
    var showCursorOnBrand by remember { mutableStateOf(false) }
    var showCursorOnTagline by remember { mutableStateOf(false) }
    var showDividerLine by remember { mutableStateOf(false) }
    var startLogoAnim by remember { mutableStateOf(false) }

    // Smooth Logo Zoom & Fade Animation
    val logoScale by animateFloatAsState(
        targetValue = if (startLogoAnim) 1f else 0.55f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "logoScale"
    )
    val logoAlpha by animateFloatAsState(
        targetValue = if (startLogoAnim) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "logoAlpha"
    )

    // Green-to-Blue Gradient Divider Line Width Animation
    val dividerWidthFraction by animateFloatAsState(
        targetValue = if (showDividerLine) 1f else 0f,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "dividerWidth"
    )

    // Blinking Cursor Alpha for realistic typing feel
    val infiniteTransition = rememberInfiniteTransition(label = "cursorBlink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(350),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )

    // Master Animation Sequence
    LaunchedEffect(Unit) {
        // 1. Pop-in the Logo
        startLogoAnim = true
        delay(650)

        // 2. Type "ComplyVision" character by character
        showCursorOnBrand = true
        for (i in 1..fullBrandName.length) {
            typedBrandChars = i
            delay(85)
        }
        delay(200)
        showCursorOnBrand = false

        // 3. Type "Because Every Consumer Matters." character by character
        showCursorOnTagline = true
        for (i in 1..fullTagline.length) {
            typedTaglineChars = i
            delay(42)
        }
        delay(150)
        showCursorOnTagline = false

        // 4. Expand the Green-to-Blue Gradient Underline
        showDividerLine = true
        delay(1100)

        // 5. Navigate to Main Home Screen
        onSplashFinished()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Full Screen Background Image (splash_bg)
        Image(
            painter = painterResource(id = R.drawable.splash_bg),
            contentDescription = "Splash Background",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // 2. Center-Top Positioned Logo + Typing Brand Name + Tagline (Matches Reference Image 2)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 150.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ComplyVision 3D Shield & Leaf Logo
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .scale(logoScale)
                    .alpha(logoAlpha)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.complyvision_logo),
                    contentDescription = "ComplyVision Logo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(136.dp)
                        .clip(CircleShape)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Dual-Tone Typed Title ("Comply" in Deep Navy + "Vision" in Royal Blue + Green Leaf Accent)
            Box(contentAlignment = Alignment.TopCenter) {
                val visibleBrand = fullBrandName.take(typedBrandChars)

                Text(
                    text = buildAnnotatedString {
                        val complyPart = visibleBrand.take(6)
                        val visionPart = if (visibleBrand.length > 6) visibleBrand.substring(6) else ""

                        withStyle(
                            style = SpanStyle(
                                color = Color(0xFF071D36), // Deep Navy for "Comply"
                                fontWeight = FontWeight.ExtraBold
                            )
                        ) {
                            append(complyPart)
                        }
                        withStyle(
                            style = SpanStyle(
                                color = Color(0xFF0052B4), // Vibrant Blue for "Vision"
                                fontWeight = FontWeight.ExtraBold
                            )
                        ) {
                            append(visionPart)
                        }
                        if (showCursorOnBrand) {
                            withStyle(
                                style = SpanStyle(
                                    color = Color(0xFF2E9B3C).copy(alpha = cursorAlpha),
                                    fontWeight = FontWeight.Light
                                )
                            ) {
                                append("|")
                            }
                        }
                    },
                    fontSize = 38.sp,
                    letterSpacing = (-0.5).sp,
                    textAlign = TextAlign.Center
                )

                // Little Green Leaf Accent above the second 'i' in "Vision" once typed
                if (typedBrandChars >= 10) {
                    Canvas(
                        modifier = Modifier
                            .offset(x = 82.dp, y = (-4).dp)
                            .size(14.dp)
                    ) {
                        val leafPath = Path().apply {
                            moveTo(0f, size.height)
                            lineTo(size.width, 0f)
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(
                            path = leafPath,
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0xFF7ED957), Color(0xFF2E9B3C))
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Typed Tagline: "Because Every Consumer Matters."
            Text(
                text = buildAnnotatedString {
                    append(fullTagline.take(typedTaglineChars))
                    if (showCursorOnTagline) {
                        withStyle(
                            style = SpanStyle(
                                color = Color(0xFF0052B4).copy(alpha = cursorAlpha),
                                fontWeight = FontWeight.Bold
                            )
                        ) {
                            append("|")
                        }
                    }
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF1E3A5F),
                textAlign = TextAlign.Center,
                modifier = Modifier.heightIn(min = 24.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Animated Green-to-Blue Gradient Underline (Just like Reference Image 2)
            Box(
                modifier = Modifier
                    .width(95.dp * dividerWidthFraction)
                    .height(3.5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF5CB85C), Color(0xFF0052B4))
                        )
                    )
            )
        }
    }
}

@Composable
fun MainHomeScreen(onNavigate: (AppScreen) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Top Hero Header Banner with Brand Logo
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF071D36), Color(0xFF0052B4))
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 22.dp, vertical = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Mini Logo Badge in Header
                Surface(
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 4.dp,
                    modifier = Modifier.size(60.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.complyvision_logo),
                        contentDescription = "ComplyVision Logo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .padding(4.dp)
                            .clip(CircleShape)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Surface(
                        color = Color.White.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = "VIKSIT BHARAT 2047 • AI COMPLIANCE",
                            color = Color(0xFF81C784),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "ComplyVision 2.0",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "Because Every Consumer Matters.",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                }
            }
        }

        // 3 Primary Modules Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Select Portal",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF071D36)
            )

            // Option 1: General Consumer
            PrimaryPortalCard(
                title = "1. General Consumer",
                description = "Product scanning, front-claim vs. back-label analysis, and consumer awareness.",
                badgeText = "Consumer Protection",
                symbolText = "SCAN",
                accentColor = Color(0xFF2E9B3C),
                onClick = { onNavigate(AppScreen.CONSUMER_DASHBOARD) }
            )

            // Option 2: Business Portal
            PrimaryPortalCard(
                title = "2. Business Portal",
                description = "Legal Metrology, Pre-FSSAI Verification, and BIS Verification workflows.",
                badgeText = "Regulatory & B2B",
                symbolText = "B2B",
                accentColor = Color(0xFF0052B4),
                onClick = { onNavigate(AppScreen.BUSINESS_PORTAL_HOME) }
            )

            // Option 3: Accessibility Mode
            PrimaryPortalCard(
                title = "3. Accessibility Mode",
                description = "Voice-guided scanning and audio product information for visually impaired users.",
                badgeText = "Voice Guided (HI / EN)",
                symbolText = "TTS",
                accentColor = Color(0xFF00838F),
                onClick = { onNavigate(AppScreen.ACCESSIBILITY_HOME) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BusinessPortalHomeScreen(
    onNavigate: (AppScreen) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Business Portal", fontWeight = FontWeight.Bold) },
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
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Select Verification Submodule",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF475569)
            )

            PrimaryPortalCard(
                title = "1. Legal Metrology",
                description = "Verification of packaged commodity declarations and inspection workflows.",
                badgeText = "Inspector & Authority",
                symbolText = "LM",
                accentColor = Color(0xFF0052B4),
                onClick = { onNavigate(AppScreen.LM_ROLE_SELECTION) }
            )

            PrimaryPortalCard(
                title = "2. Pre-FSSAI Verification",
                description = "Preliminary verification module related to food product labeling and FSSAI requirements.",
                badgeText = "Food Compliance",
                symbolText = "FSSAI",
                accentColor = Color(0xFF2E9B3C),
                onClick = { onNavigate(AppScreen.PRE_FSSAI_HOME) }
            )

            PrimaryPortalCard(
                title = "3. BIS Verification",
                description = "Preliminary verification module for applicable Bureau of Indian Standards requirements.",
                badgeText = "Standards Check",
                symbolText = "BIS",
                accentColor = Color(0xFF6A1B9A),
                onClick = { onNavigate(AppScreen.BIS_VERIFICATION_HOME) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalMetrologyRoleScreen(
    onNavigate: (AppScreen) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Legal Metrology", fontWeight = FontWeight.Bold) },
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Select Authorized Role",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF475569)
            )

            PrimaryPortalCard(
                title = "Role A: Inspector",
                description = "Assigned inspections, OCR verification, and physical/manual inspection recording.",
                badgeText = "Field Verification",
                symbolText = "INSP",
                accentColor = Color(0xFF0052B4),
                onClick = { onNavigate(AppScreen.INSPECTOR_LOGIN) }
            )

            PrimaryPortalCard(
                title = "Role B: Supreme Authority",
                description = "Inspector monitoring, task oversight, physical inspection tracking, and analytics.",
                badgeText = "Oversight & Admin",
                symbolText = "AUTH",
                accentColor = Color(0xFF2E9B3C),
                onClick = { onNavigate(AppScreen.AUTHORITY_DASHBOARD) }
            )
        }
    }
}

@Composable
fun PrimaryPortalCard(
    title: String,
    description: String,
    badgeText: String,
    symbolText: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = symbolText,
                    color = accentColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Surface(
                    color = accentColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = accentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF071D36)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    fontSize = 13.sp,
                    color = Color(0xFF64748B),
                    lineHeight = 18.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "→",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF94A3B8)
            )
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