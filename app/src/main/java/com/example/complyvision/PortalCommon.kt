package com.example.complyvision

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// Shared look for the business-side pages (Business Portal, Officer Login, dashboards)

internal val PortalPageTop = Color(0xFFEEF5FD)
internal val PortalNavy = Color(0xFF071D36)
internal val PortalBlue = Color(0xFF0A63F0)
internal val PortalMuted = Color(0xFF566A85)

internal fun portalPageBrush(): Brush = Brush.verticalGradient(
    colors = listOf(PortalPageTop, Color(0xFFEAF2FC), Color(0xFFF6F9FD))
)

/**
 * Hero used on the Business Portal and Officer Login pages: wave/warehouse art behind a back button,
 * brand logo and the page's own [content]. The hero grows with its content, so nothing is ever
 * clipped or covered by the section below it.
 */
@Composable
fun PortalHero(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = maxWidth * (1024f / 1536f))
        ) {
            Image(
                painter = painterResource(id = R.drawable.business_hero_bg),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.BottomEnd,
                modifier = Modifier.matchParentSize()
            )

            // Gentle wash on the left so titles stay crisp wherever they meet the artwork
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            0f to PortalPageTop.copy(alpha = 0.6f),
                            0.5f to PortalPageTop.copy(alpha = 0.3f),
                            0.8f to Color.Transparent
                        )
                    )
            )

            // Soft fade so the hero blends into the page background
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, PortalPageTop.copy(alpha = 0.9f))
                        )
                    )
            )

            Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                PortalTopBar(onBack = onBack, backEnabled = backEnabled)
                Spacer(modifier = Modifier.height(26.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.62f)
                        .padding(start = 22.dp),
                    content = content
                )
                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }
}

/** Round back button on the left, ComplyVision logo + wordmark centered */
@Composable
fun PortalTopBar(onBack: () -> Unit, backEnabled: Boolean = true) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp)
            .height(42.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(40.dp)
                .shadow(4.dp, CircleShape, ambientColor = Color(0x330052B4), spotColor = Color(0x330052B4))
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.85f))
                .border(1.dp, Color.White, CircleShape)
                .semantics { contentDescription = "Back" }
                .clickable(enabled = backEnabled) { onBack() },
            contentAlignment = Alignment.Center
        ) {
            ChevronIcon(
                color = PortalNavy.copy(alpha = if (backEnabled) 1f else 0.35f),
                pointsLeft = true,
                modifier = Modifier.size(18.dp)
            )
        }

        BrandMark(Modifier.align(Alignment.Center))
    }
}

/** Logo + "ComplyVision" wordmark with the green leaf accent */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // The logo JPEG has a white backdrop; multiply keeps only the artwork
        val logo = ImageBitmap.imageResource(id = R.drawable.complyvision_logo)
        Canvas(modifier = Modifier.size(44.dp)) {
            drawImage(
                image = logo,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.High,
                blendMode = BlendMode.Multiply
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        Box {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = PortalNavy, fontWeight = FontWeight.ExtraBold)) {
                        append("Comply")
                    }
                    withStyle(SpanStyle(color = Color(0xFF0052B4), fontWeight = FontWeight.ExtraBold)) {
                        append("Vision")
                    }
                },
                fontSize = 22.sp,
                letterSpacing = (-0.5).sp
            )
            // Green leaf accent above the second 'i' in Vision
            Canvas(
                modifier = Modifier
                    .offset(x = 113.dp, y = (-1).dp)
                    .size(9.dp)
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
    }
}

