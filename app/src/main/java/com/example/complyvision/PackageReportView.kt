package com.example.complyvision

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.util.Locale

internal fun ruleTone(status: String): Pair<Color, Color> = when (status) {
    RuleStatus.PASS -> AC.Green to AC.GreenSoft
    RuleStatus.FAIL -> AC.Red to AC.RedSoft
    RuleStatus.REVIEW -> AC.Amber to AC.AmberSoft
    else -> AC.Slate to AC.SlateSoft
}

internal fun ruleLabel(status: String): String = when (status) {
    RuleStatus.PASS -> "PASS"
    RuleStatus.FAIL -> "FAIL"
    RuleStatus.REVIEW -> "REVIEW"
    else -> "N/A"
}

/** A stored package report opened from a case */
@Composable
fun PackageReportPage(
    store: InspectorStore,
    exporter: ReportExporter,
    caseCode: String,
    scanCode: String,
    bottomSpace: Dp,
    onBack: () -> Unit
) {
    var state by remember(scanCode) { mutableStateOf<Load<Pair<ScanSummary, PackageReport>>>(Load.Loading) }
    var caseInfo by remember(caseCode) { mutableStateOf<MyCase?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var resolving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(scanCode, reload) {
        if (state !is Load.Ready) state = Load.Loading
        state = try {
            Load.Ready(store.scan(scanCode))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(store.handle(e) ?: return@LaunchedEffect)
        }
    }
    LaunchedEffect(caseCode) {
        caseInfo = (store.cases as? Load.Ready)?.data?.items?.firstOrNull { it.code == caseCode }
            ?: try { store.case(caseCode).case } catch (_: Exception) { null }
    }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(scanCode, (state as? Load.Ready)?.data?.second?.verdictLabel, onBack)
        when (val s = state) {
            Load.Loading -> LoadingPanel("Loading package report…")
            is Load.Failed -> ErrorPanel(s.message, { reload++ }, Modifier.padding(16.dp))
            is Load.Ready -> {
                val (summary, report) = s.data
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)
                        .padding(top = 4.dp, bottom = bottomSpace + 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    caseInfo?.let { c ->
                        Text("${c.product} • ${c.code}", color = AC.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    // Review items can be settled while the inspection is still open
                    val canResolve = caseInfo?.status == CaseStatus.IN_PROGRESS
                    PackageReportContent(report, summary, resolving = resolving, onResolve = if (!canResolve) null else { decisions ->
                        resolving = true
                        scope.launch {
                            when (val r = store.resolveScan(summary.scanCode, decisions)) {
                                is ActionResult.Done -> { store.showToast("Decisions saved - package re-scored"); reload++ }
                                is ActionResult.Rejected -> store.showToast(r.message, isError = true)
                            }
                            resolving = false
                        }
                    })
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Download PDF", { exporter.packagePdf(caseInfo, summary, report) }, Modifier.weight(1f),
                            loading = exporter.busy == "pdf:${summary.scanCode}", icon = AIcon.DOWNLOAD, height = 44.dp)
                        SoftButton("Export JSON", { exporter.packageJson(summary, report) }, Modifier.weight(1f),
                            enabled = exporter.busy == null, icon = AIcon.DOC, height = 44.dp)
                    }
                }
            }
        }
    }
}

/**
 * Full rendering of a canonical package report (shared by the scanner result and stored reports):
 * verdict -> how the product was categorised and which standards apply -> the inspector's review
 * items -> every applied rule grouped by section -> evidence and the technical trail.
 */
