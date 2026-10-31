package com.example.complyvision

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ============================================================================
// Palette (matches the Supreme Authority reference design)
// ============================================================================

internal object AC {
    val Ink = Color(0xFF0B1F3A)
    val Muted = Color(0xFF5B6E8C)
    val Faint = Color(0xFF8A9AB5)
    val Line = Color(0xFFDCE7F7)

    val Blue = Color(0xFF1463F3)
    val BlueSoft = Color(0xFFE3EEFF)
    val Green = Color(0xFF16A34A)
    val GreenSoft = Color(0xFFDDF6E7)
    val Red = Color(0xFFE5484D)
    val RedSoft = Color(0xFFFDE6E6)
    val Amber = Color(0xFFF59E0B)
    val AmberSoft = Color(0xFFFFF0D2)
    val Violet = Color(0xFF6D4AE0)
    val VioletSoft = Color(0xFFECE6FF)
    val Teal = Color(0xFF0E9AA7)
    val TealSoft = Color(0xFFD9F4F6)
    val Orange = Color(0xFFEA7317)
    val OrangeSoft = Color(0xFFFFE9D6)
    val Slate = Color(0xFF64748B)
    val SlateSoft = Color(0xFFE8EDF4)

    val PageTop = Color(0xFFE6F0FF)
    val PageMid = Color(0xFFEFF5FF)
    val PageBottom = Color(0xFFF7FAFF)
    val Card = Color(0xE6FFFFFF)

    fun page(): Brush = Brush.verticalGradient(listOf(PageTop, PageMid, PageBottom))
}

internal fun statusStyle(status: String): Pair<Color, Color> = when (status) {
    CaseStatus.PENDING -> AC.Orange to AC.OrangeSoft
    CaseStatus.ASSIGNED -> AC.Blue to AC.BlueSoft
    CaseStatus.SCHEDULED -> AC.Violet to AC.VioletSoft
    CaseStatus.IN_PROGRESS -> AC.Teal to AC.TealSoft
    CaseStatus.UNDER_REVIEW -> AC.Amber to AC.AmberSoft
    CaseStatus.PASSED -> AC.Green to AC.GreenSoft
    CaseStatus.FAILED -> AC.Red to AC.RedSoft
    else -> AC.Slate to AC.SlateSoft
}

internal fun priorityStyle(priority: String): Pair<Color, Color> = when (priority) {
    "Critical" -> AC.Red to AC.RedSoft
    "High" -> AC.Orange to AC.OrangeSoft
    "Medium" -> AC.Blue to AC.BlueSoft
    else -> AC.Slate to AC.SlateSoft
}

internal fun inspectorStatusStyle(status: String): Pair<Color, Color> = when (status) {
    InspectorStatus.DETAINED -> AC.Amber to AC.AmberSoft
    InspectorStatus.SUSPENDED -> AC.Red to AC.RedSoft
    else -> AC.Green to AC.GreenSoft
}

internal fun inspectorStatusLabel(status: String): String = when (status) {
    InspectorStatus.DETAINED -> "On Hold"
    InspectorStatus.SUSPENDED -> "Suspended"
    else -> "Active"
}

internal fun rateColor(rate: Double): Color = when {
    rate >= 80 -> AC.Green
    rate >= 65 -> AC.Amber
    else -> AC.Red
}

// ============================================================================
// Formatting
// ============================================================================

private val Grouped: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)
private val FullDate = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)
private val ShortDate = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)
private val DateTimeFmt = DateTimeFormatter.ofPattern("dd MMM yyyy, h:mm a", Locale.ENGLISH)
private val ClockFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
private val MonthYear = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)

internal fun fmtInt(n: Int): String = Grouped.format(n)
internal fun fmtPct(v: Double): String = String.format(Locale.US, "%.1f%%", v)
internal fun fmtPctOf(part: Int, whole: Int): String = if (whole <= 0) "0.0%" else fmtPct(100.0 * part / whole)

internal fun parseDate(iso: String?): LocalDate? = try {
    if (iso.isNullOrBlank()) null else LocalDate.parse(iso.take(10))
} catch (_: Exception) {
    null
}

