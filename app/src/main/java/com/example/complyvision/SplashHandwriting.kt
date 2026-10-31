package com.example.complyvision
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale as drawScale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.delay


@Composable
fun HandwrittenText(
    text: String,
    progress: Float,
    firstColor: Color,
    secondColor: Color,
    splitAt: Int,
    typeface: Typeface,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 64.sp
) {
    val density = LocalDensity.current
    val sizePx = with(density) { fontSize.toPx() }
    val padPx = with(density) { 8.dp.toPx() }
    val featherPx = with(density) { 10.dp.toPx() }

    // One outline path per letter, laid out along the baseline (cached).
    val layout = remember(text, sizePx, typeface) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            this.typeface = typeface
        }
        val bounds = RectF()
        val glyphs = text.mapIndexed { i, ch ->
            val p = android.graphics.Path()
            if (!ch.isWhitespace()) {
                paint.getTextPath(ch.toString(), 0, 1, paint.measureText(text, 0, i), 0f, p)
                val r = RectF()
                p.computeBounds(r, true)
                if (!r.isEmpty) bounds.union(r)
            }
            p.asComposePath()
        }
        glyphs to bounds
    }
    val (glyphs, bounds) = layout
    val naturalW = bounds.width() + padPx * 2
    val naturalH = bounds.height() + padPx * 2

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        val availablePx = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else naturalW
        val fit = minOf(1f, availablePx / naturalW)
        val widthDp = with(density) { (naturalW * fit).toDp() }
        val heightDp = with(density) { (naturalH * fit).toDp() }

        Canvas(
            modifier = Modifier
                .size(widthDp, heightDp)
                .semantics { contentDescription = text }
        ) {
            drawScale(fit, fit, pivot = Offset.Zero) {
                translate(left = -bounds.left + padPx, top = -bounds.top + padPx) {
                    val slant = bounds.height() * 0.28f
                    val span = bounds.width() + slant * 2 + featherPx * 2
                    val front = bounds.left - slant + progress * span
                    val top = bounds.top - padPx
                    val bottom = bounds.bottom + padPx
                    val far = bounds.left - padPx - slant * 2

                    // three stacked clips = soft leading edge
                    listOf(0f to 0.35f, 1f to 0.8f, 2f to 1f).forEach { (step, a) ->
                        val x = front - featherPx * step
                        val edge = Path().apply {
                            moveTo(far, top)
                            lineTo(x + slant, top)   // top leans forward, like italic strokes
                            lineTo(x - slant, bottom)
                            lineTo(far, bottom)
                            close()
                        }
                        clipPath(edge) {
                            glyphs.forEachIndexed { i, g ->
                                drawPath(g, color = if (i < splitAt) firstColor else secondColor, alpha = a)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Drop-in replacement for the old AnimatedSplashScreen in MainActivity.kt
 * (delete that one, otherwise the names clash).
 *
 * Needs app/src/main/res/font/sacramento_regular.ttf
 */
@Composable
fun AnimatedSplashScreen(onSplashFinished: () -> Unit) {
    val fullTagline = "Because Every Consumer Matters."
    val context = LocalContext.current
    val scriptFont = remember {
        // looked up by name so the app still compiles (with a cursive fallback) before the font file is added
        val fontId = context.resources.getIdentifier("sacramento_regular", "font", context.packageName)
        (if (fontId != 0) ResourcesCompat.getFont(context, fontId) else null)
            ?: Typeface.create("cursive", Typeface.ITALIC)
    }

    val handwriting = remember { Animatable(0f) }
    var typedTaglineChars by remember { mutableIntStateOf(0) }
    var showCursorOnTagline by remember { mutableStateOf(false) }
    var showDividerLine by remember { mutableStateOf(false) }
    var startLogoAnim by remember { mutableStateOf(false) }

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
    val dividerWidthFraction by animateFloatAsState(
        targetValue = if (showDividerLine) 1f else 0f,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "dividerWidth"
    )
    val infiniteTransition = rememberInfiniteTransition(label = "cursorBlink")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(animation = tween(350), repeatMode = RepeatMode.Reverse),
        label = "cursorAlpha"
    )

    LaunchedEffect(Unit) {
        // 1. Logo pops in
        startLogoAnim = true
        delay(650)

        // 2. "ComplyVision" is drawn fluidly by hand (Update this block)
        handwriting.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = 3500, // Duration badhaya taaki stroke drawing saaf dikhe
                easing = FastOutSlowInEasing // Easing change kiya for smoother ink flow
            )
        )
        delay(250)

        // 3. Tagline types out
        showCursorOnTagline = true
        for (i in 1..fullTagline.length) {
            typedTaglineChars = i
            delay(42)
        }
        delay(150)
        showCursorOnTagline = false

        // 4. Gradient underline
        showDividerLine = true
        delay(1100)

        // 5. Go to home
        onSplashFinished()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(id = R.drawable.splash_bg),
            contentDescription = "Splash Background",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 150.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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

            // "Comply" navy + "Vision" royal blue, written by hand, always fits the screen
            HandwrittenText(
                text = "ComplyVision",
                progress = handwriting.value,
                firstColor = Color(0xFF071D36),
                secondColor = Color(0xFF0052B4),
                splitAt = 6,
                typeface = scriptFont,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                fontSize = 72.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = buildAnnotatedString {
                    append(fullTagline.take(typedTaglineChars))
                    if (showCursorOnTagline) {
                        withStyle(
                            style = SpanStyle(
                                color = Color(0xFF0052B4).copy(alpha = cursorAlpha),
                                fontWeight = FontWeight.Bold
                            )
                        ) { append("|") }
                    }
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                fontStyle = FontStyle.Italic,
                color = Color(0xFF1E3A5F),
                textAlign = TextAlign.Center,
                modifier = Modifier.heightIn(min = 24.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

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
