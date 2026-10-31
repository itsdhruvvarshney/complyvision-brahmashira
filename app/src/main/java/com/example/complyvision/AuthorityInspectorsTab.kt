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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private const val F_ALL = "All"
private const val F_ACTIVE = "Active"
private const val F_HOLD = "On Hold"
private const val F_SUSPENDED = "Suspended"
private val InspectorFilters = listOf(F_ALL, F_ACTIVE, F_HOLD, F_SUSPENDED)
private val InspectorSorts = listOf("Name", "Pass rate", "Workload", "Total inspections")

private fun matchesFilter(status: String, filter: String) = when (filter) {
    F_ACTIVE -> status == InspectorStatus.ACTIVE
    F_HOLD -> status == InspectorStatus.DETAINED
    F_SUSPENDED -> status == InspectorStatus.SUSPENDED
    else -> true
}

// ============================================================================
// List
// ============================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AuthorityInspectorsTab(store: AuthorityStore, bottomSpace: Dp, onOpen: (String) -> Unit) {
    when (val state = store.inspectors) {
        Load.Loading -> Column { InspectorsHeader(); LoadingPanel("Loading your team…") }
        is Load.Failed -> Column { InspectorsHeader(); ErrorPanel(state.message, { store.loadInspectors() }, Modifier.padding(horizontal = 16.dp)) }
        is Load.Ready -> {
            val all = state.data
            var query by rememberSaveable { mutableStateOf("") }
            var filter by rememberSaveable { mutableStateOf(F_ALL) }
            var sort by rememberSaveable { mutableStateOf(InspectorSorts.first()) }

            val shown = remember(all, query, filter, sort) {
                val q = query.trim().lowercase()
                val base = all.filter {
                    matchesFilter(it.status, filter) &&
                        (q.isEmpty() || listOf(it.name, it.officerId, it.office, it.specialization).any { f -> f.lowercase().contains(q) })
                }
                when (sort) {
                    "Pass rate" -> base.sortedByDescending { it.passRate }
                    "Workload" -> base.sortedByDescending { it.open }
                    "Total inspections" -> base.sortedByDescending { it.total }
                    else -> base.sortedBy { it.name }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = bottomSpace + 12.dp)
            ) {
                item { InspectorsHeader() }
                item { TeamSummary(all) { filter = it } }

                stickyHeader {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(AC.PageMid, AC.PageMid, AC.PageMid.copy(alpha = 0f))))
                            .padding(top = 4.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SearchBox(query, { query = it }, "Search name, ID, office or specialty", Modifier.fillMaxWidth())
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            InspectorFilters.forEach { f ->
                                FilterPill(f, filter == f, { filter = f }, count = all.count { matchesFilter(it.status, f) })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
                            Text(
                                "${shown.size} inspector${if (shown.size == 1) "" else "s"}",
                                color = AC.Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                            )
                            InspectorSortPicker(sort) { sort = it }
                        }
                    }
                }

                if (shown.isEmpty()) {
                    item {
                        EmptyPanel(
                            "No inspectors found",
                            if (all.isEmpty()) "Inspectors assigned to you will appear here." else "Try a different search or filter.",
                            AIcon.PEOPLE
                        )
                    }
                } else {
                    items(shown, key = { it.officerId }) { InspectorCard(it) { onOpen(it.officerId) } }
                }
            }
        }
    }
}

@Composable
private fun InspectorsHeader() {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
        Text("Inspectors", color = AC.Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
        Text(
            "Everyone reporting to you: performance, workload and conduct. Place an inspector on hold or suspend them when needed.",
            color = AC.Muted, fontSize = 13.sp, lineHeight = 18.sp
        )
    }
}

@Composable
private fun TeamSummary(all: List<InspectorProfile>, onFilter: (String) -> Unit) {
    val active = all.count { it.status == InspectorStatus.ACTIVE }
    val hold = all.count { it.status == InspectorStatus.DETAINED }
    val suspended = all.count { it.status == InspectorStatus.SUSPENDED }
    val avgRate = all.filter { it.total > 0 }.let { l -> if (l.isEmpty()) 0.0 else l.sumOf { it.passRate } / l.size }
    val open = all.sumOf { it.open }
    GlassCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Team of ${all.size}", color = AC.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                Text("${fmtInt(open)} cases in the field • avg pass rate ${fmtPct(avgRate)}", color = AC.Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TeamTile("Active", active, AC.Green, AC.GreenSoft, Modifier.weight(1f)) { onFilter(F_ACTIVE) }
            TeamTile("On hold", hold, AC.Amber, AC.AmberSoft, Modifier.weight(1f)) { onFilter(F_HOLD) }
            TeamTile("Suspended", suspended, AC.Red, AC.RedSoft, Modifier.weight(1f)) { onFilter(F_SUSPENDED) }
        }
    }
}