@Composable
fun PackageReportContent(
    report: PackageReport,
    summary: ScanSummary?,
    onChangeCategory: (() -> Unit)? = null,
    resolving: Boolean = false,
    onResolve: ((List<RuleDecision>) -> Unit)? = null
) {
    val evidenceById = remember(report) { report.evidence.associateBy { it.id } }
    var zoom by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        VerdictCard(report, summary)

        report.classification?.let { ClassificationCard(it, onChangeCategory) }

        val photoNames = remember(report) { report.raw.optJSONArray("photos").jsonObjects().map { it.str("label") } }
        report.raw.optJSONObject("intelligence")?.let { ProductIntelligenceSection(it, photoNames) }
        report.raw.optJSONObject("compliance_review")?.let { ComplianceReviewSection(it, report.raw.str("mode") == "org") }
        report.raw.optJSONObject("gemini_audit")?.let { GeminiAuditLine(it, photoNames.size) }

        val pending = report.reviewRules
        if (onResolve != null && pending.isNotEmpty()) ReviewResolutionCard(pending, resolving, onResolve)

        RuleList(report, evidenceById) { zoom = it }

        if (report.annotatedImage.isNotBlank()) {
            GlassCard(padding = 12.dp) {
                SectionHeader(AIcon.SEARCH, "Annotated capture", "Corrected image with every declaration the rules relied on")
                Spacer(Modifier.height(8.dp))
                DataImage(report.annotatedImage, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button) { zoom = report.annotatedImage }, 1400)
            }
        }

        if (report.evidence.isNotEmpty()) EvidenceGrid(report.evidence) { zoom = it }

        if (report.declarations.isNotEmpty()) {
            GlassCard(padding = 14.dp) {
                SectionHeader(AIcon.DOC, "Extracted declarations", "Read by PaddleOCR and parsed into label declaration fields")
                Spacer(Modifier.height(8.dp))
                KeyValueTable(report.declarations)
            }
        }

        if ((report.measurements.isNotEmpty() || report.calibration.isNotBlank()) && report.raw.str("mode") != "consumer") {
            GlassCard(padding = 14.dp) {
                SectionHeader(AIcon.SORT, "Measurements", "ArUco scale calibration, numeral glyph height and local contrast")
                Spacer(Modifier.height(8.dp))
                KeyValueTable(buildList {
                    if (report.calibration.isNotBlank()) add("Calibration" to report.calibration)
                    addAll(report.measurements)
                })
            }
        }

        if (report.qualityGrade.isNotBlank() || report.preprocessing.isNotEmpty()) QualityCard(report)
        report.ai?.let { AiCard(it, report.crossChecks) }
        if (report.ocrText.isNotBlank()) OcrCard(report)
        if (report.pipeline.isNotEmpty()) PipelineCard(report)
        if (report.input.isNotEmpty()) {
            GlassCard(padding = 14.dp) {
                SectionHeader(AIcon.SHIELD, "Validated input", "MIME type confirmed from the file signature")
                Spacer(Modifier.height(8.dp))
                KeyValueTable(report.input)
            }
        }
        if (report.seeded) {
            Text(
                "Historical record imported from the field log - evidence images were not retained for this package.",
                color = AC.Faint, fontSize = 11.5.sp, lineHeight = 15.sp
            )
        }
    }

    zoom?.let { uri ->
        Dialog(onDismissRequest = { zoom = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color(0xEE07142A)).clickable { zoom = null }, contentAlignment = Alignment.Center) {
                DataImage(uri, Modifier.fillMaxWidth().padding(12.dp), 2000, ContentScale.Fit)
                Text("Tap to close", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
            }
        }
    }
}

