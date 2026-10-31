package com.example.complyvision

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val ALL = "All"
private val FilterOrder = listOf(
    CaseStatus.PENDING, CaseStatus.UNDER_REVIEW, CaseStatus.ASSIGNED, CaseStatus.SCHEDULED,
    CaseStatus.IN_PROGRESS, CaseStatus.PASSED, CaseStatus.FAILED, ALL
)
private val SortOptions = listOf("Priority", "Newest first", "Due soon", "Inspection date")

private fun priorityRank(p: String) = when (p) { "Critical" -> 0; "High" -> 1; "Medium" -> 2; else -> 3 }

private sealed interface ListDialog {
    data class Assign(val case: CaseItem) : ListDialog
    data class Schedule(val case: CaseItem) : ListDialog
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AuthorityInspectionsTab(
    store: AuthorityStore,
    filter: String,
    onFilter: (String) -> Unit,
    bottomSpace: Dp,
    onOpenCase: (String) -> Unit
) {
    when (val state = store.cases) {
        Load.Loading -> Column { PageHeader(); LoadingPanel("Loading your cases…") }
        is Load.Failed -> Column { PageHeader(); ErrorPanel(state.message, { store.loadCases() }, Modifier.padding(horizontal = 16.dp)) }
        is Load.Ready -> {
            val data = state.data
            var query by rememberSaveable { mutableStateOf("") }
            var sort by rememberSaveable { mutableStateOf(SortOptions.first()) }
            var dialog by remember { mutableStateOf<ListDialog?>(null) }

            val shown = remember(data.items, filter, query, sort) {
                val q = query.trim().lowercase()
                val base = data.items.filter { item ->
                    (filter == ALL || item.status == filter) &&
                        (q.isEmpty() || listOf(item.code, item.product, item.brand, item.establishment, item.city, item.state, item.inspector, item.category)
                            .any { it.lowercase().contains(q) })
                }
                when (sort) {
                    "Newest first" -> base.sortedByDescending { it.submittedAt }
                    "Due soon" -> base.sortedWith(compareBy<CaseItem> { it.dueDate ?: "9999" }.thenBy { priorityRank(it.priority) })
                    "Inspection date" -> base.sortedWith(compareBy<CaseItem> { it.scheduledDate ?: "9999" }.thenBy { it.scheduledTime ?: "" })
                    else -> base.sortedWith(compareBy<CaseItem> { priorityRank(it.priority) }.thenBy { it.dueDate ?: "9999" })
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = bottomSpace + 12.dp)
            ) {
                item { PageHeader() }
                item { ActionCenter(data.counts, onFilter) }

                stickyHeader {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(AC.PageMid, AC.PageMid, AC.PageMid.copy(alpha = 0f))))
                            .padding(top = 4.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SearchBox(query, { query = it }, "Search code, product, shop, city or inspector", Modifier.fillMaxWidth())
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterOrder.forEach { f ->
                                FilterPill(f, filter == f, { onFilter(f) }, count = data.counts.forStatus(f))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
                            Text(
                                "${fmtInt(shown.size)} case${if (shown.size == 1) "" else "s"}",
                                color = AC.Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                            )
                            SortPicker(sort) { sort = it }
                        }
                    }
                }

                if (shown.isEmpty()) {
                    item {
                        EmptyPanel(
                            title = if (query.isNotBlank()) "No matching cases" else "Nothing here",
                            message = when {
                                query.isNotBlank() -> "Try a different search or switch the status filter."
                                filter == CaseStatus.PENDING -> "Every case routed to you has an inspector. New website submissions will appear here."
                                else -> "No cases with status \"$filter\" right now."
                            }
                        )
                    }
                } else {
                    items(shown, key = { it.code }) { item ->
                        CaseCard(
                            item = item,
                            onOpen = { onOpenCase(item.code) },
                            onAssign = { dialog = ListDialog.Assign(item) },
                            onSchedule = { dialog = ListDialog.Schedule(item) }
                        )
                    }
                }
            }

            when (val d = dialog) {
                is ListDialog.Assign -> AssignDialog(
                    code = d.case.code, product = d.case.product, city = d.case.city,
                    currentInspectorId = d.case.inspectorId.ifBlank { null },
                    inspectors = (store.inspectors as? Load.Ready)?.data.orEmpty(),
                    onDismiss = { dialog = null },
                    submit = { id, date, time, note ->
                        store.mutate { AuthorityRepository.assign(store.session.token, d.case.code, id, date, time, note) }
                    },
                    onDone = { message -> dialog = null; store.showToast(message) }
                )

                is ListDialog.Schedule -> ScheduleDialog(
                    code = d.case.code, product = d.case.product,
                    reschedule = d.case.scheduledDate != null,
                    initialDate = d.case.scheduledDate, initialTime = d.case.scheduledTime,
                    onDismiss = { dialog = null },
                    submit = { date, time, note ->
                        store.mutate { AuthorityRepository.schedule(store.session.token, d.case.code, date, time, note) }
                    },
                    onDone = { message -> dialog = null; store.showToast(message) }
                )

                null -> Unit
            }
        }
    }
}

@Composable
private fun PageHeader() {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
        Text("Inspections", color = AC.Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
        Text(
            "Cases routed to you from the Legal Metrology portal. Assign, schedule and follow every step.",
            color = AC.Muted, fontSize = 13.sp, lineHeight = 18.sp
        )
    }
}

// ---------------------------------------------------------------------------
// Action center: what needs the authority right now
// ---------------------------------------------------------------------------

