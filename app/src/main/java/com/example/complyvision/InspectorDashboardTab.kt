package com.example.complyvision

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun InspectorDashboardTab(
    store: InspectorStore,
    exporter: ReportExporter,
    onOpenCase: (String) -> Unit,
    onViewAllHistory: () -> Unit,
    onOpenInspections: (InspectionFilter) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        InspectorHero(store.session)

        when (val state = store.overview) {
            Load.Loading -> LoadingPanel("Loading your dashboard…")
            is Load.Failed -> ErrorPanel(state.message, { store.loadOverview() }, Modifier.padding(horizontal = 16.dp))
            is Load.Ready -> {
                val d = state.data
                Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    IdentityCard(d.profile, store.session)
                    if (d.profile.status != InspectorStatus.ACTIVE) StatusNotice(d.profile)
                    InspectorStats(d)

                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth >= 372.dp) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                                InspectionOverviewCard(d, Modifier.weight(1.55f).fillMaxHeight(), stretch = true)
                                ComplianceTrendCard(d, store, Modifier.weight(1f).fillMaxHeight(), stretch = true)
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                InspectionOverviewCard(d, Modifier.fillMaxWidth(), stretch = false)
                                ComplianceTrendCard(d, store, Modifier.fillMaxWidth(), stretch = false)
                            }
                        }
                    }

                    OverallComplianceBanner(d)
                    AssignmentsCard(d, onOpenCase, onOpenInspections)
                    InspectionHistoryCard(d.history, exporter, onOpenCase, onViewAllHistory)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Hero with a drawn illustration (scale, boxes, checklist, leaves)
// ---------------------------------------------------------------------------

@Composable
private fun InspectorHero(session: OfficerSession) {
    Box(Modifier.fillMaxWidth().height(164.dp)) {
        Canvas(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.4f).padding(end = 6.dp, top = 8.dp, bottom = 8.dp)) { drawHeroArt() }
        Column(
            modifier = Modifier.fillMaxHeight().fillMaxWidth(0.6f).padding(start = 20.dp, top = 4.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Welcome,", color = AC.Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Text(
                session.designation, color = AC.Blue, fontSize = 26.sp, lineHeight = 30.sp,
                fontWeight = FontWeight.ExtraBold, maxLines = 2
            )
            Spacer(Modifier.height(4.dp))
            Text("Legal Metrology Compliance Dashboard", color = AC.Muted, fontSize = 13.sp, lineHeight = 17.sp)
        }
    }
}