@Composable
private fun TeamTile(label: String, value: Int, fg: Color, bg: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(fmtInt(value), color = fg, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = fg.copy(alpha = 0.85f), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun InspectorSortPicker(sort: String, onPick: (String) -> Unit) {
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
            InspectorSorts.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, fontWeight = if (option == sort) FontWeight.Bold else FontWeight.Normal, color = if (option == sort) AC.Blue else AC.Ink) },
                    onClick = { open = false; onPick(option) }
                )
            }
        }
    }
}

@Composable
private fun InspectorCard(p: InspectorProfile, onClick: () -> Unit) {
    val (fg, bg) = inspectorStatusStyle(p.status)
    val tone = rateColor(p.passRate)
    GlassCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.name, 48.dp, fg, bg, fontSize = 15)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, color = AC.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (p.status != InspectorStatus.ACTIVE) Pill(inspectorStatusLabel(p.status), fg, bg, Modifier.padding(start = 8.dp), size = 9)
                }
                Text("${p.officerId} • ${p.city.ifBlank { p.office }}", color = AC.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.specialization.isNotBlank()) Text(p.specialization, color = AC.Faint, fontSize = 11.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (p.total == 0) "—" else fmtPct(p.passRate), color = if (p.total == 0) AC.Faint else tone, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text("pass rate", color = AC.Faint, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        ProgressTrack(if (p.total == 0) 0f else (p.passRate / 100).toFloat(), tone)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            MiniKpi("Inspections", p.total, AC.Ink, Modifier.weight(1f))
            MiniKpi("Passed", p.passed, AC.Green, Modifier.weight(1f))
            MiniKpi("Failed", p.failed, AC.Red, Modifier.weight(1f))
            MiniKpi("In field", p.open, AC.Blue, Modifier.weight(1f))
        }
        if (p.status != InspectorStatus.ACTIVE && p.statusReason.isNotBlank()) {
            Text(
                text = p.statusReason, color = fg, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Last active ${relativeTime(p.lastLoginAt)}", color = AC.Faint, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
            Text("View profile", color = AC.Blue, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            AIconView(AIcon.ARROW_RIGHT, AC.Blue, Modifier.size(14.dp))
        }
    }
}

@Composable
private fun MiniKpi(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(fmtInt(value), color = color, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = AC.Muted, fontSize = 10.5.sp, maxLines = 1)
    }
}

// ============================================================================
// Detail
// ============================================================================

private val DetainReasons = listOf("Delayed inspections", "Incomplete reports", "Quality concerns", "Pending inquiry")
private val SuspendReasons = listOf("Misconduct", "Falsified records", "Unauthorised absence", "Repeated failures")