@Composable
private fun ActionCenter(counts: CaseCounts, onFilter: (String) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF0B2E59), Color(0xFF1463F3))))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Needs your attention", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "${fmtInt(counts.total)} cases in total • ${fmtInt(counts.assigned + counts.scheduled + counts.inProgress)} in the field",
                    color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AttentionTile("To assign", counts.pending, AC.Orange, Modifier.weight(1f)) { onFilter(CaseStatus.PENDING) }
            AttentionTile("To review", counts.review, AC.Amber, Modifier.weight(1f)) { onFilter(CaseStatus.UNDER_REVIEW) }
            AttentionTile("Overdue", counts.overdue, AC.Red, Modifier.weight(1f)) { onFilter(CaseStatus.PENDING) }
        }
    }
}

@Composable
private fun AttentionTile(label: String, value: Int, tone: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (value > 0) tone else Color.White.copy(alpha = 0.4f)))
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(fmtInt(value), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun SortPicker(sort: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .clickable(role = Role.Button) { open = true }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AIconView(AIcon.SORT, AC.Blue, Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(sort, color = AC.Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(2.dp))
            AIconView(AIcon.CHEVRON_DOWN, AC.Muted, Modifier.size(12.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortOptions.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option,
                            fontWeight = if (option == sort) FontWeight.Bold else FontWeight.Normal,
                            color = if (option == sort) AC.Blue else AC.Ink
                        )
                    },
                    onClick = { open = false; onPick(option) }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Case card
// ---------------------------------------------------------------------------

@Composable
private fun CaseCard(item: CaseItem, onOpen: () -> Unit, onAssign: () -> Unit, onSchedule: () -> Unit) {
    val (priorityFg, _) = priorityStyle(item.priority)
    val overdue = isOverdue(item.dueDate, item.status)
    val shape = RoundedCornerShape(22.dp)

    GlassCard(padding = 0.dp, corner = 22.dp, onClick = onOpen) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(priorityFg))
            Column(Modifier.weight(1f).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.code, color = AC.Muted, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    PriorityPill(item.priority)
                    Spacer(Modifier.width(6.dp))
                    StatusPill(item.status)
                }
                Spacer(Modifier.height(8.dp))
                Text(item.product, color = AC.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(item.brand, item.category).filter { it.isNotBlank() }.joinToString(" • "),
                    color = AC.Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AIconView(AIcon.PIN, AC.Faint, Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "${item.establishment}, ${item.city}",
                        color = AC.Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }

                HorizontalRule(Modifier.padding(vertical = 12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Who has it
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        if (item.inspector.isNotBlank()) {
                            Avatar(item.inspector, 28.dp, fontSize = 10)
                            Spacer(Modifier.width(7.dp))
                            Column {
                                Text(item.inspector, color = AC.Ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.inspectorId, color = AC.Faint, fontSize = 10.5.sp)
                            }
                        } else {
                            Box(Modifier.size(28.dp).clip(CircleShape).background(AC.OrangeSoft), contentAlignment = Alignment.Center) {
                                AIconView(AIcon.USER, AC.Orange, Modifier.size(15.dp))
                            }
                            Spacer(Modifier.width(7.dp))
                            Text("Unassigned", color = AC.Orange, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    // When
                    Column(horizontalAlignment = Alignment.End) {
                        if (item.scheduledDate != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AIconView(AIcon.CALENDAR, AC.Violet, Modifier.size(13.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(fmtSchedule(item.scheduledDate, item.scheduledTime), color = AC.Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        } else if (item.status == CaseStatus.PASSED || item.status == CaseStatus.FAILED) {
                            Text("Closed", color = AC.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (item.dueDate != null && item.status != CaseStatus.PASSED && item.status != CaseStatus.FAILED) {
                            Text(
                                (if (overdue) "Overdue • " else "Due ") + fmtDateShort(item.dueDate),
                                color = if (overdue) AC.Red else AC.Faint, fontSize = 11.sp,
                                fontWeight = if (overdue) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                        if (item.violations > 0) {
                            Text("${item.violations} violation${if (item.violations == 1) "" else "s"}", color = AC.Red, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                CardActions(item, onOpen, onAssign, onSchedule)
            }
        }
    }
}

@Composable
private fun CardActions(item: CaseItem, onOpen: () -> Unit, onAssign: () -> Unit, onSchedule: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        when (item.status) {
            CaseStatus.PENDING -> {
                PrimaryButton("Assign Inspector", onAssign, Modifier.weight(1f), icon = AIcon.PEOPLE, height = 40.dp)
                SoftButton("Details", onOpen, height = 40.dp)
            }
            CaseStatus.ASSIGNED -> {
                PrimaryButton("Schedule", onSchedule, Modifier.weight(1f), icon = AIcon.CALENDAR, height = 40.dp)
                SoftButton("Reassign", onAssign, height = 40.dp)
            }
            CaseStatus.SCHEDULED -> {
                SoftButton("Reschedule", onSchedule, Modifier.weight(1f), icon = AIcon.CALENDAR, height = 40.dp)
                SoftButton("Reassign", onAssign, height = 40.dp)
            }
            CaseStatus.UNDER_REVIEW -> {
                PrimaryButton("Review Result", onOpen, Modifier.weight(1f), icon = AIcon.SHIELD, height = 40.dp, colors = listOf(Color(0xFFE59A0B), Color(0xFFF5B83D)))
                SoftButton("Track", onOpen, height = 40.dp)
            }
            else -> SoftButton("Track Record", onOpen, Modifier.fillMaxWidth(), icon = AIcon.CLOCK, height = 40.dp)
        }
    }
}