private fun DrawScope.drawHeroArt() {
    val w = size.width
    val h = size.height
    val leaf = Color(0xFF34C26B)
    val leafDark = Color(0xFF1E9E52)

    fun leafAt(cx: Float, cy: Float, len: Float, deg: Float, c: Color) = rotate(deg, Offset(cx, cy)) {
        val p = Path().apply {
            moveTo(cx, cy)
            quadraticBezierTo(cx + len * 0.5f, cy - len * 0.38f, cx + len, cy)
            quadraticBezierTo(cx + len * 0.5f, cy + len * 0.38f, cx, cy)
            close()
        }
        drawPath(p, c)
        drawLine(Color.White.copy(alpha = 0.5f), Offset(cx + len * 0.1f, cy), Offset(cx + len * 0.85f, cy), 1.5f)
    }

    // Soft backdrop
    drawCircle(Brush.radialGradient(listOf(Color(0x332F7BFF), Color.Transparent), Offset(w * 0.55f, h * 0.55f), w * 0.55f),
        w * 0.55f, Offset(w * 0.55f, h * 0.55f))

    // Balance scale
    val sx = w * 0.22f
    val blue = Color(0xFF1463F3)
    drawLine(blue, Offset(sx, h * 0.2f), Offset(sx, h * 0.82f), w * 0.022f, StrokeCap.Round)
    drawRoundRect(blue, Offset(sx - w * 0.09f, h * 0.8f), Size(w * 0.18f, h * 0.05f), CornerRadius(6f))
    drawLine(blue, Offset(sx - w * 0.17f, h * 0.27f), Offset(sx + w * 0.17f, h * 0.27f), w * 0.016f, StrokeCap.Round)
    drawCircle(blue, w * 0.022f, Offset(sx, h * 0.2f))
    for (dir in listOf(-1f, 1f)) {
        val px = sx + dir * w * 0.17f
        drawLine(blue.copy(alpha = 0.7f), Offset(px, h * 0.27f), Offset(px - w * 0.06f, h * 0.5f), 2f)
        drawLine(blue.copy(alpha = 0.7f), Offset(px, h * 0.27f), Offset(px + w * 0.06f, h * 0.5f), 2f)
        drawArc(blue, 0f, 180f, true, Offset(px - w * 0.075f, h * 0.44f), Size(w * 0.15f, h * 0.13f))
    }

    // Cardboard boxes
    val box = Color(0xFFE2B47A)
    val boxDark = Color(0xFFC8935A)
    fun carton(x: Float, y: Float, bw: Float, bh: Float) {
        drawRoundRect(box, Offset(x, y), Size(bw, bh), CornerRadius(4f))
        drawRect(boxDark, Offset(x, y), Size(bw, bh * 0.14f))
        drawRect(Color(0xFFF4D9B0), Offset(x + bw * 0.42f, y), Size(bw * 0.16f, bh * 0.38f))
        drawRoundRect(Color(0xFF7A5A36), Offset(x + bw * 0.12f, y + bh * 0.62f), Size(bw * 0.26f, bh * 0.18f), CornerRadius(2f), style = Stroke(1.5f))
    }
    carton(w * 0.42f, h * 0.5f, w * 0.26f, h * 0.36f)
    carton(w * 0.47f, h * 0.2f, w * 0.2f, h * 0.3f)

    // Clipboard with checks
    val cx = w * 0.7f
    drawRoundRect(Color(0xFF2F6FE0), Offset(cx, h * 0.18f), Size(w * 0.24f, h * 0.7f), CornerRadius(8f))
    drawRoundRect(Color.White, Offset(cx + w * 0.02f, h * 0.24f), Size(w * 0.2f, h * 0.6f), CornerRadius(5f))
    drawRoundRect(Color(0xFF8AA4C8), Offset(cx + w * 0.075f, h * 0.14f), Size(w * 0.09f, h * 0.08f), CornerRadius(4f))
    for (i in 0 until 3) {
        val ry = h * (0.37f + i * 0.15f)
        val check = Path().apply {
            moveTo(cx + w * 0.04f, ry)
            lineTo(cx + w * 0.06f, ry + h * 0.035f)
            lineTo(cx + w * 0.095f, ry - h * 0.035f)
        }
        drawPath(check, Color(0xFF16A34A), style = Stroke(3.2f, cap = StrokeCap.Round))
        drawLine(Color(0xFFB7C7DE), Offset(cx + w * 0.115f, ry), Offset(cx + w * 0.2f, ry), 3f, StrokeCap.Round)
    }

    // Leaves
    leafAt(w * 0.08f, h * 0.86f, w * 0.16f, -150f, leafDark)
    leafAt(w * 0.1f, h * 0.88f, w * 0.13f, -195f, leaf)
    leafAt(w * 0.93f, h * 0.2f, w * 0.12f, -60f, leaf)
    leafAt(w * 0.95f, h * 0.86f, w * 0.13f, -30f, leafDark)
}

// ---------------------------------------------------------------------------
// Identity card
// ---------------------------------------------------------------------------