@Composable
internal fun InspectorDetailPage(
    store: AuthorityStore,
    officerId: String,
    bottomSpace: Dp,
    onBack: () -> Unit,
    onOpenCase: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val profile = (store.inspectors as? Load.Ready)?.data?.firstOrNull { it.officerId == officerId }
    var detail by remember(officerId) { mutableStateOf<Load<InspectorDetail>>(Load.Loading) }
    var reload by remember(officerId) { mutableIntStateOf(0) }
    var statusDialog by remember(officerId) { mutableStateOf<String?>(null) }

    // Reload the extra detail whenever the inspector's numbers or status move
    LaunchedEffect(officerId, reload, profile?.status, profile?.open, profile?.total) {
        if (detail !is Load.Ready) detail = Load.Loading
        try {
            detail = Load.Ready(store.inspectorDetail(officerId))
        } catch (e: SessionExpiredException) {
            // the store already signs the officer out
        } catch (e: ApiException) {
            if (detail !is Load.Ready) detail = Load.Failed(e.userMessage())
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundIconButton(AIcon.CHEVRON_LEFT, "Back", onBack, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Inspector Profile", color = AC.Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text(officerId, color = AC.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            RoundIconButton(AIcon.REFRESH, "Refresh", { store.loadInspectors(silent = true); reload++ }, size = 42.dp)
        }

        if (profile == null) {
            when (val s = store.inspectors) {
                is Load.Failed -> ErrorPanel(s.message, { store.loadInspectors() }, Modifier.padding(horizontal = 16.dp))
                else -> if (s is Load.Ready) EmptyPanel("Inspector not found", "This officer no longer reports to you.", AIcon.PEOPLE) else LoadingPanel("Loading profile…")
            }
        } else {
            val d = (detail as? Load.Ready)?.data
            Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfileHero(profile, d?.actions?.firstOrNull())
                PerformanceSection(profile, d)
                ContactSection(profile)

                GlassCard {
                    SectionHeading("Current workload", AIcon.CLIPBOARD)
                    when (val s = detail) {
                        Load.Loading -> LoadingPanel(modifier = Modifier.padding(vertical = 0.dp))
                        is Load.Failed -> Text(s.message, color = AC.Red, fontSize = 13.sp)
                        is Load.Ready -> if (s.data.openCases.isEmpty()) {
                            Text("No active cases. Free to take new assignments.", color = AC.Muted, fontSize = 13.sp)
                        } else {
                            s.data.openCases.forEachIndexed { i, c ->
                                if (i > 0) HorizontalRule(Modifier.padding(vertical = 10.dp))
                                MiniCaseRow(c, onOpenCase)
                            }
                        }
                    }
                }

                (detail as? Load.Ready)?.data?.let { det ->
                    if (det.recent.isNotEmpty()) {
                        GlassCard {
                            SectionHeading("Recent results", AIcon.CHECK_CIRCLE)
                            det.recent.forEachIndexed { i, c ->
                                if (i > 0) HorizontalRule(Modifier.padding(vertical = 10.dp))
                                MiniCaseRow(c, onOpenCase)
                            }
                        }
                    }
                    GlassCard {
                        SectionHeading("Conduct history", AIcon.FLAG)
                        if (det.actions.isEmpty()) {
                            Text("No disciplinary action on record.", color = AC.Muted, fontSize = 13.sp)
                        } else {
                            det.actions.forEachIndexed { i, a ->
                                if (i > 0) HorizontalRule(Modifier.padding(vertical = 10.dp))
                                ActionLogRow(a)
                            }
                        }
                    }
                }

                ConductActions(profile) { statusDialog = it }
            }
        }
        Spacer(Modifier.height(bottomSpace + 12.dp))
    }

    val target = statusDialog
    if (target != null && profile != null) {
        StatusChangeDialog(
            profile = profile,
            target = target,
            onDismiss = { statusDialog = null },
            submit = { reason ->
                store.mutate { AuthorityRepository.setInspectorStatus(store.session.token, profile.officerId, target, reason) }
            },
            onDone = { message ->
                statusDialog = null
                store.showToast(message)
                reload++
            }
        )
    }
}

