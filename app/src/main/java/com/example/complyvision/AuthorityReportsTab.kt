package com.example.complyvision

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun AuthorityReportsTab(store: AuthorityStore, bottomSpace: Dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var generating by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ReportData?>(null) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri: Uri? ->
        val data = pending
        pending = null
        if (uri == null || data == null) {
            generating = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { ReportPdf.write(data, it) }
                        ?: throw IllegalStateException("Could not open the file for writing")
                }
                store.showToast("Report saved as PDF", actionLabel = "Open") {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, "application/pdf")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: ActivityNotFoundException) {
                        store.showToast("No PDF viewer is installed on this device", isError = true)
                    }
                }
            } catch (e: Exception) {
                store.showToast("Could not save the report. Please try again.", isError = true)
            } finally {
                generating = false
            }
        }
    }

    fun download() {
        if (generating) return
        generating = true
        scope.launch {
            try {
                // Always pull a fresh copy so the PDF reflects the situation right now
                pending = store.freshReport()
                saver.launch("ComplyVision_Report_${LocalDate.now()}_${if (store.reportDays == 0) "all" else "${store.reportDays}d"}.pdf")
            } catch (e: SessionExpiredException) {
                generating = false
            } catch (e: ApiException) {
                generating = false
                store.showToast(e.userMessage(), isError = true)
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reports", color = AC.Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp, modifier = Modifier.weight(1f))
                LiveBadge(store.syncedAt)
            }
            Text("Live statistics for everything under your authority. Download the full report any time.", color = AC.Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PeriodOptions.forEach { d ->
                FilterPill(
                    when (d) { 7 -> "7 days"; 30 -> "30 days"; 90 -> "90 days"; 365 -> "12 months"; else -> "All time" },
                    store.reportDays == d, { store.changeReportDays(d) }
                )
            }
        }

        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DownloadCard(generating, store.report is Load.Ready, ::download)

            when (val state = store.report) {
                Load.Loading -> LoadingPanel("Crunching the numbers…")
                is Load.Failed -> ErrorPanel(state.message, { store.loadReport() })
                is Load.Ready -> ReportBody(state.data)
            }
        }
        Spacer(Modifier.height(bottomSpace + 12.dp))
    }
}

@Composable
private fun LiveBadge(syncedAt: Long) {
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "livePulse"
    )
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(AC.GreenSoft).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).alpha(pulse).clip(CircleShape).background(AC.Green))
        Spacer(Modifier.width(6.dp))
        Text(
            if (syncedAt > 0) "LIVE • ${fmtClock(syncedAt)}" else "LIVE",
            color = AC.Green, fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1
        )
    }
}