internal fun parseInstant(iso: String?): Instant? = try {
    if (iso.isNullOrBlank()) null else OffsetDateTime.parse(iso).toInstant()
} catch (_: Exception) {
    null
}

internal fun fmtDate(iso: String?): String = parseDate(iso)?.format(FullDate) ?: "—"
internal fun fmtDateShort(iso: String?): String = parseDate(iso)?.format(ShortDate) ?: "—"

internal fun fmtDateTime(iso: String?): String =
    parseInstant(iso)?.atZone(ZoneId.systemDefault())?.format(DateTimeFmt) ?: "—"

internal fun fmtClock(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("hh:mm:ss a", Locale.ENGLISH))

internal fun fmtMonthYear(iso: String?): String = parseDate(iso)?.format(MonthYear) ?: "—"

/** "10:30" -> "10:30 AM" */
internal fun fmtTime(hhmm: String?): String = try {
    if (hhmm.isNullOrBlank()) "" else java.time.LocalTime.parse(hhmm.take(5)).format(ClockFmt)
} catch (_: Exception) {
    hhmm.orEmpty()
}

internal fun fmtSchedule(date: String?, time: String?): String {
    if (parseDate(date) == null) return "Not scheduled"
    val t = fmtTime(time)
    return if (t.isBlank()) fmtDateShort(date) else "${fmtDateShort(date)}, $t"
}

internal fun relativeTime(iso: String?): String {
    val then = parseInstant(iso) ?: return "—"
    val mins = java.time.Duration.between(then, Instant.now()).toMinutes()
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 60 * 24 -> "${mins / 60}h ago"
        mins < 60 * 24 * 30 -> "${mins / (60 * 24)}d ago"
        else -> fmtDate(iso)
    }
}

internal fun isOverdue(due: String?, status: String): Boolean {
    if (status == CaseStatus.PASSED || status == CaseStatus.FAILED || status == CaseStatus.UNDER_REVIEW) return false
    val d = parseDate(due) ?: return false
    return d.isBefore(LocalDate.now())
}

internal fun initialsOf(name: String): String {
    val parts = name.split(" ").filter { it.isNotBlank() && !it.endsWith(".") }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> "${parts[0].first()}${parts[1].first()}".uppercase()
    }
}

internal fun periodLabel(days: Int): String = when (days) {
    7 -> "Last 7 Days"
    30 -> "Last 30 Days"
    90 -> "Last 90 Days"
    365 -> "Last 12 Months"
    else -> "All Time"
}

internal val PeriodOptions = listOf(7, 30, 90, 365, 0)

// ============================================================================
// Icons (drawn on a Canvas: no icon-library dependency)
// ============================================================================

internal enum class AIcon {
    HOME, CLIPBOARD, PEOPLE, CHART, MENU, BELL, USER, CHECK_CIRCLE, CROSS_CIRCLE, HOURGLASS, TREND_UP,
    ARROW_RIGHT, ARROW_UP, ARROW_DOWN, SORT, MORE, SEARCH, CALENDAR, CLOCK, DOWNLOAD, PIN, PHONE, MAIL, BOX,
    CLOSE, FLAG, BAN, PAUSE, FILTER, CHEVRON_DOWN, CHEVRON_LEFT, DOC, ID, REFRESH, LOGOUT, BUILDING, SHIELD, PLUS,
    FLASH, UPLOAD, CAMERA
}

@Composable
internal fun AIconView(icon: AIcon, tint: Color, modifier: Modifier = Modifier.size(20.dp)) {
    when (icon) {
        AIcon.REFRESH -> { GlyphIcon(Glyph.REFRESH, tint, modifier); return }
        AIcon.LOGOUT -> { GlyphIcon(Glyph.LOGOUT, tint, modifier); return }
        AIcon.BUILDING -> { GlyphIcon(Glyph.ORGANISATION, tint, modifier); return }
        AIcon.SHIELD -> { GlyphIcon(Glyph.SHIELD_CHECK, tint, modifier); return }
        AIcon.ID -> { GlyphIcon(Glyph.ID_CARD, tint, modifier); return }
        else -> Unit
    }
    Canvas(modifier) { drawAIcon(icon, tint) }
}

