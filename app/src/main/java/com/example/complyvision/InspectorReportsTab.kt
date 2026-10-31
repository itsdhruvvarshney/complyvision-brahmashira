package com.example.complyvision

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** One downloadable report per inspection that has analysed packages */
@Composable
fun InspectorReportsTab(store: InspectorStore, exporter: ReportExporter, bottomSpace: Dp, onOpenCase: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var newestFirst by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) { store.loadHistory(silent = true) }

    when (val state = store.history) {
        Load.Loading -> LoadingPanel("Loading reports…")
        is Load.Failed -> ErrorPanel(state.message, { store.loadHistory() }, Modifier.padding(16.dp))
        is Load.Ready -> {
            val q = query.trim().lowercase(Locale.ENGLISH)
            val reports = remember(state.data, q, newestFirst) {
                state.data.cases.filter { it.scans.total > 0 }
                    .filter { c -> q.isEmpty() || listOf(c.code, c.product, c.brand, c.establishment, c.city).any { it.lowercase(Locale.ENGLISH).contains(q) } }
                    .sortedBy { parseInstant(it.firstScanAt ?: it.startedAt)?.toEpochMilli() ?: 0L }
                    .let { if (newestFirst) it.reversed() else it }
            }
            val packages = reports.sumOf { it.scans.total }
            val passed = reports.sumOf { it.scans.passed }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = bottomSpace + 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Column {
                        Text("Reports", color = AC.Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Inspection reports built live from your package analyses", color = AC.Muted, fontSize = 13.sp)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SummaryTile("Reports", fmtInt(reports.size), AC.Blue, AIcon.DOC, Modifier.weight(1f))
                        SummaryTile("Packages", fmtInt(packages), AC.Violet, AIcon.BOX, Modifier.weight(1f))
                        SummaryTile("Compliance", fmtPctOf(passed, packages), rateColor(if (packages == 0) 0.0 else 100.0 * passed / packages), AIcon.CHECK_CIRCLE, Modifier.weight(1f))
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SearchBox(query, { query = it }, "Search product, code, shop…", Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        RoundIconButton(AIcon.SORT, if (newestFirst) "Showing newest first" else "Showing oldest first", { newestFirst = !newestFirst }, size = 46.dp, tint = AC.Blue)
                    }
                }
                item {
                    Text(
                        "PDF reports include the test-case results, package register, evidence mapping images and the full timeline. JSON exports the canonical data.",
                        color = AC.Faint, fontSize = 11.5.sp, lineHeight = 15.sp
                    )
                }
                if (reports.isEmpty()) {
                    item { EmptyPanel("No reports yet", if (q.isEmpty()) "Reports appear once you analyse packages for an inspection." else "No reports match \"$query\".", AIcon.DOC) }
                }
                items(reports, key = { it.code }) { c -> ReportCard(c, exporter) { onOpenCase(c.code) } }
            }
        }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, tone: Color, icon: AIcon, modifier: Modifier) {
    GlassCard(modifier = modifier, padding = 12.dp) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(tone.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            AIconView(icon, tone, Modifier.size(15.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(value, color = AC.Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(label, color = AC.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun ReportCard(c: MyCase, exporter: ReportExporter, onOpen: () -> Unit) {
    val inspectedAt = c.firstScanAt ?: c.startedAt
    GlassCard(padding = 14.dp, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(AC.RedSoft), contentAlignment = Alignment.Center) {
                Text("PDF", color = AC.Red, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.product, color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                Text("${c.code} • ${c.establishment}, ${c.city}", color = AC.Muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusPill(c.status)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            LabelValue("Date of inspection", fmtDate(inspectedAt), Modifier.weight(1f))
            LabelValue("Time", parseInstant(inspectedAt)?.let { fmtDateTime(inspectedAt).substringAfter(", ") } ?: "—", Modifier.weight(0.8f))
            LabelValue("Packages", "${fmtInt(c.scans.total)} • ${fmtPct(c.scans.rate)}", Modifier.weight(1f), valueColor = rateColor(c.scans.rate))
        }
        Spacer(Modifier.height(8.dp))
        OutcomeBar(c.scans, 6.dp)
        if (c.lastScanAt != null && c.lastScanAt != c.firstScanAt) {
            Text("Last package analysed ${fmtDateTime(c.lastScanAt)}", color = AC.Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                "PDF report", { exporter.casePdf(c.code, c.product) }, Modifier.weight(1f),
                enabled = exporter.busy == null || exporter.busy == "pdf:${c.code}", loading = exporter.busy == "pdf:${c.code}",
                icon = AIcon.DOWNLOAD, height = 42.dp
            )
            SoftButton(
                if (exporter.busy == "json:${c.code}") "Preparing…" else "JSON data", { exporter.caseJson(c.code, c.product) },
                Modifier.weight(1f), enabled = exporter.busy == null, icon = AIcon.DOC, height = 42.dp
            )
        }
    }
}