@Composable
private fun DownloadCard(generating: Boolean, ready: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF0B2E59), Color(0xFF1463F3))))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            AIconView(AIcon.DOC, Color.White, Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Full compliance report", color = Color.White, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold)
            Text("PDF with every chart and table, built from live data the moment you tap.", color = Color.White.copy(alpha = 0.78f), fontSize = 11.5.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (ready && !generating) Color.White else Color.White.copy(alpha = 0.6f))
                .clickableIf(ready && !generating, onClick)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center
        ) {
            if (generating) {
                androidx.compose.material3.CircularProgressIndicator(color = AC.Blue, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AIconView(AIcon.DOWNLOAD, AC.Blue, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("PDF", color = AC.Blue, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

private fun Modifier.clickableIf(enabled: Boolean, onClick: () -> Unit): Modifier =
    if (enabled) this.clickable(role = Role.Button, onClick = onClick) else this

// ---------------------------------------------------------------------------
// Report body
// ---------------------------------------------------------------------------

@Composable
private fun ReportBody(r: ReportData) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KpiGrid(r)
        OutcomeCard(r)
        WeeklyCard(r)
        PipelineCard(r)
        if (r.categories.isNotEmpty()) RateListCard("By product category", AIcon.BOX, r.categories)
        if (r.states.isNotEmpty()) RateListCard("By state", AIcon.PIN, r.states)
        if (r.violationTypes.isNotEmpty()) ViolationsCard(r.violationTypes)
        if (r.inspectors.isNotEmpty()) LeaderboardCard(r.inspectors)
        WorkforceCard(r)
        Text(
            "Report period: ${periodLabel(r.days)} • generated ${fmtDateTime(r.generatedAt)}",
            color = AC.Faint, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
    }
}

@Composable
private fun KpiGrid(r: ReportData) {
    val tiles = listOf(
        Triple("Total inspections", fmtInt(r.total), AC.Blue to AIcon.CLIPBOARD),
        Triple("Compliance rate", if (r.passed + r.failed == 0) "—" else fmtPct(100.0 * r.passed / (r.passed + r.failed)), AC.Green to AIcon.CHECK_CIRCLE),
        Triple("Failed", "${fmtInt(r.failed)} • ${fmtPct(r.failRate)}", AC.Red to AIcon.CROSS_CIRCLE),
        Triple("Violations found", fmtInt(r.violations), AC.Orange to AIcon.FLAG),
        Triple("Avg. turnaround", r.avgTurnaround?.let { String.format(java.util.Locale.US, "%.1f days", it) } ?: "—", AC.Violet to AIcon.CLOCK),
        Triple("Overdue cases", fmtInt(r.overdue), (if (r.overdue > 0) AC.Red else AC.Teal) to AIcon.HOURGLASS)
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                pair.forEach { (label, value, style) ->
                    GlassCard(modifier = Modifier.weight(1f).fillMaxHeight(), padding = 14.dp, corner = 20.dp) {
                        Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(style.first.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                            AIconView(style.second, style.first, Modifier.size(17.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(value, color = AC.Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(label, color = AC.Muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardHeading(text: String, icon: AIcon, trailing: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(AC.BlueSoft), contentAlignment = Alignment.Center) {
            AIconView(icon, AC.Blue, Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = AC.Faint, fontSize = 11.5.sp)
    }
}

@Composable
private fun OutcomeCard(r: ReportData) {
    GlassCard {
        CardHeading("Outcome split", AIcon.CHART, "${fmtInt(r.total)} inspections")
        val total = (r.passed + r.failed + r.review).coerceAtLeast(1)
        Row(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(50)).background(Color(0xFFE6EEF8))) {
            listOf(r.passed to AC.Green, r.failed to AC.Red, r.review to AC.Amber).forEach { (count, color) ->
                if (count > 0) Box(Modifier.weight(count.toFloat() / total).fillMaxHeight().background(color))
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            OutcomeLegend("Passed", r.passed, r.total, AC.Green, Modifier.weight(1f))
            OutcomeLegend("Failed", r.failed, r.total, AC.Red, Modifier.weight(1f))
            OutcomeLegend("Review", r.review, r.total, AC.Amber, Modifier.weight(1f))
        }
    }
}

@Composable
private fun OutcomeLegend(label: String, count: Int, total: Int, color: Color, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, color = AC.Muted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(fmtInt(count), color = AC.Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text(fmtPctOf(count, total), color = color, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WeeklyCard(r: ReportData) {
    GlassCard {
        CardHeading("Weekly results", AIcon.TREND_UP)
        if (r.weekly.all { it.passed + it.failed + it.review == 0 }) {
            Text("No inspection results in these weeks.", color = AC.Muted, fontSize = 13.sp)
        } else {
            StackedBars(r.weekly, Modifier.fillMaxWidth().height(140.dp))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                r.weekly.forEach {
                    Text(it.label.substringBefore(' ').let { d -> d } + "\n" + it.label.substringAfter(' ', ""), color = AC.Muted, fontSize = 9.sp, lineHeight = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendDot("Passed", AC.Green); LegendDot("Failed", AC.Red); LegendDot("Under review", AC.Amber)
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, color = AC.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun PipelineCard(r: ReportData) {
    GlassCard {
        CardHeading("Live case pipeline", AIcon.CLIPBOARD, "right now")
        val rows = listOf(
            Triple("Awaiting assignment", r.pending, AC.Orange),
            Triple("Assigned", r.assigned, AC.Blue),
            Triple("Scheduled", r.scheduled, AC.Violet),
            Triple("In progress", r.inProgress, AC.Teal),
            Triple("Awaiting review", r.pipelineReview, AC.Amber)
        )
        val max = rows.maxOf { it.second }.coerceAtLeast(1)
        rows.forEach { (label, count, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                Text(label, color = AC.Ink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(138.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f)) { ProgressTrack(count.toFloat() / max, color, height = 10.dp) }
                Text(fmtInt(count), color = AC.Ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
            }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.overdue > 0) Pill("${r.overdue} overdue", AC.Red, AC.RedSoft, size = 10)
            if (r.pendingCritical > 0) Pill("${r.pendingCritical} critical", AC.Red, AC.RedSoft, size = 10)
            if (r.pendingHigh > 0) Pill("${r.pendingHigh} high", AC.Orange, AC.OrangeSoft, size = 10)
            if (r.overdue == 0 && r.pendingCritical == 0 && r.pendingHigh == 0) Pill("Nothing urgent", AC.Green, AC.GreenSoft, size = 10)
        }
    }
}

@Composable
private fun RateListCard(title: String, icon: AIcon, rows: List<NamedStat>) {
    GlassCard {
        CardHeading(title, icon, "pass rate")
        rows.forEachIndexed { i, row ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, color = AC.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${fmtInt(row.total)} inspections", color = AC.Faint, fontSize = 11.sp)
                Spacer(Modifier.width(10.dp))
                Text(fmtPct(row.passRate), color = rateColor(row.passRate), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(5.dp))
            ProgressTrack((row.passRate / 100).toFloat(), rateColor(row.passRate), height = 7.dp)
        }
    }
}

@Composable
private fun ViolationsCard(rows: List<CountStat>) {
    GlassCard {
        CardHeading("Most common violations", AIcon.FLAG)
        val max = rows.maxOf { it.count }.coerceAtLeast(1)
        rows.forEachIndexed { i, v ->
            if (i > 0) Spacer(Modifier.height(11.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(v.name, color = AC.Ink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(fmtInt(v.count), color = AC.Red, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(5.dp))
            ProgressTrack(v.count.toFloat() / max, AC.Red, height = 7.dp)
        }
    }
}

@Composable
private fun LeaderboardCard(rows: List<PerformanceRow>) {
    GlassCard {
        CardHeading("Inspector leaderboard", AIcon.PEOPLE, "by pass rate")
        val ranked = rows.filter { it.total >= 3 }.sortedByDescending { it.passRate }.take(8)
        if (ranked.isEmpty()) {
            Text("Not enough inspections in this period to rank inspectors.", color = AC.Muted, fontSize = 13.sp)
        }
        ranked.forEachIndexed { i, row ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${i + 1}", color = if (i < 3) Color.White else AC.Muted, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.size(24.dp).clip(CircleShape).background(if (i < 3) AC.Blue else AC.SlateSoft).padding(top = 4.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row {
                        Text(row.name, color = AC.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(fmtPct(row.passRate), color = rateColor(row.passRate), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    ProgressTrack((row.passRate / 100).toFloat(), rateColor(row.passRate), height = 6.dp)
                    Text("${row.officerId} • ${fmtInt(row.total)} inspections", color = AC.Faint, fontSize = 10.5.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}

@Composable
private fun WorkforceCard(r: ReportData) {
    GlassCard {
        CardHeading("Workforce", AIcon.USER, "${r.workforceTotal} inspectors")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkTile("Active", r.workforceActive, AC.Green, AC.GreenSoft, Modifier.weight(1f))
            WorkTile("On hold", r.workforceDetained, AC.Amber, AC.AmberSoft, Modifier.weight(1f))
            WorkTile("Suspended", r.workforceSuspended, AC.Red, AC.RedSoft, Modifier.weight(1f))
        }
    }
}

@Composable
private fun WorkTile(label: String, value: Int, fg: Color, bg: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(fmtInt(value), color = fg, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = fg.copy(alpha = 0.85f), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}