@Composable
fun ChevronIcon(color: Color, pointsLeft: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            if (pointsLeft) {
                moveTo(w * 0.7f, h * 0.1f)
                lineTo(w * 0.25f, h * 0.5f)
                lineTo(w * 0.7f, h * 0.9f)
            } else {
                moveTo(w * 0.3f, h * 0.1f)
                lineTo(w * 0.75f, h * 0.5f)
                lineTo(w * 0.3f, h * 0.9f)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = w * 0.17f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

enum class Glyph {
    ID_CARD, LOCK, PERSON, SHIELD_CHECK, REFRESH, LOGIN, EYE, EYE_OFF, WARNING, ORGANISATION, LOGOUT, CHECK
}

/** Line icons drawn on a Canvas (no icon-library dependency) */
@Composable
fun GlyphIcon(glyph: Glyph, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = w * 0.085f
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (glyph) {
            Glyph.ID_CARD -> {
                drawRoundRect(
                    color = tint, topLeft = Offset(w * 0.07f, h * 0.2f), size = Size(w * 0.86f, h * 0.6f),
                    cornerRadius = CornerRadius(w * 0.1f), style = stroke
                )
                drawCircle(tint, radius = w * 0.075f, center = Offset(w * 0.31f, h * 0.44f), style = stroke)
                val shoulders = Path().apply {
                    moveTo(w * 0.19f, h * 0.68f)
                    cubicTo(w * 0.2f, h * 0.55f, w * 0.42f, h * 0.55f, w * 0.43f, h * 0.68f)
                }
                drawPath(shoulders, tint, style = stroke)
                drawLine(tint, Offset(w * 0.56f, h * 0.42f), Offset(w * 0.8f, h * 0.42f), sw, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.56f, h * 0.58f), Offset(w * 0.8f, h * 0.58f), sw, StrokeCap.Round)
            }

            Glyph.LOCK -> {
                drawRoundRect(
                    color = tint, topLeft = Offset(w * 0.2f, h * 0.44f), size = Size(w * 0.6f, h * 0.44f),
                    cornerRadius = CornerRadius(w * 0.1f), style = stroke
                )
                val shackle = Path().apply {
                    moveTo(w * 0.32f, h * 0.44f)
                    lineTo(w * 0.32f, h * 0.32f)
                    arcTo(Rect(w * 0.32f, h * 0.12f, w * 0.68f, h * 0.52f), 180f, 180f, false)
                    lineTo(w * 0.68f, h * 0.44f)
                }
                drawPath(shackle, tint, style = stroke)
                drawCircle(tint, radius = w * 0.04f, center = Offset(w * 0.5f, h * 0.66f))
            }

            Glyph.PERSON -> {
                drawCircle(tint, radius = w * 0.17f, center = Offset(w * 0.5f, h * 0.32f), style = stroke)
                val shoulders = Path().apply {
                    moveTo(w * 0.18f, h * 0.88f)
                    cubicTo(w * 0.18f, h * 0.58f, w * 0.82f, h * 0.58f, w * 0.82f, h * 0.88f)
                }
                drawPath(shoulders, tint, style = stroke)
            }

            Glyph.SHIELD_CHECK -> {
                drawPath(shieldPath(w, h), tint, style = stroke)
                val check = Path().apply {
                    moveTo(w * 0.34f, h * 0.5f)
                    lineTo(w * 0.46f, h * 0.62f)
                    lineTo(w * 0.68f, h * 0.38f)
                }
                drawPath(check, tint, style = stroke)
            }

            Glyph.REFRESH -> {
                val r = w * 0.32f
                val c = Offset(w * 0.5f, h * 0.5f)
                drawArc(
                    color = tint, startAngle = 40f, sweepAngle = 280f, useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2), style = stroke
                )
                // Arrow head at the end of the arc, following the clockwise tangent
                val endRad = Math.toRadians(320.0)
                val tip = Offset(c.x + r * cos(endRad).toFloat(), c.y + r * sin(endRad).toFloat())
                val dirX = -sin(endRad).toFloat()
                val dirY = cos(endRad).toFloat()
                fun back(angle: Double): Offset {
                    val a = Math.toRadians(angle)
                    val rx = (dirX * cos(a) - dirY * sin(a)).toFloat()
                    val ry = (dirX * sin(a) + dirY * cos(a)).toFloat()
                    return Offset(tip.x - rx * w * 0.2f, tip.y - ry * w * 0.2f)
                }
                drawLine(tint, tip, back(35.0), sw, StrokeCap.Round)
                drawLine(tint, tip, back(-35.0), sw, StrokeCap.Round)
            }

            Glyph.LOGIN -> {
                val bracket = Path().apply {
                    moveTo(w * 0.52f, h * 0.18f)
                    lineTo(w * 0.8f, h * 0.18f)
                    lineTo(w * 0.8f, h * 0.82f)
                    lineTo(w * 0.52f, h * 0.82f)
                }
                drawPath(bracket, tint, style = stroke)
                drawLine(tint, Offset(w * 0.12f, h * 0.5f), Offset(w * 0.6f, h * 0.5f), sw, StrokeCap.Round)
                val head = Path().apply {
                    moveTo(w * 0.42f, h * 0.33f)
                    lineTo(w * 0.6f, h * 0.5f)
                    lineTo(w * 0.42f, h * 0.67f)
                }
                drawPath(head, tint, style = stroke)
            }

            Glyph.LOGOUT -> {
                val bracket = Path().apply {
                    moveTo(w * 0.48f, h * 0.18f)
                    lineTo(w * 0.2f, h * 0.18f)
                    lineTo(w * 0.2f, h * 0.82f)
                    lineTo(w * 0.48f, h * 0.82f)
                }
                drawPath(bracket, tint, style = stroke)
                drawLine(tint, Offset(w * 0.4f, h * 0.5f), Offset(w * 0.88f, h * 0.5f), sw, StrokeCap.Round)
                val head = Path().apply {
                    moveTo(w * 0.7f, h * 0.33f)
                    lineTo(w * 0.88f, h * 0.5f)
                    lineTo(w * 0.7f, h * 0.67f)
                }
                drawPath(head, tint, style = stroke)
            }

            Glyph.EYE, Glyph.EYE_OFF -> {
                val eye = Path().apply {
                    moveTo(w * 0.06f, h * 0.5f)
                    cubicTo(w * 0.24f, h * 0.2f, w * 0.76f, h * 0.2f, w * 0.94f, h * 0.5f)
                    cubicTo(w * 0.76f, h * 0.8f, w * 0.24f, h * 0.8f, w * 0.06f, h * 0.5f)
                }
                drawPath(eye, tint, style = stroke)
                drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                if (glyph == Glyph.EYE_OFF) {
                    drawLine(tint, Offset(w * 0.16f, h * 0.84f), Offset(w * 0.84f, h * 0.16f), sw, StrokeCap.Round)
                }
            }

            Glyph.WARNING -> {
                val tri = Path().apply {
                    moveTo(w * 0.5f, h * 0.12f)
                    lineTo(w * 0.92f, h * 0.84f)
                    lineTo(w * 0.08f, h * 0.84f)
                    close()
                }
                drawPath(tri, tint, style = stroke)
                drawLine(tint, Offset(w * 0.5f, h * 0.38f), Offset(w * 0.5f, h * 0.6f), sw, StrokeCap.Round)
                drawCircle(tint, radius = w * 0.04f, center = Offset(w * 0.5f, h * 0.73f))
            }

            Glyph.CHECK -> {
                val check = Path().apply {
                    moveTo(w * 0.2f, h * 0.52f)
                    lineTo(w * 0.42f, h * 0.74f)
                    lineTo(w * 0.8f, h * 0.28f)
                }
                drawPath(check, tint, style = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            Glyph.ORGANISATION -> {
                // Main tower
                drawRoundRect(
                    color = tint, topLeft = Offset(w * 0.18f, h * 0.12f), size = Size(w * 0.46f, h * 0.76f),
                    cornerRadius = CornerRadius(w * 0.05f), style = stroke
                )
                // Side wing
                val wing = Path().apply {
                    moveTo(w * 0.64f, h * 0.42f)
                    lineTo(w * 0.84f, h * 0.42f)
                    lineTo(w * 0.84f, h * 0.88f)
                    lineTo(w * 0.64f, h * 0.88f)
                }
                drawPath(wing, tint, style = stroke)
                for (row in 0..1) {
                    for (col in 0..1) {
                        drawRect(
                            color = tint,
                            topLeft = Offset(w * (0.27f + col * 0.17f), h * (0.24f + row * 0.17f)),
                            size = Size(w * 0.08f, h * 0.08f)
                        )
                    }
                }
                // Door
                drawRect(tint, Offset(w * 0.34f, h * 0.66f), Size(w * 0.12f, h * 0.22f))
            }
        }
    }
}

private fun shieldPath(w: Float, h: Float): Path = Path().apply {
    moveTo(w * 0.5f, h * 0.1f)
    lineTo(w * 0.85f, h * 0.24f)
    lineTo(w * 0.85f, h * 0.52f)
    cubicTo(w * 0.85f, h * 0.75f, w * 0.5f, h * 0.92f, w * 0.5f, h * 0.92f)
    cubicTo(w * 0.5f, h * 0.92f, w * 0.15f, h * 0.75f, w * 0.15f, h * 0.52f)
    lineTo(w * 0.15f, h * 0.24f)
    close()
}
