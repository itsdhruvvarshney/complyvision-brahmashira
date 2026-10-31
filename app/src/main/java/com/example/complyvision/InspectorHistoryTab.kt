package com.example.complyvision

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val HistoryStatuses = listOf("All", CaseStatus.IN_PROGRESS, CaseStatus.UNDER_REVIEW, CaseStatus.PASSED, CaseStatus.FAILED)
private val MonthHeader = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

private sealed interface HistoryItem {
    data class Header(val label: String, val count: Int, val totals: PackageTotals) : HistoryItem
    data class Case(val case: MyCase) : HistoryItem
}

@Composable
fun InspectorHistoryTab(store: InspectorStore, exporter: ReportExporter, bottomSpace: Dp, onOpenCase: (String) -> Unit) {
    var view by rememberSaveable { mutableStateOf("cases") }
    var status by rememberSaveable { mutableStateOf("All") }
    var days by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) { store.loadHistory(silent = true) }

    when (val state = store.history) {
        Load.Loading -> LoadingPanel("Loading your history…")
        is Load.Failed -> ErrorPanel(state.message, { store.loadHistory() }, Modifier.padding(16.dp))
        is Load.Ready -> {
            val h = state.data
            val q = query.trim().lowercase(Locale.ENGLISH)
            val cutoff = if (days == 0) null else Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
            val filtered = remember(h, status, days, q) {
                h.cases.filter { status == "All" || it.status == status }
                    .filter { c -> cutoff == null || (parseInstant(c.lastActivity)?.isAfter(cutoff) ?: false) }
                    .filter { c ->
                        q.isEmpty() || listOf(c.code, c.product, c.brand, c.establishment, c.city, c.category)
                            .any { it.lowercase(Locale.ENGLISH).contains(q) }
                    }
            }
            val items = remember(filtered) {
                filtered.groupBy { c -> parseInstant(c.lastActivity)?.atZone(ZoneId.systemDefault())?.format(MonthHeader) ?: "Undated" }
                    .flatMap { (month, list) ->
                        val t = PackageTotals(list.sumOf { it.scans.total }, list.sumOf { it.scans.passed }, list.sumOf { it.scans.failed }, list.sumOf { it.scans.review })
                        listOf<HistoryItem>(HistoryItem.Header(month, list.size, t)) + list.map { HistoryItem.Case(it) }
                    }
            }
            val totals = PackageTotals(filtered.sumOf { it.scans.total }, filtered.sumOf { it.scans.passed },
                filtered.sumOf { it.scans.failed }, filtered.sumOf { it.scans.review })

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = bottomSpace + 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Column {
                        Text("Inspection History", color = AC.Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Every inspection you have worked on, newest first", color = AC.Muted, fontSize = 13.sp)
                    }
                }
                item { MonthlyCard(h.monthly) }
                if (h.topIssues.isNotEmpty()) item { TopIssuesCard(h.topIssues) }
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Color.White).border(1.dp, AC.Line, RoundedCornerShape(50)).padding(4.dp)) {
                        listOf("cases" to "Inspections", "activity" to "Activity log").forEach { (key, label) ->
                            val on = view == key
                            Text(
                                label, color = if (on) Color.White else AC.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)).background(if (on) AC.Blue else Color.Transparent)
                                    .clickable(role = Role.Tab) { view = key }.padding(vertical = 9.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                if (view == "activity") {
                    if (h.activity.isEmpty()) item { EmptyPanel("No activity yet", "Assignments, scans and decisions on your inspections appear here.", AIcon.CLOCK) }
                    items(h.activity, key = { "${it.code}-${it.at}-${it.type}-${it.title}" }) { a -> ActivityRow(a) { onOpenCase(a.code) } }
                } else {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SearchBox(query, { query = it }, "Search history…", Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            PeriodChip(days) { days = it }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HistoryStatuses.forEach { s ->
                                FilterPill(s, status == s, { status = s }, count = h.cases.count { s == "All" || it.status == s })
                            }
                        }
                    }
                    item {
                        GlassCard(padding = 12.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${fmtInt(filtered.size)} inspections • ${fmtInt(totals.total)} packages", color = AC.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("${fmtInt(totals.passed)} passed • ${fmtInt(totals.failed)} failed • ${fmtInt(totals.review)} under review",
                                        color = AC.Muted, fontSize = 11.5.sp)
                                }
                                Text(fmtPct(totals.rate), color = rateColor(totals.rate), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Spacer(Modifier.height(8.dp))
                            OutcomeBar(totals)
                        }
                    }
                    if (items.isEmpty()) {
                        item { EmptyPanel("Nothing to show", if (h.cases.isEmpty()) "Your completed inspections will appear here." else "No inspections match these filters.", AIcon.CLOCK) }
                    }
                    items(items, key = { when (it) { is HistoryItem.Header -> "h-${it.label}"; is HistoryItem.Case -> it.case.code } }) { item ->
                        when (item) {
                            is HistoryItem.Header -> MonthHeaderRow(item)
                            is HistoryItem.Case -> HistoryCaseCard(item.case, exporter) { onOpenCase(item.case.code) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthlyCard(months: List<MonthStat>) {
    GlassCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AIconView(AIcon.CHART, AC.Blue, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Packages analysed per month", color = AC.Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        StackedBars(months.map { WeekPoint(it.label, it.passed, it.failed, it.review) }, Modifier.fillMaxWidth().height(110.dp))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            months.forEach { m ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(m.label.take(3), color = AC.Muted, fontSize = 10.5.sp)
                    Text(if (m.total == 0) "—" else fmtInt(m.total), color = AC.Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot("Passed", AC.Green); LegendDot("Failed", AC.Red); LegendDot("Under review", AC.Amber)
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(label, color = AC.Muted, fontSize = 11.sp)
    }
}

@Composable
private fun TopIssuesCard(issues: List<IssueStat>) {
    val max = issues.maxOf { it.failed + it.review }.coerceAtLeast(1)
    GlassCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AIconView(AIcon.FLAG, AC.Red, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Most frequent issues you found", color = AC.Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.height(8.dp))
        issues.forEach { i ->
            Column(Modifier.padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(i.id, color = AC.Blue, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(44.dp))
                    Text(i.title, color = AC.Ink, fontSize = 12.5.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${i.failed} fail" + if (i.review > 0) " • ${i.review} review" else "", color = AC.Muted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(3.dp))
                Box(Modifier.padding(start = 44.dp)) {
                    ProgressTrack((i.failed + i.review).toFloat() / max, if (i.failed > 0) AC.Red else AC.Amber, height = 6.dp)
                }
            }
        }
    }
}

@Composable
private fun MonthHeaderRow(h: HistoryItem.Header) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(h.label, color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        Text("${h.count} inspection${if (h.count == 1) "" else "s"} • ${fmtPct(h.totals.rate)}", color = AC.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun HistoryCaseCard(c: MyCase, exporter: ReportExporter, onOpen: () -> Unit) {
    GlassCard(padding = 12.dp, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProductTile(c.category, 44.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(c.product, color = AC.Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${c.code} • ${c.establishment}, ${c.city}", color = AC.Muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(fmtDateTime(c.lastActivity), color = AC.Faint, fontSize = 11.sp)
            }
            StatusPill(c.status)
        }
        if (c.scans.total > 0) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatNum("Passed", c.scans.passed, AC.Green, Modifier.weight(1f))
                StatNum("Failed", c.scans.failed, AC.Red, Modifier.weight(1f))
                StatNum("Review", c.scans.review, AC.Amber, Modifier.weight(1f))
                Column(Modifier.weight(1.1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Compliance", color = AC.Muted, fontSize = 10.sp)
                    Text(fmtPct(c.scans.rate), color = rateColor(c.scans.rate), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                }
                DownloadButton(exporter.busy == "pdf:${c.code}") { exporter.casePdf(c.code, c.product) }
            }
        } else {
            Text("No packages analysed", color = AC.Faint, fontSize = 11.5.sp, modifier = Modifier.padding(top = 8.dp))
        }
        if (c.violationTypes.isNotEmpty()) {
            Text("Violations: ${c.violationTypes.joinToString(", ")}", color = AC.Red, fontSize = 11.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp))
        }
        c.assignedBy?.name?.takeIf { it.isNotBlank() }?.let {
            Text("Assigned by $it", color = AC.Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun StatNum(label: String, value: Int, tone: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = AC.Muted, fontSize = 10.sp)
        Text(fmtInt(value), color = tone, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun ActivityRow(a: ActivityItem, onClick: () -> Unit) {
    val tone = when (a.type) {
        "Assigned", "Reassigned" -> AC.Violet
        "Scheduled", "Rescheduled" -> AC.Blue
        "Started" -> AC.Teal
        "Scan" -> AC.Blue
        "Review" -> AC.Amber
        "Result" -> if (a.title.contains("failed", true)) AC.Red else AC.Green
        "Returned" -> AC.Orange
        else -> AC.Slate
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, AC.Line, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button) { onClick() }.padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.padding(top = 4.dp).size(10.dp).clip(CircleShape).background(tone))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, color = AC.Ink, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            Text("${a.product} • ${a.code}", color = AC.Blue, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (a.detail.isNotBlank()) Text(a.detail, color = AC.Muted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(listOf(fmtDateTime(a.at), a.actor).filter { it.isNotBlank() }.joinToString(" • "), color = AC.Faint, fontSize = 11.sp)
        }
    }
}