private fun DrawScope.drawAIcon(icon: AIcon, tint: Color) {
    val w = size.width
    val h = size.height
    val sw = w * 0.09f
    val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, Offset(w * x1, h * y1), Offset(w * x2, h * y2), sw, StrokeCap.Round)
    fun poly(vararg p: Float, close: Boolean = false) {
        val path = Path().apply {
            moveTo(w * p[0], h * p[1])
            var i = 2
            while (i < p.size) { lineTo(w * p[i], h * p[i + 1]); i += 2 }
            if (close) close()
        }
        drawPath(path, tint, style = stroke)
    }
    fun rrect(x: Float, y: Float, rw: Float, rh: Float, r: Float = 0.1f) = drawRoundRect(
        tint, Offset(w * x, h * y), Size(w * rw, h * rh), CornerRadius(w * r), style = stroke
    )
    fun ring(cx: Float, cy: Float, r: Float) = drawCircle(tint, w * r, Offset(w * cx, h * cy), style = stroke)

    when (icon) {
        AIcon.HOME -> {
            poly(0.1f, 0.5f, 0.5f, 0.14f, 0.9f, 0.5f)
            poly(0.22f, 0.42f, 0.22f, 0.86f, 0.78f, 0.86f, 0.78f, 0.42f)
            poly(0.42f, 0.86f, 0.42f, 0.6f, 0.58f, 0.6f, 0.58f, 0.86f)
        }
        AIcon.CLIPBOARD -> {
            rrect(0.2f, 0.16f, 0.6f, 0.74f)
            rrect(0.36f, 0.07f, 0.28f, 0.18f, 0.05f)
            line(0.34f, 0.45f, 0.66f, 0.45f); line(0.34f, 0.6f, 0.66f, 0.6f); line(0.34f, 0.75f, 0.54f, 0.75f)
        }
        AIcon.PEOPLE -> {
            ring(0.36f, 0.3f, 0.14f)
            drawPath(Path().apply {
                moveTo(w * 0.08f, h * 0.84f)
                cubicTo(w * 0.08f, h * 0.52f, w * 0.64f, h * 0.52f, w * 0.64f, h * 0.84f)
            }, tint, style = stroke)
            ring(0.72f, 0.36f, 0.1f)
            drawPath(Path().apply {
                moveTo(w * 0.7f, h * 0.58f)
                cubicTo(w * 0.86f, h * 0.55f, w * 0.94f, h * 0.68f, w * 0.93f, h * 0.82f)
            }, tint, style = stroke)
        }
        AIcon.CHART -> {
            rrect(0.16f, 0.52f, 0.16f, 0.36f, 0.03f)
            rrect(0.42f, 0.3f, 0.16f, 0.58f, 0.03f)
            rrect(0.68f, 0.12f, 0.16f, 0.76f, 0.03f)
        }
        AIcon.MENU -> { line(0.16f, 0.28f, 0.84f, 0.28f); line(0.16f, 0.5f, 0.84f, 0.5f); line(0.16f, 0.72f, 0.62f, 0.72f) }
        AIcon.BELL -> {
            val path = Path().apply {
                moveTo(w * 0.2f, h * 0.72f)
                lineTo(w * 0.8f, h * 0.72f)
                lineTo(w * 0.72f, h * 0.55f)
                lineTo(w * 0.72f, h * 0.42f)
                cubicTo(w * 0.72f, h * 0.2f, w * 0.28f, h * 0.2f, w * 0.28f, h * 0.42f)
                lineTo(w * 0.28f, h * 0.55f)
                close()
            }
            drawPath(path, tint, style = stroke)
            line(0.42f, 0.84f, 0.58f, 0.84f)
        }
        AIcon.USER -> {
            ring(0.5f, 0.32f, 0.17f)
            drawPath(Path().apply {
                moveTo(w * 0.16f, h * 0.9f)
                cubicTo(w * 0.16f, h * 0.56f, w * 0.84f, h * 0.56f, w * 0.84f, h * 0.9f)
            }, tint, style = stroke)
        }
        AIcon.CHECK_CIRCLE -> { ring(0.5f, 0.5f, 0.4f); poly(0.3f, 0.52f, 0.45f, 0.67f, 0.72f, 0.36f) }
        AIcon.CROSS_CIRCLE -> { ring(0.5f, 0.5f, 0.4f); line(0.36f, 0.36f, 0.64f, 0.64f); line(0.64f, 0.36f, 0.36f, 0.64f) }
        AIcon.HOURGLASS -> poly(0.28f, 0.12f, 0.72f, 0.12f, 0.72f, 0.28f, 0.5f, 0.5f, 0.72f, 0.72f, 0.72f, 0.88f, 0.28f, 0.88f, 0.28f, 0.72f, 0.5f, 0.5f, 0.28f, 0.28f, close = true)
        AIcon.TREND_UP -> { poly(0.1f, 0.74f, 0.38f, 0.46f, 0.55f, 0.62f, 0.9f, 0.24f); poly(0.68f, 0.24f, 0.9f, 0.24f, 0.9f, 0.46f) }
        AIcon.ARROW_RIGHT -> { line(0.14f, 0.5f, 0.86f, 0.5f); poly(0.6f, 0.24f, 0.86f, 0.5f, 0.6f, 0.76f) }
        AIcon.ARROW_UP -> { line(0.5f, 0.86f, 0.5f, 0.16f); poly(0.24f, 0.42f, 0.5f, 0.16f, 0.76f, 0.42f) }
        AIcon.ARROW_DOWN -> { line(0.5f, 0.14f, 0.5f, 0.84f); poly(0.24f, 0.58f, 0.5f, 0.84f, 0.76f, 0.58f) }
        AIcon.SORT -> {
            line(0.3f, 0.84f, 0.3f, 0.16f); poly(0.12f, 0.34f, 0.3f, 0.16f, 0.48f, 0.34f)
            line(0.7f, 0.16f, 0.7f, 0.84f); poly(0.52f, 0.66f, 0.7f, 0.84f, 0.88f, 0.66f)
        }
        AIcon.MORE -> { for (cx in listOf(0.2f, 0.5f, 0.8f)) drawCircle(tint, w * 0.07f, Offset(w * cx, h * 0.5f)) }
        AIcon.SEARCH -> { ring(0.44f, 0.44f, 0.28f); line(0.66f, 0.66f, 0.88f, 0.88f) }
        AIcon.CALENDAR -> {
            rrect(0.12f, 0.2f, 0.76f, 0.68f); line(0.12f, 0.42f, 0.88f, 0.42f)
            line(0.32f, 0.1f, 0.32f, 0.28f); line(0.68f, 0.1f, 0.68f, 0.28f)
        }
        AIcon.CLOCK -> { ring(0.5f, 0.5f, 0.4f); poly(0.5f, 0.26f, 0.5f, 0.5f, 0.68f, 0.62f) }
        AIcon.DOWNLOAD -> {
            line(0.5f, 0.12f, 0.5f, 0.62f); poly(0.28f, 0.42f, 0.5f, 0.64f, 0.72f, 0.42f)
            poly(0.14f, 0.62f, 0.14f, 0.86f, 0.86f, 0.86f, 0.86f, 0.62f)
        }
        AIcon.PIN -> {
            ring(0.5f, 0.38f, 0.2f)
            poly(0.34f, 0.52f, 0.5f, 0.9f, 0.66f, 0.52f)
        }
        AIcon.PHONE -> { rrect(0.28f, 0.08f, 0.44f, 0.84f, 0.1f); line(0.43f, 0.78f, 0.57f, 0.78f) }
        AIcon.MAIL -> { rrect(0.08f, 0.2f, 0.84f, 0.6f); poly(0.1f, 0.26f, 0.5f, 0.56f, 0.9f, 0.26f) }
        AIcon.BOX -> {
            poly(0.5f, 0.08f, 0.88f, 0.28f, 0.88f, 0.72f, 0.5f, 0.92f, 0.12f, 0.72f, 0.12f, 0.28f, close = true)
            poly(0.12f, 0.28f, 0.5f, 0.5f, 0.88f, 0.28f); line(0.5f, 0.5f, 0.5f, 0.92f)
        }
        AIcon.CLOSE -> { line(0.22f, 0.22f, 0.78f, 0.78f); line(0.78f, 0.22f, 0.22f, 0.78f) }
        AIcon.FLAG -> {
            line(0.24f, 0.1f, 0.24f, 0.92f)
            poly(0.24f, 0.16f, 0.82f, 0.16f, 0.68f, 0.36f, 0.82f, 0.56f, 0.24f, 0.56f)
        }
        AIcon.BAN -> { ring(0.5f, 0.5f, 0.4f); line(0.22f, 0.22f, 0.78f, 0.78f) }
        AIcon.PAUSE -> { ring(0.5f, 0.5f, 0.4f); line(0.4f, 0.34f, 0.4f, 0.66f); line(0.6f, 0.34f, 0.6f, 0.66f) }
        AIcon.FILTER -> poly(0.1f, 0.16f, 0.9f, 0.16f, 0.6f, 0.52f, 0.6f, 0.84f, 0.4f, 0.74f, 0.4f, 0.52f, close = true)
        AIcon.CHEVRON_DOWN -> poly(0.2f, 0.36f, 0.5f, 0.66f, 0.8f, 0.36f)
        AIcon.CHEVRON_LEFT -> poly(0.66f, 0.18f, 0.32f, 0.5f, 0.66f, 0.82f)
        AIcon.DOC -> {
            poly(0.22f, 0.08f, 0.62f, 0.08f, 0.82f, 0.28f, 0.82f, 0.92f, 0.22f, 0.92f, close = true)
            poly(0.62f, 0.08f, 0.62f, 0.28f, 0.82f, 0.28f)
            line(0.34f, 0.5f, 0.7f, 0.5f); line(0.34f, 0.66f, 0.7f, 0.66f); line(0.34f, 0.8f, 0.52f, 0.8f)
        }
        AIcon.PLUS -> { line(0.5f, 0.2f, 0.5f, 0.8f); line(0.2f, 0.5f, 0.8f, 0.5f) }
        AIcon.FLASH -> poly(0.58f, 0.08f, 0.24f, 0.54f, 0.48f, 0.54f, 0.4f, 0.92f, 0.76f, 0.42f, 0.52f, 0.42f, close = true)
        AIcon.UPLOAD -> {
            line(0.5f, 0.64f, 0.5f, 0.14f); poly(0.28f, 0.36f, 0.5f, 0.14f, 0.72f, 0.36f)
            poly(0.14f, 0.62f, 0.14f, 0.86f, 0.86f, 0.86f, 0.86f, 0.62f)
        }
        AIcon.CAMERA -> {
            rrect(0.1f, 0.28f, 0.8f, 0.56f)
            poly(0.34f, 0.28f, 0.4f, 0.16f, 0.6f, 0.16f, 0.66f, 0.28f)
            ring(0.5f, 0.56f, 0.15f)
        }
        else -> Unit
    }
}