@Composable
private fun IdentityCard(profile: MyProfile, session: OfficerSession) {
    GlassCard(padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(AC.BlueSoft), contentAlignment = Alignment.Center) {
                AIconView(AIcon.USER, AC.Blue, Modifier.size(22.dp))
            }
            Spacer(Modifier.width(10.dp))
            IdentityCell("Inspector ID", profile.officerId.ifBlank { session.officerId }, Modifier.weight(0.9f))
            VerticalDivider()
            IdentityCell("Name", profile.name.ifBlank { session.name }, Modifier.weight(1.2f))
            VerticalDivider()
            Row(Modifier.weight(1.3f), verticalAlignment = Alignment.CenterVertically) {
                AIconView(AIcon.CALENDAR, AC.Blue, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                IdentityCell("Tenure", tenureLabel(profile.employeeSince), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun IdentityCell(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 6.dp)) {
        Text(label, color = AC.Faint, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(value, color = AC.Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, lineHeight = 16.sp)
    }
}

@Composable
private fun VerticalDivider() {
    Box(Modifier.fillMaxHeight().padding(vertical = 4.dp).width(1.dp).background(AC.Line))
}

@Composable
private fun StatusNotice(profile: MyProfile) {
    val (fg, bg) = inspectorStatusStyle(profile.status)
    GlassCard(tint = bg, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AIconView(if (profile.status == InspectorStatus.SUSPENDED) AIcon.BAN else AIcon.PAUSE, fg, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Account ${inspectorStatusLabel(profile.status).lowercase(Locale.ENGLISH)}", color = fg, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                if (profile.statusReason.isNotBlank()) Text(profile.statusReason, color = AC.Ink, fontSize = 12.sp, lineHeight = 16.sp)
                Text("No new inspections will be assigned until the Supreme Authority reinstates you.", color = AC.Muted, fontSize = 11.5.sp, lineHeight = 15.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Headline numbers (package level, lifetime)
// ---------------------------------------------------------------------------

@Composable
private fun InspectorStats(d: InspectorOverview) {
    val t = d.lifetime
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigStat(Modifier.weight(1f), AIcon.CLIPBOARD, AC.Blue, AC.BlueSoft, fmtInt(t.total), "Total Inspections", "Lifetime",
            AC.Blue, Color(0xFFD5E5FF), listOf(Color(0xFFEAF3FF), Color(0xFFD9E9FF)))
        BigStat(Modifier.weight(1f), AIcon.CHECK_CIRCLE, Color.White, AC.Green, fmtInt(t.passed), "Passed Cases", fmtPctOf(t.passed, t.total),
            AC.Green, Color(0xFFCDF1DB), listOf(Color(0xFFEAF9F0), Color(0xFFD6F3E2)))
        BigStat(Modifier.weight(1f), AIcon.CROSS_CIRCLE, Color.White, AC.Red, fmtInt(t.failed), "Failed Cases", fmtPctOf(t.failed, t.total),
            AC.Red, Color(0xFFFAD3D3), listOf(Color(0xFFFEEEEE), Color(0xFFFBDADA)))
        BigStat(Modifier.weight(1f), AIcon.HOURGLASS, Color.White, AC.Amber, fmtInt(t.review), "Under Review", fmtPctOf(t.review, t.total),
            Color(0xFFB7791F), Color(0xFFFFE3B3), listOf(Color(0xFFFFF6E5), Color(0xFFFFEACB)))
    }
    Text(
        "Package-level results across ${fmtInt(d.lifetimeCases)} inspection${if (d.lifetimeCases == 1) "" else "s"} • ${fmtInt(d.lifetimeChecks)} rule checks evaluated",
        color = AC.Faint, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun BigStat(
    modifier: Modifier, icon: AIcon, iconTint: Color, iconBg: Color, value: String, label: String, pill: String,
    pillFg: Color, pillBg: Color, gradient: List<Color>
) {
    Column(
        modifier = modifier.fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(Brush.verticalGradient(gradient))
            .border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(18.dp))
            .padding(horizontal = 9.dp, vertical = 11.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label $value" }
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) {
            AIconView(icon, iconTint, Modifier.size(16.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(value, color = AC.Ink, fontSize = if (value.length > 5) 16.sp else 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(label, color = AC.Muted, fontSize = 10.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(6.dp))
        Text(
            pill, color = pillFg, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(pillBg).padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Overview donut + compliance trend
// ---------------------------------------------------------------------------

@Composable
private fun InspectionOverviewCard(d: InspectorOverview, modifier: Modifier, stretch: Boolean) {
    val t = d.lifetime
    GlassCard(modifier = modifier, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AIconView(AIcon.CHART, AC.Blue, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Inspection Overview", color = AC.Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(12.dp))
        // Next to the taller trend card, centre the chart in the shared height
        if (stretch) Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DonutChart(
                values = listOf(t.passed.toFloat(), t.failed.toFloat(), t.review.toFloat()),
                colors = listOf(AC.Green, AC.Red, AC.Amber),
                modifier = Modifier.size(96.dp),
                thickness = 14.dp
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(fmtInt(t.total), color = AC.Ink, fontSize = if (t.total >= 10_000) 14.sp else 17.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Inspections", color = AC.Muted, fontSize = 9.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Legend("Passed", t.passed, t.total, AC.Green)
                Legend("Failed", t.failed, t.total, AC.Red)
                Legend("Under Review", t.review, t.total, AC.Amber)
            }
        }
        if (stretch) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Legend(label: String, count: Int, total: Int, color: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, color = AC.Ink, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Text("${fmtInt(count)} (${fmtPctOf(count, total)})", color = AC.Muted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(start = 14.dp))
    }
}

@Composable
private fun ComplianceTrendCard(d: InspectorOverview, store: InspectorStore, modifier: Modifier, stretch: Boolean) {
    GlassCard(modifier = modifier, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AIconView(AIcon.TREND_UP, AC.Blue, Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("Compliance Trend", color = AC.Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 17.sp, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        PeriodChip(store.days) { store.changeDays(it) }
        Spacer(Modifier.height(8.dp))
        val delta = d.delta
        if (delta == null) {
            Text(fmtPct(d.rate), color = rateColor(d.rate), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (d.days == 0) "Compliance since you started" else if (d.period.total == 0) "No packages analysed in this period" else "No earlier period to compare",
                color = AC.Muted, fontSize = 11.5.sp, lineHeight = 15.sp
            )
        } else {
            val up = delta >= 0
            val tone = if (up) AC.Green else AC.Red
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (up) "+" else "") + String.format(Locale.US, "%.1f%%", delta), color = tone, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                AIconView(if (up) AIcon.ARROW_UP else AIcon.ARROW_DOWN, tone, Modifier.size(17.dp))
            }
            Text("vs. previous ${if (d.days == 365) "12 months" else "${d.days} days"}", color = AC.Muted, fontSize = 11.5.sp, lineHeight = 15.sp)
            Text("${fmtPct(d.rate)} now • ${fmtPct(d.previousRate ?: 0.0)} before", color = AC.Faint, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Sparkline(
            values = d.series.map { if (it.total > 0) it.rate else null },
            color = if ((delta ?: 0.0) >= 0) AC.Green else AC.Red,
            modifier = if (stretch) Modifier.fillMaxWidth().weight(1f, fill = true).heightIn(min = 54.dp) else Modifier.fillMaxWidth().height(72.dp)
        )
    }
}

@Composable
internal fun PeriodChip(days: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).border(1.dp, AC.Line, RoundedCornerShape(50))
                .clickable(role = Role.Button) { open = true }.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(periodLabel(days), color = AC.Muted, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(2.dp))
            AIconView(AIcon.CHEVRON_DOWN, AC.Muted, Modifier.size(12.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PeriodOptions.forEach { option ->
                DropdownMenuItem(
                    text = { Text(periodLabel(option), fontWeight = if (option == days) FontWeight.Bold else FontWeight.Normal, color = if (option == days) AC.Blue else AC.Ink) },
                    onClick = { open = false; onPick(option) }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Overall compliance
// ---------------------------------------------------------------------------

@Composable
private fun OverallComplianceBanner(d: InspectorOverview) {
    val rate = d.lifetime.rate
    GlassCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(AC.BlueSoft), contentAlignment = Alignment.Center) {
                AIconView(AIcon.CHECK_CIRCLE, AC.Blue, Modifier.size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Overall Compliance", color = AC.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Text("Across all inspections (Lifetime)", color = AC.Muted, fontSize = 12.sp)
            }
            Text(
                fmtPct(rate), color = rateColor(rate), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(rateColor(rate).copy(alpha = 0.14f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        ProgressTrack(fraction = (rate / 100).toFloat(), color = rateColor(rate))
    }
}

// ---------------------------------------------------------------------------
// Assignments (what to do next)
// ---------------------------------------------------------------------------

@Composable
private fun AssignmentsCard(d: InspectorOverview, onOpenCase: (String) -> Unit, onOpenInspections: (InspectionFilter) -> Unit) {
    val p = d.pipeline
    GlassCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AIconView(AIcon.FLAG, AC.Orange, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("My Assignments", color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text(
                "Open all", color = AC.Blue, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { onOpenInspections(InspectionFilter.PENDING) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniCount("Pending", p.assigned + p.scheduled, AC.Violet, Modifier.weight(1f)) { onOpenInspections(InspectionFilter.PENDING) }
            MiniCount("In progress", p.inProgress, AC.Teal, Modifier.weight(1f)) { onOpenInspections(InspectionFilter.ACTIVE) }
            MiniCount("With authority", p.review, AC.Amber, Modifier.weight(1f)) { onOpenInspections(InspectionFilter.REVIEW) }
            MiniCount("Overdue", p.overdue, AC.Red, Modifier.weight(1f)) { onOpenInspections(InspectionFilter.PENDING) }
        }
        d.next?.let { n ->
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AC.BlueSoft.copy(alpha = 0.6f))
                    .clickable(role = Role.Button) { onOpenCase(n.code) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(if (n.status == CaseStatus.IN_PROGRESS) "Continue inspection" else "Up next", color = AC.Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(n.product, color = AC.Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${n.establishment}, ${n.city}", color = AC.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${n.code} • ${fmtSchedule(n.scheduledDate, n.scheduledTime)}", color = AC.Faint, fontSize = 11.sp)
                }
                Spacer(Modifier.width(8.dp))
                StatusPill(n.status)
                Spacer(Modifier.width(4.dp))
                AIconView(AIcon.ARROW_RIGHT, AC.Blue, Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun MiniCount(label: String, value: Int, tone: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(14.dp)).background(tone.copy(alpha = 0.09f)).clickable(role = Role.Button) { onClick() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(fmtInt(value), color = tone, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = AC.Muted, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------
// Inspection history (latest packages analysed, per inspection)
// ---------------------------------------------------------------------------

private val HistoryFilters = listOf("All Packages", CaseStatus.PASSED, CaseStatus.FAILED, CaseStatus.UNDER_REVIEW, CaseStatus.IN_PROGRESS)

@Composable
private fun InspectionHistoryCard(rows: List<HistoryRow>, exporter: ReportExporter, onOpenCase: (String) -> Unit, onViewAll: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf(HistoryFilters.first()) }
    var open by remember { mutableStateOf(false) }
    val shown = rows.filter { filter == HistoryFilters.first() || it.status == filter }.take(4)

    GlassCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AIconView(AIcon.CLOCK, AC.Blue, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Inspection History", color = AC.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Box {
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).border(1.dp, AC.Line, RoundedCornerShape(50))
                        .clickable(role = Role.Button) { open = true }.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Filter: ", color = AC.Muted, fontSize = 11.sp)
                    Text(filter, color = AC.Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.width(2.dp))
                    AIconView(AIcon.CHEVRON_DOWN, AC.Muted, Modifier.size(12.dp))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    HistoryFilters.forEach { f ->
                        DropdownMenuItem(
                            text = { Text(f, fontWeight = if (f == filter) FontWeight.Bold else FontWeight.Normal, color = if (f == filter) AC.Blue else AC.Ink) },
                            onClick = { open = false; filter = f }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (shown.isEmpty()) {
            Text(
                if (rows.isEmpty()) "No packages analysed yet. Scan a package from an assigned inspection to see it here."
                else "No inspections with status \"$filter\" among your recent work.",
                color = AC.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEach { row -> HistoryPackageRow(row, exporter, onOpenCase) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(50)).background(Color.White)
                .border(1.dp, AC.Line, RoundedCornerShape(50)).clickable(role = Role.Button) { onViewAll() }
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("View All History", color = AC.Blue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            AIconView(AIcon.ARROW_RIGHT, AC.Blue, Modifier.size(16.dp))
        }
    }
}

@Composable
internal fun ProductTile(category: String, size: Dp = 46.dp) {
    val tones = listOf(
        AC.Green to AC.GreenSoft, AC.Amber to AC.AmberSoft, AC.Blue to AC.BlueSoft,
        AC.Orange to AC.OrangeSoft, AC.Violet to AC.VioletSoft, AC.Teal to AC.TealSoft
    )
    val (fg, bg) = tones[(category.lowercase(Locale.ENGLISH).hashCode() and 0x7fffffff) % tones.size]
    Box(Modifier.size(size).clip(RoundedCornerShape(12.dp)).background(bg), contentAlignment = Alignment.Center) {
        AIconView(AIcon.BOX, fg, Modifier.size(size * 0.5f))
    }
}

@Composable
private fun HistoryPackageRow(row: HistoryRow, exporter: ReportExporter, onOpenCase: (String) -> Unit) {
    val t = row.totals
    val busy = exporter.busy == "pdf:${row.code}"
    BoxWithConstraints(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, AC.Line, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button) { onOpenCase(row.code) }.padding(10.dp)
    ) {
        val wide = maxWidth >= 380.dp
        @Composable
        fun Title(modifier: Modifier) {
            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                ProductTile(row.category)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.product, color = AC.Ink, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, lineHeight = 16.sp, overflow = TextOverflow.Ellipsis)
                    Text(row.code, color = AC.Muted, fontSize = 11.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AIconView(AIcon.CALENDAR, AC.Faint, Modifier.size(11.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(fmtDate(row.lastScanAt), color = AC.Muted, fontSize = 11.sp)
                    }
                }
            }
        }

        @Composable
        fun Counts(modifier: Modifier) {
            Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                CountCell(AIcon.CHECK_CIRCLE, "Passed", t.passed, AC.Green, Modifier.weight(1f))
                CountCell(AIcon.CROSS_CIRCLE, "Failed", t.failed, AC.Red, Modifier.weight(1f))
                CountCell(AIcon.HOURGLASS, "Review", t.review, AC.Amber, Modifier.weight(1f))
                Column(Modifier.weight(1.1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Compliance", color = AC.Muted, fontSize = 10.sp)
                    Text(
                        fmtPct(t.rate), color = rateColor(t.rate), fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(rateColor(t.rate).copy(alpha = 0.13f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                DownloadButton(busy) { exporter.casePdf(row.code, row.product) }
            }
        }

        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Title(Modifier.weight(1f))
                Counts(Modifier.weight(1.3f))
            }
        } else {
            Column {
                Title(Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Counts(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CountCell(icon: AIcon, label: String, value: Int, tone: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AIconView(icon, tone, Modifier.size(11.dp))
            Spacer(Modifier.width(3.dp))
            Text(label, color = AC.Muted, fontSize = 10.sp, maxLines = 1)
        }
        Text(fmtInt(value), color = tone, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
internal fun DownloadButton(busy: Boolean, description: String = "Download PDF report", onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(AC.BlueSoft)
            .semantics { contentDescription = description }
            .clickable(enabled = !busy, role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (busy) CircularProgressIndicator(color = AC.Blue, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        else AIconView(AIcon.DOWNLOAD, AC.Blue, Modifier.size(18.dp))
    }
}
