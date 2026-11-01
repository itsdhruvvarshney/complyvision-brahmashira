package com.example.complyvision

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun InspectorCasePage(
    store: InspectorStore,
    exporter: ReportExporter,
    code: String,
    refreshKey: Int,
    bottomSpace: Dp,
    onBack: () -> Unit,
    onScan: (MyCase) -> Unit,
    onOpenScan: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var state by remember(code) { mutableStateOf<Load<CaseFull>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var submitOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(code, reload, refreshKey) {
        if (state !is Load.Ready) state = Load.Loading
        state = try {
            Load.Ready(store.case(code))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val m = store.handle(e) ?: return@LaunchedEffect
            if (state is Load.Ready) state else Load.Failed(m)
        }
    }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(code, (state as? Load.Ready)?.data?.case?.status, onBack)
        when (val s = state) {
            Load.Loading -> LoadingPanel("Loading inspection…")
            is Load.Failed -> ErrorPanel(s.message, { reload++ }, Modifier.padding(16.dp))
            is Load.Ready -> {
                val full = s.data
                val c = full.case
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp).padding(top = 4.dp, bottom = bottomSpace + 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ProductCard(c)
                    AssignmentDetails(c)
                    ActionCard(
                        c = c,
                        busy = busy,
                        onStart = {
                            busy = true
                            scope.launch {
                                when (val r = store.mutate { InspectorRepository.start(store.token, c.code) }) {
                                    is ActionResult.Done -> { store.showToast("Inspection started"); reload++ }
                                    is ActionResult.Rejected -> store.showToast(r.message, isError = true)
                                }
                                busy = false
                            }
                        },
                        onScan = { onScan(c) },
                        onSubmit = { submitOpen = true }
                    )
                    if (c.scans.total > 0) {
                        ResultsCard(c, exporter)
                        RuleResultsCard(full.ruleStats)
                        PackagesCard(full.scans, onOpenScan)
                    }
                    TimelineCard(full.events)
                }

                if (submitOpen) {
                    SubmitFindingsDialog(
                        c = c,
                        onDismiss = { submitOpen = false },
                        onSubmit = { remarks ->
                            val r = store.mutate { InspectorRepository.submitFindings(store.token, c.code, remarks) }
                            if (r is ActionResult.Done) {
                                submitOpen = false
                                store.showToast("Findings sent to ${c.assignedBy?.name ?: "the Supreme Authority"} for review")
                                reload++
                            }
                            r
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun DetailHeader(title: String, status: String?, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RoundIconButton(AIcon.CHEVRON_LEFT, "Back", onBack, size = 42.dp)
        Spacer(Modifier.width(12.dp))
        Text(title, color = AC.Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (status != null) StatusPill(status)
        trailing?.let { Spacer(Modifier.width(8.dp)); it() }
    }
}

@Composable
private fun ProductCard(c: MyCase) {
    GlassCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProductTile(c.category, 54.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.product, color = AC.Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 22.sp)
                Text(listOf(c.brand, c.category).filter { it.isNotBlank() }.joinToString(" • "), color = AC.Muted, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (c.priority.isNotBlank()) PriorityPill(c.priority)
            Pill(c.type, AC.Blue, AC.BlueSoft, size = 10)
            Pill(c.source, AC.Slate, AC.SlateSoft, size = 10)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            LabelValue("MRP on record", c.mrp?.let { "₹" + String.format(Locale.US, "%.2f", it) } ?: "—", Modifier.weight(1f))
            LabelValue("Batch", c.batchNo, Modifier.weight(1f))
        }
        if (c.manufacturer.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            LabelValue("Manufacturer", c.manufacturer)
        }
    }
}

@Composable
private fun AssignmentDetails(c: MyCase) {
    GlassCard(padding = 16.dp) {
        Text("Assignment", color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(10.dp))
        c.assignedBy?.let { by ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(by.name, 44.dp, fg = AC.Violet, bg = AC.VioletSoft, fontSize = 14)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Assigned by", color = AC.Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(by.name, color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    Text(listOf(by.designation, by.officerId).filter { it.isNotBlank() }.joinToString(" • "), color = AC.Violet, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    if (by.office.isNotBlank()) Text(by.office, color = AC.Muted, fontSize = 11.5.sp, lineHeight = 15.sp)
                    Text("on ${fmtDateTime(by.at)} (${relativeTime(by.at)})", color = AC.Muted, fontSize = 11.5.sp)
                }
            }
            if (by.note.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "“${by.note}”", color = AC.Ink, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AC.VioletSoft.copy(alpha = 0.5f)).padding(10.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            HorizontalRule()
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            LabelValue("Scheduled", fmtSchedule(c.scheduledDate, c.scheduledTime), Modifier.weight(1f))
            val overdue = isOverdue(c.dueDate, c.status)
            LabelValue("Due by", fmtDate(c.dueDate) + if (overdue) " (overdue)" else "", Modifier.weight(1f), valueColor = if (overdue) AC.Red else AC.Ink)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            LabelValue("Case filed", fmtDateTime(c.submittedAt), Modifier.weight(1f))
            LabelValue("Started", fmtDateTime(c.startedAt), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        LabelValue("Establishment", listOf(c.establishment, c.address, c.city, c.state).filter { it.isNotBlank() }.joinToString(", ") +
            if (c.establishmentType.isNotBlank()) " (${c.establishmentType})" else "")
        if (c.complaint.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            LabelValue("Complaint / instruction", c.complaint)
        }
        if (c.remarks.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            LabelValue("Remarks", c.remarks)
        }
    }
}

@Composable
private fun ActionCard(c: MyCase, busy: Boolean, onStart: () -> Unit, onScan: () -> Unit, onSubmit: () -> Unit) {
    when (c.status) {
        CaseStatus.ASSIGNED, CaseStatus.SCHEDULED -> GlassCard(padding = 16.dp, tint = Color(0xF2F2F7FF)) {
            val ready = canStartToday(c)
            Text(if (ready) "Ready to inspect" else "Scheduled for ${fmtDate(c.scheduledDate)}", color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (ready) "Start the inspection on site, then photograph each sample package. The first scan starts the inspection automatically."
                else "This inspection unlocks on its scheduled day. Contact the Supreme Authority to reschedule.",
                color = AC.Muted, fontSize = 12.5.sp, lineHeight = 17.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("Start inspection", onStart, Modifier.weight(1f), enabled = ready && !busy, height = 44.dp)
                PrimaryButton("Scan first package", onScan, Modifier.weight(1.2f), enabled = ready, loading = busy, icon = AIcon.PLUS, height = 44.dp)
            }
        }

        CaseStatus.IN_PROGRESS -> GlassCard(padding = 16.dp, tint = Color(0xF2EFFAFB)) {
            Text("Inspection in progress", color = AC.Teal, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (c.scans.total == 0) "Photograph or upload each sample package. Every image is validated, corrected, read by OCR and checked against the Legal Metrology rules."
                else "${fmtInt(c.scans.total)} package${if (c.scans.total == 1) "" else "s"} analysed. Keep scanning samples, then submit your findings to the Supreme Authority.",
                color = AC.Muted, fontSize = 12.5.sp, lineHeight = 17.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Scan package", onScan, Modifier.weight(1.2f), icon = AIcon.PLUS, height = 44.dp)
                SoftButton("Submit findings", onSubmit, Modifier.weight(1f), enabled = c.scans.total > 0, fg = AC.Green, bg = AC.GreenSoft, height = 44.dp)
            }
        }

        CaseStatus.UNDER_REVIEW -> InfoCard(AIcon.HOURGLASS, AC.Amber, "Awaiting the authority's decision",
            "Submitted ${fmtDateTime(c.reviewSubmittedAt)}. ${c.assignedBy?.name ?: "The Supreme Authority"} will mark it Passed, Failed or send it back for re-inspection.")

        CaseStatus.PASSED -> InfoCard(AIcon.CHECK_CIRCLE, AC.Green, "Inspection passed", "Decided ${fmtDateTime(c.completedAt)}.")
        CaseStatus.FAILED -> InfoCard(AIcon.CROSS_CIRCLE, AC.Red, "Inspection failed - ${c.violations} non-compliant package${if (c.violations == 1) "" else "s"}",
            "Decided ${fmtDateTime(c.completedAt)}." + if (c.violationTypes.isNotEmpty()) " Violations: ${c.violationTypes.joinToString(", ")}." else "")
    }
}

@Composable
private fun InfoCard(icon: AIcon, tone: Color, title: String, body: String) {
    GlassCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(tone.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                AIconView(icon, tone, Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Text(body, color = AC.Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun ResultsCard(c: MyCase, exporter: ReportExporter) {
    val t = c.scans
    GlassCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Package results", color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text(fmtPct(t.rate), color = rateColor(t.rate), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ResultTile("Analysed", t.total, AC.Blue, Modifier.weight(1f))
            ResultTile("Passed", t.passed, AC.Green, Modifier.weight(1f))
            ResultTile("Failed", t.failed, AC.Red, Modifier.weight(1f))
            ResultTile("Review", t.review, AC.Amber, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        OutcomeBar(t, 10.dp)
        Spacer(Modifier.height(6.dp))
        Text("First scan ${fmtDateTime(c.firstScanAt)} • last ${relativeTime(c.lastScanAt)}", color = AC.Faint, fontSize = 11.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("PDF report", { exporter.casePdf(c.code, c.product) }, Modifier.weight(1f),
                loading = exporter.busy == "pdf:${c.code}", icon = AIcon.DOWNLOAD, height = 42.dp)
            SoftButton("JSON data", { exporter.caseJson(c.code, c.product) }, Modifier.weight(1f),
                enabled = exporter.busy == null, icon = AIcon.DOC, height = 42.dp)
        }
    }
}

@Composable
private fun ResultTile(label: String, value: Int, tone: Color, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(tone.copy(alpha = 0.09f)).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(fmtInt(value), color = tone, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = AC.Muted, fontSize = 10.5.sp)
    }
}

@Composable
private fun RuleResultsCard(stats: List<RuleStat>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val sorted = stats.sortedWith(compareByDescending<RuleStat> { it.failed }.thenByDescending { it.review })
    val shown = if (expanded) sorted else sorted.take(6)
    GlassCard(padding = 16.dp) {
        Text("Test case results by rule", color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        Text("Every rule the category-aware engine applied to this case's packages", color = AC.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        shown.forEach { r ->
            val applicable = r.passed + r.failed + r.review
            Column(Modifier.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.id, color = AC.Blue, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(44.dp))
                    Text(r.title, color = AC.Ink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2, lineHeight = 15.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (applicable == 0) "N/A" else "${r.passed}/$applicable",
                        color = if (r.failed > 0) AC.Red else if (r.review > 0) AC.Amber else AC.Green,
                        fontSize = 12.sp, fontWeight = FontWeight.ExtraBold
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.padding(start = 44.dp)) {
                    OutcomeBar(PackageTotals(applicable, r.passed, r.failed, r.review), 6.dp)
                }
                if (r.failed > 0 || r.review > 0) {
                    Text(
                        listOfNotNull(if (r.failed > 0) "${r.failed} failed" else null, if (r.review > 0) "${r.review} review" else null).joinToString(" • ") +
                            if (r.reference.isNotBlank()) " • ${r.reference}" else "",
                        color = AC.Muted, fontSize = 10.5.sp, modifier = Modifier.padding(start = 44.dp, top = 2.dp)
                    )
                }
            }
        }
        if (sorted.size > 6) {
            Text(
                if (expanded) "Show fewer" else "Show all ${sorted.size} rules", color = AC.Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button) { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun PackagesCard(scans: List<ScanSummary>, onOpenScan: (String) -> Unit) {
    var verdict by rememberSaveable { mutableStateOf("All") }
    var limit by rememberSaveable { mutableIntStateOf(15) }
    val filtered = scans.filter { verdict == "All" || it.verdict == verdict }
    GlassCard(padding = 16.dp) {
        Text("Packages analysed", color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("All", CaseStatus.FAILED, CaseStatus.UNDER_REVIEW, CaseStatus.PASSED).forEach { v ->
                FilterPill(if (v == CaseStatus.UNDER_REVIEW) "Review" else v, verdict == v, { verdict = v; limit = 15 },
                    count = scans.count { v == "All" || it.verdict == v })
            }
        }
        Spacer(Modifier.height(8.dp))
        filtered.take(limit).forEach { s -> ScanRow(s) { onOpenScan(s.scanCode) } }
        if (filtered.size > limit) {
            SoftButton("Show ${minOf(25, filtered.size - limit)} more", { limit += 25 }, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        if (filtered.isEmpty()) Text("No packages with this result.", color = AC.Muted, fontSize = 12.5.sp, modifier = Modifier.padding(vertical = 10.dp))
    }
}

@Composable
internal fun ScanRow(s: ScanSummary, onClick: () -> Unit) {
    val tone = statusStyle(s.verdict).first
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(Color.White)
            .border(1.dp, AC.Line, RoundedCornerShape(14.dp)).clickable(role = Role.Button) { onClick() }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(tone.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
            AIconView(when (s.verdict) { CaseStatus.PASSED -> AIcon.CHECK_CIRCLE; CaseStatus.FAILED -> AIcon.CROSS_CIRCLE; else -> AIcon.HOURGLASS }, tone, Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.scanCode, color = AC.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(
                listOfNotNull(fmtDateTime(s.createdAt), s.category.takeIf { it.isNotBlank() }, s.source,
                    s.quality.takeIf { it.isNotBlank() }?.let { "$it image" }, s.resolvedAt?.let { "reviewed" }).joinToString(" • "),
                color = AC.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (s.flags.isNotEmpty()) {
                Text(
                    s.flags.joinToString(" • ") { it.title.ifBlank { it.id } }, color = tone, fontSize = 11.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 14.sp
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(fmtPct(s.score), color = rateColor(s.score), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
            Text("${s.passed}/${s.passed + s.failed + s.review} rules", color = AC.Faint, fontSize = 10.sp)
        }
    }
}

// ---------------------------------------------------------------------------
// Timeline
// ---------------------------------------------------------------------------

private fun eventTone(type: String): Color = when (type) {
    "Submitted", "Routed" -> AC.Slate
    "Assigned", "Reassigned" -> AC.Violet
    "Scheduled", "Rescheduled" -> AC.Blue
    "Started" -> AC.Teal
    "Scan" -> AC.Blue
    "Review" -> AC.Amber
    "Returned" -> AC.Orange
    "Result" -> AC.Green
    else -> AC.Slate
}

@Composable
internal fun TimelineCard(events: List<CaseEvent>) {
    // Consecutive per-package scan events collapse into one entry
    val grouped = mutableListOf<Pair<CaseEvent, Int>>()
    events.forEach { e ->
        val last = grouped.lastOrNull()
        if (e.type == "Scan" && last != null && last.first.type == "Scan") grouped[grouped.lastIndex] = e to (last.second + 1)
        else grouped += e to 1
    }
    GlassCard(padding = 16.dp) {
        Text("Track record", color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(10.dp))
        grouped.forEachIndexed { i, (e, n) ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(18.dp)) {
                    val tone = if (e.type == "Result" && e.title.contains("failed", ignoreCase = true)) AC.Red else eventTone(e.type)
                    Box(Modifier.padding(top = 3.dp).size(12.dp).clip(CircleShape).background(tone))
                    if (i < grouped.lastIndex) Box(Modifier.width(2.dp).weight(1f).background(AC.Line))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).padding(bottom = 14.dp)) {
                    Text(if (n > 1) "${e.title} ×$n" else e.title, color = AC.Ink, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                    if (e.detail.isNotBlank()) Text(e.detail, color = AC.Muted, fontSize = 12.sp, lineHeight = 16.sp)
                    Text(
                        listOf(fmtDateTime(e.at), listOf(e.actor, e.role).filter { it.isNotBlank() }.joinToString(", ")).filter { it.isNotBlank() }.joinToString(" • "),
                        color = AC.Faint, fontSize = 11.sp
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Submit findings
// ---------------------------------------------------------------------------

@Composable
private fun SubmitFindingsDialog(c: MyCase, onDismiss: () -> Unit, onSubmit: suspend (String) -> ActionResult) {
    val scope = rememberCoroutineScope()
    var remarks by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val t = c.scans
    Dialog(onDismissRequest = { if (!sending) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color.White).padding(20.dp)
        ) {
            Text("Submit findings", color = AC.Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Text("Send ${c.code} to ${c.assignedBy?.name ?: "the Supreme Authority"} for a decision.", color = AC.Muted, fontSize = 13.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ResultTile("Packages", t.total, AC.Blue, Modifier.weight(1f))
                ResultTile("Passed", t.passed, AC.Green, Modifier.weight(1f))
                ResultTile("Failed", t.failed, AC.Red, Modifier.weight(1f))
                ResultTile("Review", t.review, AC.Amber, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text("Compliance ${fmtPct(t.rate)} • violations are recorded from failed rules automatically", color = AC.Faint, fontSize = 11.5.sp)
            Spacer(Modifier.height(14.dp))
            Text("Remarks (optional)", color = AC.Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 90.dp).clip(RoundedCornerShape(14.dp)).background(AC.PageMid)
                    .border(1.dp, AC.Line, RoundedCornerShape(14.dp)).padding(12.dp)
            ) {
                if (remarks.isEmpty()) Text("Observations for the authority, e.g. stock seized, shop warned…", color = AC.Faint, fontSize = 13.sp)
                BasicTextField(
                    value = remarks, onValueChange = { remarks = it.take(500) },
                    textStyle = TextStyle(color = AC.Ink, fontSize = 14.sp), cursorBrush = SolidColor(AC.Blue),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text("${remarks.length}/500", color = AC.Faint, fontSize = 10.5.sp, modifier = Modifier.align(Alignment.End).padding(top = 2.dp))
            error?.let { Text(it, color = AC.Red, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("Cancel", onDismiss, Modifier.weight(1f), enabled = !sending, height = 44.dp)
                PrimaryButton(
                    "Submit for review",
                    onClick = {
                        sending = true
                        error = null
                        scope.launch {
                            val r = onSubmit(remarks.trim())
                            if (r is ActionResult.Rejected) error = r.message
                            sending = false
                        }
                    },
                    modifier = Modifier.weight(1.3f), loading = sending, height = 44.dp,
                    colors = listOf(AC.Green, Color(0xFF34C26B))
                )
            }
        }
    }
}