// ============================================================================
// Small building blocks
// ============================================================================

@Composable
internal fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp = 22.dp,
    padding: Dp = 14.dp,
    tint: Color = AC.Card,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(corner)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(6.dp, shape, ambientColor = Color(0x331E5FB0), spotColor = Color(0x331E5FB0))
            .clip(shape)
            .background(tint)
            .border(1.dp, Color.White.copy(alpha = 0.9f), shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button) { onClick() } else Modifier)
            .padding(padding),
        content = content
    )
}

@Composable
internal fun Pill(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier, size: Int = 11) {
    Text(
        text = text,
        color = fg,
        fontSize = size.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
internal fun StatusPill(status: String, modifier: Modifier = Modifier) {
    val (fg, bg) = statusStyle(status)
    Pill(status, fg, bg, modifier)
}

@Composable
internal fun PriorityPill(priority: String, modifier: Modifier = Modifier) {
    val (fg, bg) = priorityStyle(priority)
    Pill(priority, fg, bg, modifier, size = 10)
}

@Composable
internal fun Avatar(name: String, size: Dp, fg: Color = AC.Blue, bg: Color = AC.BlueSoft, fontSize: Int = 12) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Text(initialsOf(name), color = fg, fontSize = fontSize.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
internal fun RoundIconButton(
    icon: AIcon,
    description: String,
    onClick: () -> Unit,
    size: Dp = 42.dp,
    tint: Color = AC.Ink,
    container: Color = Color.White,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(15.dp)
) {
    Box(
        modifier = Modifier
            .size(size)
            .shadow(5.dp, shape, ambientColor = Color(0x331E5FB0), spotColor = Color(0x331E5FB0))
            .clip(shape)
            .background(container)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        AIconView(icon, tint, Modifier.size(size * 0.5f))
    }
}

@Composable
internal fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: AIcon? = null,
    colors: List<Color> = listOf(Color(0xFF1463F3), Color(0xFF3B8BFF)),
    height: Dp = 46.dp
) {
    val active = enabled && !loading
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(if (active) Brush.horizontalGradient(colors) else SolidColor(Color(0xFFBFD0EA)))
            .clickable(enabled = active, role = Role.Button) { onClick() }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (loading) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            } else if (icon != null) {
                AIconView(icon, Color.White, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
internal fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fg: Color = AC.Blue,
    bg: Color = AC.BlueSoft,
    icon: AIcon? = null,
    enabled: Boolean = true,
    height: Dp = 38.dp
) {
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) bg else AC.SlateSoft)
            .clickable(enabled = enabled, role = Role.Button) { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) {
                AIconView(icon, if (enabled) fg else AC.Faint, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, color = if (enabled) fg else AC.Faint, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
internal fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit, count: Int? = null) {
    val bg by animateColorAsState(if (selected) AC.Blue else Color.White, label = "pillBg")
    val fg = if (selected) Color.White else AC.Muted
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, if (selected) AC.Blue else AC.Line, RoundedCornerShape(50))
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = fmtInt(count),
                color = if (selected) AC.Blue else AC.Muted,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White else AC.SlateSoft)
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
internal fun SearchBox(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(46.dp)
            .shadow(4.dp, RoundedCornerShape(23.dp), ambientColor = Color(0x261E5FB0), spotColor = Color(0x261E5FB0))
            .clip(RoundedCornerShape(23.dp))
            .background(Color.White)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AIconView(AIcon.SEARCH, AC.Faint, Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(hint, color = AC.Faint, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = value,
                onValueChange = { onChange(it.take(60)) },
                singleLine = true,
                textStyle = TextStyle(color = AC.Ink, fontSize = 14.sp),
                cursorBrush = SolidColor(AC.Blue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button
                    ) { onChange("") }
                    .semantics { contentDescription = "Clear search" },
                contentAlignment = Alignment.Center
            ) { AIconView(AIcon.CLOSE, AC.Muted, Modifier.size(12.dp)) }
        }
    }
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = AC.Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
internal fun LabelValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = AC.Ink) {
    Column(modifier = modifier) {
        Text(label, color = AC.Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(value.ifBlank { "—" }, color = valueColor, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
    }
}

@Composable
internal fun HorizontalRule(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(AC.Line))
}

@Composable
internal fun LoadingPanel(message: String = "Loading…", modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 56.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = AC.Blue, strokeWidth = 3.dp)
            Spacer(Modifier.height(12.dp))
            Text(message, color = AC.Muted, fontSize = 14.sp)
        }
    }
}

@Composable
internal fun ErrorPanel(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier.fillMaxWidth(), tint = Color(0xFFFFF1F1)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            GlyphIcon(Glyph.WARNING, AC.Red, Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text(message, color = Color(0xFF9B2226), fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Try again", onRetry, height = 40.dp)
        }
    }
}

@Composable
internal fun EmptyPanel(title: String, message: String, icon: AIcon = AIcon.CLIPBOARD, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(AC.BlueSoft),
            contentAlignment = Alignment.Center
        ) { AIconView(icon, AC.Blue, Modifier.size(30.dp)) }
        Spacer(Modifier.height(14.dp))
        Text(title, color = AC.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(message, color = AC.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
    }
}

@Composable
internal fun ProgressTrack(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFE6EEF8))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

internal fun Rect.inflateBy(d: Float): Rect = Rect(left - d, top - d, right + d, bottom + d)
