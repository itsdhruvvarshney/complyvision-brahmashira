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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import java.time.LocalDate
import java.util.Locale

enum class InspectionFilter(val label: String) {
    PENDING("Pending"),
    ACTIVE("In Progress"),
    REVIEW("Under Review"),
    COMPLETED("Completed"),
    ALL("All");

    fun matches(c: MyCase): Boolean = when (this) {
        PENDING -> c.status == CaseStatus.ASSIGNED || c.status == CaseStatus.SCHEDULED
        ACTIVE -> c.status == CaseStatus.IN_PROGRESS
        REVIEW -> c.status == CaseStatus.UNDER_REVIEW
        COMPLETED -> c.isClosed
        ALL -> true
    }
}

private enum class CaseSort(val label: String) { SCHEDULE("Schedule"), PRIORITY("Priority"), DUE("Due date"), RECENT("Recently assigned") }

private fun priorityRank(p: String) = when (p) { "Critical" -> 0; "High" -> 1; "Medium" -> 2; else -> 3 }

/** Whether the inspector may begin field work today (schedules in the future are locked) */
internal fun canStartToday(c: MyCase): Boolean {
    val d = parseDate(c.scheduledDate) ?: return true
    return !d.isAfter(LocalDate.now())
}

@Composable
fun InspectorInspectionsTab(
    store: InspectorStore,
    filter: InspectionFilter,
    onFilter: (InspectionFilter) -> Unit,
    bottomSpace: Dp,
    onOpenCase: (String) -> Unit,
    onScan: (MyCase) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(CaseSort.SCHEDULE.name) }

    when (val state = store.cases) {
        Load.Loading -> LoadingPanel("Loading your inspections…")
        is Load.Failed -> ErrorPanel(state.message, { store.loadCases() }, Modifier.padding(16.dp))
        is Load.Ready -> {
            val data = state.data
            val q = query.trim().lowercase(Locale.ENGLISH)
            val shown = remember(data, filter, q, sort) {
                data.items.filter { filter.matches(it) }
                    .filter {
                        q.isEmpty() || listOf(it.code, it.product, it.brand, it.establishment, it.city, it.category, it.assignedBy?.name.orEmpty())
                            .any { f -> f.lowercase(Locale.ENGLISH).contains(q) }
                    }
                    .let { list ->
                        when (CaseSort.valueOf(sort)) {
                            CaseSort.SCHEDULE -> list.sortedWith(compareBy<MyCase>({ it.status != CaseStatus.IN_PROGRESS }, { it.scheduledDate ?: "9999" }, { priorityRank(it.priority) }))
                            CaseSort.PRIORITY -> list.sortedWith(compareBy<MyCase>({ priorityRank(it.priority) }, { it.dueDate ?: "9999" }))
                            CaseSort.DUE -> list.sortedBy { it.dueDate ?: "9999" }
                            CaseSort.RECENT -> list.sortedByDescending { it.assignedAt.orEmpty() }
                        }
                    }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = bottomSpace + 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column {
                        Text("My Inspections", color = AC.Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Cases assigned to you by the Supreme Authority", color = AC.Muted, fontSize = 13.sp)
                    }
                }
                item { CaseCounters(data.counts, onFilter) }
                item { AnalyzerStatusRow(store) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SearchBox(query, { query = it }, "Search product, code, shop, city…", Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        SortButton(CaseSort.valueOf(sort)) { sort = it.name }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InspectionFilter.entries.forEach { f ->
                            FilterPill(f.label, f == filter, { onFilter(f) }, count = data.items.count { f.matches(it) })
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        EmptyPanel(
                            title = if (data.items.isEmpty()) "No inspections yet" else "Nothing here",
                            message = when {
                                data.items.isEmpty() -> "When the Supreme Authority assigns a case to you it will appear here."
                                q.isNotEmpty() -> "No ${filter.label.lowercase(Locale.ENGLISH)} inspections match \"$query\"."
                                filter == InspectionFilter.PENDING -> "You have no pending assignments. Great work!"
                                else -> "No ${filter.label.lowercase(Locale.ENGLISH)} inspections."
                            }
                        )
                    }
                } else {
                    item {
                        Text("${shown.size} inspection${if (shown.size == 1) "" else "s"}", color = AC.Faint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    items(shown, key = { it.code }) { c ->
                        AssignmentCard(c, onOpen = { onOpenCase(c.code) }, onScan = { onScan(c) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CaseCounters(c: CasePipeline, onFilter: (InspectionFilter) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Counter("Due today", c.today, AC.Blue, AIcon.CALENDAR, Modifier.weight(1f)) { onFilter(InspectionFilter.PENDING) }
        Counter("Pending", c.assigned + c.scheduled, AC.Violet, AIcon.CLIPBOARD, Modifier.weight(1f)) { onFilter(InspectionFilter.PENDING) }
        Counter("In progress", c.inProgress, AC.Teal, AIcon.CLOCK, Modifier.weight(1f)) { onFilter(InspectionFilter.ACTIVE) }
        Counter("Overdue", c.overdue, AC.Red, AIcon.FLAG, Modifier.weight(1f)) { onFilter(InspectionFilter.PENDING) }
    }
}

@Composable
private fun Counter(label: String, value: Int, tone: Color, icon: AIcon, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier.clip(RoundedCornerShape(18.dp)).background(Color.White).border(1.dp, AC.Line, RoundedCornerShape(18.dp))
            .clickable(role = Role.Button) { onClick() }.padding(10.dp)
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(tone.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            AIconView(icon, tone, Modifier.size(14.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(fmtInt(value), color = AC.Ink, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = AC.Muted, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SortButton(current: CaseSort, onPick: (CaseSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RoundIconButton(AIcon.SORT, "Sort by ${current.label}", { open = true }, size = 46.dp, tint = AC.Blue)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CaseSort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text("Sort by ${s.label}", fontWeight = if (s == current) FontWeight.Bold else FontWeight.Normal, color = if (s == current) AC.Blue else AC.Ink) },
                    onClick = { open = false; onPick(s) }
                )
            }
        }
    }
}

@Composable
private fun AssignmentCard(c: MyCase, onOpen: () -> Unit, onScan: () -> Unit) {
    val overdue = isOverdue(c.dueDate, c.status)
    GlassCard(padding = 14.dp, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.code, color = AC.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (c.priority.isNotBlank()) {
                PriorityPill(c.priority)
                Spacer(Modifier.width(6.dp))
            }
            StatusPill(c.status)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProductTile(c.category, 44.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(c.product, color = AC.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(c.brand, c.category).filter { it.isNotBlank() }.joinToString(" • "), color = AC.Muted, fontSize = 12.sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height(10.dp))
        IconLine(AIcon.BUILDING, "${c.establishment} • ${c.city}${if (c.state.isNotBlank()) ", ${c.state}" else ""}")
        IconLine(AIcon.CALENDAR, when {
            c.status == CaseStatus.IN_PROGRESS -> "Started ${fmtDateTime(c.startedAt)}"
            c.isClosed -> "Decided ${fmtDateTime(c.completedAt)}"
            c.status == CaseStatus.UNDER_REVIEW -> "Submitted ${fmtDateTime(c.reviewSubmittedAt)}"
            parseDate(c.scheduledDate) == null -> "Not scheduled yet - start any time"
            else -> "Scheduled ${fmtSchedule(c.scheduledDate, c.scheduledTime)}"
        })
        if (c.dueDate != null && !c.isClosed) {
            IconLine(AIcon.FLAG, (if (overdue) "Overdue since " else "Due by ") + fmtDate(c.dueDate), if (overdue) AC.Red else AC.Muted)
        }

        c.assignedBy?.let { by ->
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AC.VioletSoft.copy(alpha = 0.55f)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(by.name, 32.dp, fg = AC.Violet, bg = Color.White, fontSize = 11)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Assigned by ${by.name}", color = AC.Ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(by.designation, by.officerId, by.at?.let { relativeTime(it) }).filter { !it.isNullOrBlank() }.joinToString(" • "),
                        color = AC.Muted, fontSize = 11.sp, maxLines = 1
                    )
                    if (by.note.isNotBlank()) Text("“${by.note}”", color = AC.Violet, fontSize = 11.5.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (c.scans.total > 0) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${fmtInt(c.scans.total)} packages analysed", color = AC.Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(fmtPct(c.scans.rate) + " compliant", color = rateColor(c.scans.rate), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            OutcomeBar(c.scans)
        }

        if (c.isOpen) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("Details", onOpen, Modifier.weight(1f), height = 42.dp)
                val ready = canStartToday(c)
                PrimaryButton(
                    text = when {
                        !ready -> "Opens ${fmtDateShort(c.scheduledDate)}"
                        c.status == CaseStatus.IN_PROGRESS -> "Scan package"
                        else -> "Start & scan"
                    },
                    onClick = onScan,
                    enabled = ready,
                    icon = AIcon.PLUS,
                    modifier = Modifier.weight(1.4f),
                    height = 42.dp
                )
            }
        }
    }
}

@Composable
internal fun IconLine(icon: AIcon, text: String, color: Color = AC.Muted) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        AIconView(icon, AC.Faint, Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, fontSize = 12.5.sp, lineHeight = 16.sp)
    }
}

/** Passed / failed / review proportions of a set of packages */
@Composable
internal fun OutcomeBar(t: PackageTotals, height: Dp = 8.dp) {
    Row(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(Color(0xFFE6EEF8))) {
        if (t.total > 0) {
            listOf(t.passed to AC.Green, t.failed to AC.Red, t.review to AC.Amber).forEach { (n, tone) ->
                if (n > 0) Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(tone))
            }
        }
    }
}