@Composable
private fun SectionHeading(text: String, icon: AIcon) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(AC.BlueSoft), contentAlignment = Alignment.Center) {
            AIconView(icon, AC.Blue, Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun ProfileHero(p: InspectorProfile, latestAction: ActionLog?) {
    val (fg, bg) = inspectorStatusStyle(p.status)
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.name, 68.dp, fg, bg, fontSize = 22)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, color = AC.Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 24.sp)
                Text("Metrology Inspector • ${p.officerId}", color = AC.Muted, fontSize = 12.5.sp)
                Text(p.office, color = AC.Faint, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(inspectorStatusLabel(p.status), fg, bg)
            if (p.specialization.isNotBlank()) Pill(p.specialization, AC.Blue, AC.BlueSoft)
        }
        if (p.status != InspectorStatus.ACTIVE) {
            Column(
                Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg).padding(12.dp)
            ) {
                Text(
                    if (p.status == InspectorStatus.SUSPENDED) "Suspended — cannot sign in" else "On hold — no new cases can be assigned",
                    color = fg, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold
                )
                if (p.statusReason.isNotBlank()) Text(p.statusReason, color = AC.Ink, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp))
                Text(
                    "Since ${fmtDate(p.statusChangedAt)}" + (latestAction?.by?.takeIf { it.isNotBlank() }?.let { " • by $it" } ?: ""),
                    color = AC.Muted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PerformanceSection(p: InspectorProfile, d: InspectorDetail?) {
    GlassCard {
        SectionHeading("Performance", AIcon.CHART)
        Row(verticalAlignment = Alignment.CenterVertically) {
            DonutChart(
                values = listOf(p.passed.toFloat(), p.failed.toFloat(), p.review.toFloat()),
                colors = listOf(AC.Green, AC.Red, AC.Amber),
                modifier = Modifier.size(104.dp), thickness = 15.dp
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (p.total == 0) "—" else fmtPct(p.passRate), color = AC.Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Text("pass rate", color = AC.Muted, fontSize = 9.5.sp)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LegendLine("Passed", p.passed, AC.Green)
                LegendLine("Failed", p.failed, AC.Red)
                LegendLine("Under review", p.review, AC.Amber)
            }
        }
        HorizontalRule(Modifier.padding(vertical = 14.dp))
        Row(Modifier.fillMaxWidth()) {
            LabelValue("Inspections", fmtInt(p.total), Modifier.weight(1f))
            LabelValue("In the field", fmtInt(p.open), Modifier.weight(1f))
            LabelValue("Violations found", fmtInt(p.violations), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            LabelValue("Avg. turnaround", p.avgDays?.let { String.format(java.util.Locale.US, "%.1f days", it) } ?: "—", Modifier.weight(1f))
            LabelValue("Last active", relativeTime(p.lastLoginAt), Modifier.weight(2f))
        }
        if (d != null && d.monthly.isNotEmpty()) {
            HorizontalRule(Modifier.padding(vertical = 14.dp))
            Text("Monthly compliance rate", color = AC.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            RateBars(d.monthly, Modifier.fillMaxWidth().height(90.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                d.monthly.forEach {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(it.label, color = AC.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (it.total == 0) "—" else "${it.total}", color = AC.Faint, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendLine(label: String, value: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, color = AC.Muted, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
        Text(fmtInt(value), color = AC.Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun ContactSection(p: InspectorProfile) {
    GlassCard {
        SectionHeading("Contact & service", AIcon.ID)
        ContactLine(AIcon.PHONE, "Phone", p.phone)
        Spacer(Modifier.height(10.dp))
        ContactLine(AIcon.MAIL, "Email", p.email)
        Spacer(Modifier.height(10.dp))
        ContactLine(AIcon.BUILDING, "Office", p.office)
        Spacer(Modifier.height(10.dp))
        ContactLine(AIcon.CALENDAR, "In service since", fmtMonthYear(p.employeeSince).takeIf { p.employeeSince != null } ?: "")
    }
}

@Composable
private fun ContactLine(icon: AIcon, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xFFF1F6FE)), contentAlignment = Alignment.Center) {
            AIconView(icon, AC.Blue, Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, color = AC.Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(value.ifBlank { "—" }, color = AC.Ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun MiniCaseRow(c: MiniCase, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onOpen(c.code) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(c.product, color = AC.Ink, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${c.code} • ${c.establishment}, ${c.city}", color = AC.Muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = when {
                c.completedAt != null -> "Closed ${fmtDateShort(c.completedAt)}" + if (c.violations > 0) " • ${c.violations} violation${if (c.violations == 1) "" else "s"}" else ""
                c.scheduledDate != null -> "Inspection ${fmtSchedule(c.scheduledDate, c.scheduledTime)}"
                else -> "Not scheduled yet"
            }
            Text(line, color = AC.Faint, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        StatusPill(c.status)
    }
}

@Composable
private fun ActionLogRow(a: ActionLog) {
    val (tone, soft) = when (a.action) {
        "Suspended" -> AC.Red to AC.RedSoft
        "Detained" -> AC.Amber to AC.AmberSoft
        else -> AC.Green to AC.GreenSoft
    }
    Row(verticalAlignment = Alignment.Top) {
        Pill(when (a.action) { "Detained" -> "Put on hold"; else -> a.action }, tone, soft, size = 10)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            if (a.reason.isNotBlank()) Text(a.reason, color = AC.Ink, fontSize = 13.sp, lineHeight = 18.sp)
            Text(
                listOf(a.by.takeIf { it.isNotBlank() }?.let { "by $it" }, fmtDateTime(a.at)).filterNotNull().joinToString(" • "),
                color = AC.Faint, fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ConductActions(p: InspectorProfile, onAction: (String) -> Unit) {
    GlassCard {
        SectionHeading("Take action", AIcon.SHIELD)
        when (p.status) {
            InspectorStatus.ACTIVE -> {
                Text(
                    "If this inspector's work is not up to standard you can stop new assignments or block their access completely.",
                    color = AC.Muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 12.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton("Put on hold", { onAction(InspectorStatus.DETAINED) }, Modifier.weight(1f), AC.Amber, AC.AmberSoft, AIcon.PAUSE, height = 46.dp)
                    SoftButton("Suspend", { onAction(InspectorStatus.SUSPENDED) }, Modifier.weight(1f), AC.Red, AC.RedSoft, AIcon.BAN, height = 46.dp)
                }
            }
            InspectorStatus.DETAINED -> {
                Text("Currently on hold: new cases cannot be assigned. Reinstate when the inquiry is closed, or escalate to a suspension.", color = AC.Muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Reinstate", { onAction(InspectorStatus.ACTIVE) }, Modifier.weight(1f), icon = AIcon.CHECK_CIRCLE, colors = listOf(Color(0xFF15A34A), Color(0xFF34C77B)))
                    SoftButton("Suspend", { onAction(InspectorStatus.SUSPENDED) }, Modifier.weight(1f), AC.Red, AC.RedSoft, AIcon.BAN, height = 46.dp)
                }
            }
            else -> {
                Text("This inspector is suspended and cannot sign in. Reinstating restores their access; their old cases stay in the pending queue.", color = AC.Muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 12.dp))
                PrimaryButton("Reinstate inspector", { onAction(InspectorStatus.ACTIVE) }, Modifier.fillMaxWidth(), icon = AIcon.CHECK_CIRCLE, colors = listOf(Color(0xFF15A34A), Color(0xFF34C77B)))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Detain / suspend / reinstate
// ---------------------------------------------------------------------------

@Composable
private fun StatusChangeDialog(
    profile: InspectorProfile,
    target: String,
    onDismiss: () -> Unit,
    submit: suspend (reason: String) -> ActionResult,
    onDone: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val reinstating = target == InspectorStatus.ACTIVE
    val suspending = target == InspectorStatus.SUSPENDED
    val tone = if (reinstating) AC.Green else if (suspending) AC.Red else AC.Amber
    val soft = if (reinstating) AC.GreenSoft else if (suspending) AC.RedSoft else AC.AmberSoft
    val title = when { reinstating -> "Reinstate ${profile.name}?"; suspending -> "Suspend ${profile.name}?"; else -> "Put ${profile.name} on hold?" }
    val impact = when {
        reinstating -> "Access and new assignments are restored immediately."
        suspending -> "They are signed out and cannot log in. " +
            if (profile.open > 0) "${profile.open} unfinished case${if (profile.open == 1) "" else "s"} will go back to your pending queue so ${if (profile.open == 1) "it" else "they"} can be reassigned."
            else "They have no unfinished cases."
        else -> "They keep their current cases but cannot be assigned anything new until you reinstate them."
    }
    val ready = reinstating || reason.trim().length >= 5

    SheetDialog(
        title = title,
        subtitle = "${profile.officerId} • ${profile.office}",
        busy = busy,
        onDismiss = onDismiss,
        footer = {
            SoftButton("Cancel", onDismiss, Modifier.weight(1f), fg = AC.Muted, bg = AC.SlateSoft, enabled = !busy, height = 46.dp)
            PrimaryButton(
                text = when { reinstating -> "Reinstate"; suspending -> "Suspend"; else -> "Put on hold" },
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        val result = submit(reason)
                        busy = false
                        when (result) {
                            is ActionResult.Done -> onDone(
                                when {
                                    reinstating -> "${profile.name} reinstated"
                                    suspending -> "${profile.name} suspended" + if (result.released > 0) " • ${result.released} case(s) returned to pending" else ""
                                    else -> "${profile.name} put on hold"
                                }
                            )
                            is ActionResult.Rejected -> error = result.message
                        }
                    }
                },
                modifier = Modifier.weight(1.6f), enabled = ready, loading = busy,
                colors = when { reinstating -> listOf(Color(0xFF15A34A), Color(0xFF34C77B)); suspending -> listOf(Color(0xFFE5484D), Color(0xFFF2777B)); else -> listOf(Color(0xFFE59A0B), Color(0xFFF5B83D)) }
            )
        }
    ) {
        Text(
            impact, color = AC.Ink, fontSize = 13.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(soft).padding(12.dp)
        )
        if (!reinstating) {
            Text("Reason (kept in the inspector's record)", color = AC.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (if (suspending) SuspendReasons else DetainReasons).forEach { r ->
                    FilterPill(r, reason == r, { reason = r })
                }
            }
            Spacer(Modifier.height(10.dp))
            NoteField(reason, { reason = it }, "Describe what happened (at least 5 characters)")
        } else {
            Text("Note (optional)", color = AC.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
            NoteField(reason, { reason = it }, "e.g. Inquiry closed, cleared to resume")
        }
        InlineError(error)
        Spacer(Modifier.height(4.dp))
    }
}