@Composable
internal fun DataImage(uri: String, modifier: Modifier, maxSide: Int = 800, scale: ContentScale = ContentScale.FillWidth) {
    val bmp = remember(uri) { decodeDataUri(uri, maxSide) }
    if (bmp != null) {
        Image(bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = scale, modifier = modifier)
    } else {
        Box(modifier.height(80.dp).background(AC.SlateSoft), contentAlignment = Alignment.Center) {
            Text("Image unavailable", color = AC.Faint, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun SectionHeader(icon: AIcon, title: String, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(AC.BlueSoft), contentAlignment = Alignment.Center) {
            AIconView(icon, AC.Blue, Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            if (subtitle != null) Text(subtitle, color = AC.Muted, fontSize = 11.5.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun VerdictCard(r: PackageReport, summary: ScanSummary?) {
    val (fg, bg) = statusStyle(r.verdictLabel)
    GlassCard(padding = 16.dp, tint = bg.copy(alpha = 0.85f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                AIconView(when (r.verdict) { RuleStatus.PASS -> AIcon.CHECK_CIRCLE; RuleStatus.FAIL -> AIcon.CROSS_CIRCLE; else -> AIcon.HOURGLASS }, fg, Modifier.size(30.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.verdictLabel.uppercase(Locale.ENGLISH), color = fg, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Text(r.headline, color = AC.Ink, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtPct(r.score), color = fg, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Text("rule score", color = AC.Muted, fontSize = 10.5.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CountChip("Passed", r.passed, RuleStatus.PASS, Modifier.weight(1f))
            CountChip("Failed", r.failed, RuleStatus.FAIL, Modifier.weight(1f))
            CountChip("Review", r.review, RuleStatus.REVIEW, Modifier.weight(1f))
            CountChip("N/A", r.notApplicable, RuleStatus.NA, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            listOfNotNull(
                fmtDateTime(summary?.createdAt ?: r.generatedAt),
                summary?.source,
                (summary?.durationMs ?: r.durationMs).takeIf { it > 0 }?.let { String.format(Locale.US, "analysed in %.1f s", it / 1000.0) },
                r.analyzerVersion.takeIf { it.isNotBlank() }?.let { "analyzer $it" }
            ).joinToString(" • "),
            color = AC.Muted, fontSize = 11.sp
        )
    }
}

@Composable
private fun CountChip(label: String, value: Int, status: String, modifier: Modifier) {
    val (fg, _) = ruleTone(status)
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), color = fg, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = AC.Muted, fontSize = 10.5.sp)
    }
}

@Composable
private fun RuleList(r: PackageReport, evidence: Map<String, EvidenceItem>, onZoom: (String) -> Unit) {
    val order = listOf(RuleStatus.FAIL, RuleStatus.REVIEW, RuleStatus.PASS, RuleStatus.NA)
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = r.rules.filter { (status == null || it.status == status) && (section == null || it.section == section) }
    val category = r.classification?.categoryName
    GlassCard(padding = 14.dp) {
        SectionHeader(AIcon.CLIPBOARD, "Rule-by-rule verification",
            "${r.rules.size} rules applied" + (category?.let { " for ${it.lowercase(Locale.ENGLISH)}" } ?: "") + " • tap a rule for its evidence")
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterPill("All", status == null, { status = null }, r.rules.size)
            listOf(RuleStatus.FAIL to "Failed", RuleStatus.REVIEW to "Review", RuleStatus.PASS to "Passed", RuleStatus.NA to "N/A").forEach { (s, label) ->
                val n = r.rules.count { it.status == s }
                if (n > 0) FilterPill(label, status == s, { status = if (status == s) null else s }, n)
            }
        }
        Spacer(Modifier.height(6.dp))
        SectionSummary(r.sections, section) { section = it }
        Spacer(Modifier.height(6.dp))
        // Grouped by section in catalog order; failures first inside each section
        shown.groupBy { it.section }.forEach { (name, rules) ->
            Text(name.uppercase(Locale.ENGLISH), color = AC.Faint, fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp, start = 2.dp))
            rules.sortedBy { order.indexOf(it.status).let { i -> if (i < 0) 9 else i } }.forEach { rule -> RuleRow(rule, evidence, onZoom) }
        }
        if (shown.isEmpty()) Text("No rules match this filter.", color = AC.Muted, fontSize = 12.5.sp, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun RuleRow(rule: RuleResult, evidence: Map<String, EvidenceItem>, onZoom: (String) -> Unit) {
    var open by rememberSaveable(rule.id) { mutableStateOf(rule.status == RuleStatus.FAIL) }
    val (fg, bg) = ruleTone(rule.status)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(Color.White)
            .border(1.dp, if (open) fg.copy(alpha = 0.35f) else AC.Line, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button) { open = !open }.padding(10.dp).animateContentSize()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(rule.id, color = AC.Blue, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(44.dp))
            Text(rule.title, color = AC.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            if (rule.aiVerified) { Pill("AI verified", AC.Violet, AC.VioletSoft, size = 9); Spacer(Modifier.width(4.dp)) }
            else if (rule.manualReview) { Pill("Check manually", AC.Amber, AC.AmberSoft, size = 9); Spacer(Modifier.width(4.dp)) }
            Pill(ruleLabel(rule.status), fg, bg, size = 10)
        }
        if (open) {
            Spacer(Modifier.height(6.dp))
            Text(rule.reason, color = AC.Ink, fontSize = 12.5.sp, lineHeight = 17.sp)
            if (rule.aiVerified) {
                Text("The text reader first reported ${ruleLabel(rule.originalStatus)}. AI read the label again and found the declaration" +
                    (rule.geminiEvidence.takeIf { it.isNotBlank() }?.let { ": \"$it\"" } ?: "."),
                    color = AC.Violet, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp))
            } else if (rule.geminiOpinion.isNotBlank() && rule.geminiNote.isNotBlank()) {
                Text("AI second look: ${rule.geminiNote}", color = if (rule.manualReview) AC.Amber else AC.Muted, fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp))
            }
            if (rule.found.isNotBlank() || rule.expected.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Row {
                    if (rule.expected.isNotBlank()) LabelValue("Expected", rule.expected, Modifier.weight(1f))
                    if (rule.found.isNotBlank()) LabelValue("Found", rule.found, Modifier.weight(1f), valueColor = fg)
                }
            }
            if (rule.resolved) {
                Text("Settled by ${rule.resolvedBy} on the physical pack" + (rule.resolvedAt.takeIf { it.isNotBlank() }?.let { " • ${fmtDateTime(it)}" } ?: ""),
                    color = AC.Violet, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            }
            if (rule.reference.isNotBlank()) {
                Text(rule.reference, color = AC.Muted, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 6.dp))
            }
            val meta = listOf(rule.applies.takeIf { it.isNotBlank() }?.let { "Applies: $it" }, rule.severity.takeIf { it.isNotBlank() }?.let { "$it severity" })
                .filter { !it.isNullOrBlank() }
            if (meta.isNotEmpty()) Text(meta.joinToString(" • "), color = AC.Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            val crops = rule.evidence.mapNotNull { evidence[it] }
            if (crops.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    crops.forEach { e ->
                        Column(Modifier.width(150.dp)) {
                            DataImage(e.image, Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(10.dp)).clickable { onZoom(e.image) }, 500, ContentScale.Crop)
                            Text("${e.id} • ${e.label}", color = AC.Muted, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EvidenceGrid(items: List<EvidenceItem>, onZoom: (String) -> Unit) {
    GlassCard(padding = 14.dp) {
        SectionHeader(AIcon.FLAG, "Evidence mapping", "Each crop is linked to the rules it supports")
        Spacer(Modifier.height(10.dp))
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                row.forEach { e ->
                    val (fg, bg) = ruleTone(e.status)
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color.White).border(1.dp, AC.Line, RoundedCornerShape(14.dp))) {
                        DataImage(e.image, Modifier.fillMaxWidth().height(86.dp).clickable { onZoom(e.image) }, 600, ContentScale.Crop)
                        Column(Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.id, color = AC.Blue, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                Spacer(Modifier.width(6.dp))
                                Text(e.label, color = AC.Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                            Text(if (e.ruleIds.isEmpty()) "Context" else "→ ${e.ruleIds.joinToString(", ")}", color = AC.Muted, fontSize = 10.5.sp)
                            if (e.status.isNotBlank()) Pill(ruleLabel(e.status), fg, bg, Modifier.padding(top = 4.dp), size = 9)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun KeyValueTable(rows: List<Pair<String, String>>) {
    Column {
        rows.forEachIndexed { i, (k, v) ->
            Row(
                Modifier.fillMaxWidth().background(if (i % 2 == 0) AC.PageMid else Color.Transparent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 7.dp)
            ) {
                Text(k, color = AC.Muted, fontSize = 12.sp, modifier = Modifier.weight(0.9f))
                Text(v.ifBlank { "—" }, color = AC.Ink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1.3f), lineHeight = 16.sp)
            }
        }
    }
}

@Composable
private fun QualityCard(r: PackageReport) {
    GlassCard(padding = 14.dp) {
        SectionHeader(AIcon.SHIELD, "Image quality & OpenCV corrections", "Tilt, glare, exposure and contrast handled before OCR")
        Spacer(Modifier.height(10.dp))
        if (r.qualityGrade.isNotBlank()) {
            val tone = when (r.qualityGrade) { "Good" -> AC.Green; "Fair" -> AC.Amber; else -> AC.Red }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("${r.qualityGrade} capture", tone, tone.copy(alpha = 0.13f))
                r.qualityScore?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(String.format(Locale.US, "%.0f / 100", it), color = AC.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (r.qualityMetrics.isNotEmpty()) KeyValueTable(r.qualityMetrics)
        if (r.preprocessing.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            r.preprocessing.forEach { s ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                    AIconView(if (s.applied) AIcon.CHECK_CIRCLE else AIcon.MORE, if (s.applied) AC.Green else AC.Faint, Modifier.padding(top = 1.dp).size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        s.step.replaceFirstChar { it.uppercase() } + ": " + s.detail,
                        color = if (s.applied) AC.Ink else AC.Muted, fontSize = 12.sp, lineHeight = 16.sp
                    )
                }
            }
        }
        if (r.warnings.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            r.warnings.forEach { w ->
                val tone = when (w.severity) { "high" -> AC.Red; "medium" -> AC.Amber; else -> AC.Slate }
                Text(
                    "• ${w.message}", color = tone, fontSize = 12.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun AiCard(ai: AiInsights, checks: List<CrossCheck>) {
    GlassCard(padding = 14.dp, tint = Color(0xF2F6F2FF)) {
        SectionHeader(AIcon.TREND_UP, "AI insights", if (ai.available) "Gemini (${ai.model}) • advisory - never overrides a rule" else "Gemini enrichment")
        Spacer(Modifier.height(10.dp))
        if (!ai.available) {
            Text(ai.reason.ifBlank { "AI enrichment was not available for this package." }, color = AC.Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
            return@GlassCard
        }
        if (ai.summary.isNotBlank()) Text(ai.summary, color = AC.Ink, fontSize = 13.sp, lineHeight = 18.sp)
        val facts = listOf("Product" to ai.productName, "Brand" to ai.brand, "Generic name" to ai.genericName, "Category" to ai.category,
            "Ingredients" to ai.ingredients, "Storage" to ai.storage).filter { it.second.isNotBlank() }
        if (facts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            KeyValueTable(facts)
        }
        listOf(
            Triple("Regulatory observations", ai.regulatoryObservations, AC.Red),
            Triple("Suspected issues", ai.suspectedIssues, AC.Orange),
            Triple("Consumer advisory", ai.consumerAdvisory, AC.Blue),
            Triple("Allergens", ai.allergens, AC.Amber),
            Triple("Nutrition highlights", ai.nutrition, AC.Teal),
            Triple("Claims", ai.claims, AC.Violet),
            Triple("Certifications", ai.certifications, AC.Green)
        ).filter { it.second.isNotEmpty() }.forEach { (title, items, tone) ->
            Spacer(Modifier.height(10.dp))
            Text(title, color = tone, fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold)
            items.forEach { Text("• $it", color = AC.Ink, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp)) }
        }
        if (checks.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("OCR vs AI cross-check", color = AC.Ink, fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold)
            checks.forEach { c ->
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.field.replace('_', ' ').replaceFirstChar { it.uppercase() }, color = AC.Muted, fontSize = 11.5.sp, modifier = Modifier.weight(0.9f))
                    Text("${c.parser.ifBlank { "—" }} / ${c.ai.ifBlank { "—" }}", color = AC.Ink, fontSize = 11.5.sp, modifier = Modifier.weight(1.4f))
                    Text(when (c.agree) { true -> "✓"; false -> "✗"; null -> "·" }, color = when (c.agree) { true -> AC.Green; false -> AC.Red; null -> AC.Faint },
                        fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
        val meta = listOfNotNull(ai.confidence?.let { String.format(Locale.US, "confidence %.0f%%", it * 100) }, ai.latencyMs.takeIf { it > 0 }?.let { "$it ms" })
        if (meta.isNotEmpty()) Text(meta.joinToString(" • "), color = AC.Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun OcrCard(r: PackageReport) {
    var open by rememberSaveable { mutableStateOf(false) }
    GlassCard(padding = 14.dp) {
        Row(Modifier.clickable(role = Role.Button) { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                SectionHeader(AIcon.DOC, "OCR text", listOfNotNull(r.ocrEngine.ifBlank { null }, "${r.ocrLines} lines",
                    r.ocrConfidence?.let { String.format(Locale.US, "%.0f%% mean confidence", it * 100) },
                    if (r.ocrVerified > 0) "${r.ocrVerified} re-verified" else null).joinToString(" • "))
            }
            AIconView(AIcon.CHEVRON_DOWN, AC.Muted, Modifier.size(16.dp))
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            Text(
                r.ocrText, color = AC.Ink, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AC.PageMid).padding(10.dp)
            )
        }
    }
}

@Composable
private fun PipelineCard(r: PackageReport) {
    val total = r.pipeline.sumOf { it.ms }.coerceAtLeast(1)
    GlassCard(padding = 14.dp) {
        SectionHeader(AIcon.CLOCK, "Processing pipeline", "FastAPI → OpenCV → PaddleOCR → rule engine → Gemini")
        Spacer(Modifier.height(8.dp))
        r.pipeline.forEach { s ->
            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                val tone = when (s.status) { "ok" -> AC.Green; "skipped" -> AC.Faint; else -> AC.Red }
                Box(Modifier.size(8.dp).clip(CircleShape).background(tone))
                Spacer(Modifier.width(8.dp))
                Text(s.label.ifBlank { s.stage }, color = AC.Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Box(Modifier.width(70.dp)) { ProgressTrack(s.ms.toFloat() / total, AC.Blue.copy(alpha = 0.6f), height = 5.dp) }
                Text("${s.ms} ms", color = AC.Muted, fontSize = 11.sp, modifier = Modifier.width(56.dp).padding(start = 6.dp))
            }
        }
    }
}
