package com.example.complyvision

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private sealed interface CaseDialog {
    data object Assign : CaseDialog
    data class Schedule(val reschedule: Boolean) : CaseDialog
    data object Review : CaseDialog
    data object Submit : CaseDialog
}

/**
 * One case from every angle: where it stands, who has it, when it happens and the complete
 * track record. Used by the Supreme Authority and by the assigned inspector.
 */
@Composable
internal fun CaseDetailPage(
    token: String,
    code: String,
    role: OfficerRole,
    inspectors: List<InspectorProfile>,
    bottomSpace: Dp,
    mutate: suspend (suspend () -> ActionResult) -> ActionResult,
    onToast: (message: String, isError: Boolean) -> Unit,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    onSessionExpired: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var state by remember(code) { mutableStateOf<Load<CaseDetail>>(Load.Loading) }
    var reloadKey by remember(code) { mutableIntStateOf(0) }
    var dialog by remember(code) { mutableStateOf<CaseDialog?>(null) }
    var busy by remember(code) { mutableStateOf(false) }
    val isAuthority = role == OfficerRole.SUPREME_AUTHORITY

    LaunchedEffect(code, reloadKey) {
        if (state !is Load.Ready) state = Load.Loading
        try {
            state = Load.Ready(AuthorityRepository.caseDetail(token, code))
        } catch (e: SessionExpiredException) {
            onSessionExpired()
        } catch (e: ApiException) {
            if (state !is Load.Ready) state = Load.Failed(e.userMessage())
        }
    }

    fun refresh() {
        reloadKey++
        onChanged()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundIconButton(AIcon.CHEVRON_LEFT, "Back", onBack, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Case Details", color = AC.Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text(code, color = AC.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            RoundIconButton(AIcon.REFRESH, "Refresh case", { reloadKey++ }, size = 42.dp)
        }

        when (val s = state) {
            Load.Loading -> LoadingPanel("Loading case…")
            is Load.Failed -> ErrorPanel(s.message, { reloadKey++ }, Modifier.padding(horizontal = 16.dp))
            is Load.Ready -> {
                val c = s.data
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SummaryCard(c)

                    ActionsCard(c, isAuthority, busy) { action ->
                        when (action) {
                            CaseAction.ASSIGN -> dialog = CaseDialog.Assign
                            CaseAction.REASSIGN -> dialog = CaseDialog.Assign
                            CaseAction.SCHEDULE -> dialog = CaseDialog.Schedule(false)
                            CaseAction.RESCHEDULE -> dialog = CaseDialog.Schedule(true)
                            CaseAction.REVIEW -> dialog = CaseDialog.Review
                            CaseAction.SUBMIT -> dialog = CaseDialog.Submit
                            CaseAction.START -> {
                                busy = true
                                scope.launch {
                                    when (val r = mutate { AuthorityRepository.inspectorStart(token, code) }) {
                                        is ActionResult.Done -> { onToast("Inspection started", false); refresh() }
                                        is ActionResult.Rejected -> onToast(r.message, true)
                                    }
                                    busy = false
                                }
                            }
                        }
                    }

                    PeopleAndScheduleCard(c, isAuthority)
                    ProductCard(c)
                    EstablishmentCard(c)
                    CaseInfoCard(c)
                    if (c.violations > 0 || c.remarks.isNotBlank() || c.violationTypes.isNotEmpty()) FindingsCard(c)
                    TrackRecordCard(c.events)
                }
            }
        }
        Spacer(Modifier.height(bottomSpace + 12.dp))
    }

    // Dialogs ----------------------------------------------------------------
    val detail = (state as? Load.Ready)?.data
    if (detail != null) {
        when (val d = dialog) {
            CaseDialog.Assign -> AssignDialog(
                code = code, product = detail.product, city = detail.city,
                currentInspectorId = detail.inspector?.officerId,
                inspectors = inspectors,
                onDismiss = { dialog = null },
                submit = { id, date, time, note -> mutate { AuthorityRepository.assign(token, code, id, date, time, note) } },
                onDone = { message -> dialog = null; onToast(message, false); refresh() }
            )

            is CaseDialog.Schedule -> ScheduleDialog(
                code = code, product = detail.product, reschedule = d.reschedule,
                initialDate = detail.scheduledDate, initialTime = detail.scheduledTime,
                onDismiss = { dialog = null },
                submit = { date, time, note -> mutate { AuthorityRepository.schedule(token, code, date, time, note) } },
                onDone = { message -> dialog = null; onToast(message, false); refresh() }
            )

            CaseDialog.Review -> ReviewDialog(
                code = code, product = detail.product, violations = detail.violations, violationTypes = detail.violationTypes,
                onDismiss = { dialog = null },
                submit = { verdict, remarks -> mutate { AuthorityRepository.review(token, code, verdict, remarks) } },
                onDone = { message -> dialog = null; onToast(message, false); refresh() }
            )

            CaseDialog.Submit -> SubmitFindingsDialog(
                code = code, product = detail.product,
                onDismiss = { dialog = null },
                submit = { violations, types, remarks -> mutate { AuthorityRepository.inspectorSubmit(token, code, violations, types, remarks) } },
                onDone = { message -> dialog = null; onToast(message, false); refresh() }
            )

            null -> Unit
        }
    }
}

// ---------------------------------------------------------------------------
// Summary + progress
// ---------------------------------------------------------------------------

private val Steps = listOf("Filed", "Assigned", "Scheduled", "Inspecting", "Review", "Result")

private fun stepsDone(status: String): Int = when (status) {
    CaseStatus.PENDING -> 1
    CaseStatus.ASSIGNED -> 2
    CaseStatus.SCHEDULED -> 3
    CaseStatus.IN_PROGRESS -> 4
    CaseStatus.UNDER_REVIEW -> 5
    else -> 6
}

@Composable
private fun SummaryCard(c: CaseDetail) {
    val overdue = isOverdue(c.dueDate, c.status)
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.code, color = AC.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            PriorityPill(c.priority)
            Spacer(Modifier.width(6.dp))
            StatusPill(c.status)
        }
        Spacer(Modifier.height(8.dp))
        Text(c.product, color = AC.Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 25.sp)
        Text(
            listOf(c.brand, c.category).filter { it.isNotBlank() }.joinToString(" • "),
            color = AC.Muted, fontSize = 13.sp
        )
        Spacer(Modifier.height(16.dp))
        ProgressStepper(c.status)
        if (overdue) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AC.RedSoft).padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AIconView(AIcon.CLOCK, AC.Red, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Overdue — was due ${fmtDate(c.dueDate)}", color = Color(0xFF9B2226), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ProgressStepper(status: String) {
    val done = stepsDone(status)
    val failed = status == CaseStatus.FAILED
    val circle = 24.dp
    Box(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(circle)) {
            val slot = size.width / Steps.size
            val y = size.height / 2
            val x0 = slot / 2
            val x1 = size.width - slot / 2
            drawLine(Color(0xFFDCE7F7), Offset(x0, y), Offset(x1, y), 3.dp.toPx(), StrokeCap.Round)
            val frac = ((done - 1).coerceAtLeast(0)).toFloat() / (Steps.size - 1)
            drawLine(if (failed) AC.Red else AC.Blue, Offset(x0, y), Offset(x0 + (x1 - x0) * frac, y), 3.dp.toPx(), StrokeCap.Round)
        }
        Row(Modifier.fillMaxWidth()) {
            Steps.forEachIndexed { i, label ->
                val complete = i < done
                val isNext = i == done
                val tone = if (i == Steps.lastIndex && failed) AC.Red else if (i == Steps.lastIndex && complete) AC.Green else AC.Blue
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(circle)
                            .clip(CircleShape)
                            .background(if (complete) tone else Color.White)
                            .then(
                                if (!complete) Modifier.border(if (isNext) 2.5.dp else 2.dp, if (isNext) AC.Blue else Color(0xFFCBD8EC), CircleShape)
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (complete) {
                            if (i == Steps.lastIndex && failed) AIconView(AIcon.CLOSE, Color.White, Modifier.size(12.dp))
                            else GlyphIcon(Glyph.CHECK, Color.White, Modifier.size(13.dp))
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth()) {
        Steps.forEachIndexed { i, label ->
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                color = if (i < done) AC.Ink else AC.Faint,
                fontSize = 9.5.sp,
                fontWeight = if (i < done) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------

private enum class CaseAction { ASSIGN, REASSIGN, SCHEDULE, RESCHEDULE, REVIEW, START, SUBMIT }

@Composable
private fun ActionsCard(c: CaseDetail, isAuthority: Boolean, busy: Boolean, onAction: (CaseAction) -> Unit) {
    if (isAuthority) {
        when (c.status) {
            CaseStatus.PENDING -> GlassCard(tint = Color(0xFFFFF7EC)) {
                Text("Waiting for an inspector", color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Text("Pick someone from your team and optionally set the inspection date in one go.", color = AC.Muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                PrimaryButton("Assign Inspector", { onAction(CaseAction.ASSIGN) }, Modifier.fillMaxWidth(), icon = AIcon.PEOPLE)
            }
            CaseStatus.ASSIGNED -> GlassCard {
                Text("Inspector assigned — no date yet", color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Schedule", { onAction(CaseAction.SCHEDULE) }, Modifier.weight(1f), icon = AIcon.CALENDAR)
                    SoftButton("Reassign", { onAction(CaseAction.REASSIGN) }, Modifier.weight(1f), height = 46.dp)
                }
            }
            CaseStatus.SCHEDULED -> GlassCard {
                Text("Scheduled for ${fmtSchedule(c.scheduledDate, c.scheduledTime)}", color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Reschedule", { onAction(CaseAction.RESCHEDULE) }, Modifier.weight(1f), icon = AIcon.CALENDAR)
                    SoftButton("Reassign", { onAction(CaseAction.REASSIGN) }, Modifier.weight(1f), height = 46.dp)
                }
            }
            CaseStatus.IN_PROGRESS -> InfoCard("Inspection in progress", "${c.inspector?.name ?: "The inspector"} is on site. You will be able to review the result once it is submitted.", AIcon.CLOCK, AC.Teal, AC.TealSoft)
            CaseStatus.UNDER_REVIEW -> GlassCard(tint = Color(0xFFFFF7EC)) {
                Text("Findings submitted — your decision", color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    if (c.violations == 0) "No violations were reported." else "${c.violations} violation${if (c.violations == 1) "" else "s"} reported.",
                    color = AC.Muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                )
                PrimaryButton("Review Result", { onAction(CaseAction.REVIEW) }, Modifier.fillMaxWidth(), icon = AIcon.SHIELD, colors = listOf(Color(0xFFE59A0B), Color(0xFFF5B83D)))
            }
            else -> InfoCard(
                if (c.status == CaseStatus.PASSED) "Case closed — Passed" else "Case closed — Failed",
                "Full history is kept in the track record below.",
                if (c.status == CaseStatus.PASSED) AIcon.CHECK_CIRCLE else AIcon.CROSS_CIRCLE,
                if (c.status == CaseStatus.PASSED) AC.Green else AC.Red,
                if (c.status == CaseStatus.PASSED) AC.GreenSoft else AC.RedSoft
            )
        }
    } else {
        when (c.status) {
            CaseStatus.ASSIGNED, CaseStatus.SCHEDULED -> GlassCard {
                Text(
                    if (c.status == CaseStatus.SCHEDULED) "Scheduled for ${fmtSchedule(c.scheduledDate, c.scheduledTime)}" else "Waiting for the authority to set a date",
                    color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold
                )
                Text("Start the inspection when you arrive at the premises.", color = AC.Muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                PrimaryButton("Start Inspection", { onAction(CaseAction.START) }, Modifier.fillMaxWidth(), loading = busy, icon = AIcon.CLIPBOARD)
            }
            CaseStatus.IN_PROGRESS -> GlassCard {
                Text("Inspection in progress", color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Text("Record what you found and send it to the Supreme Authority.", color = AC.Muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                PrimaryButton("Submit Findings", { onAction(CaseAction.SUBMIT) }, Modifier.fillMaxWidth(), icon = AIcon.FLAG)
            }
            CaseStatus.UNDER_REVIEW -> InfoCard("Submitted for review", "The Supreme Authority will confirm the result shortly.", AIcon.HOURGLASS, AC.Amber, AC.AmberSoft)
            else -> InfoCard(
                if (c.status == CaseStatus.PASSED) "Closed — Passed" else "Closed — Failed",
                "This inspection is complete.",
                if (c.status == CaseStatus.PASSED) AIcon.CHECK_CIRCLE else AIcon.CROSS_CIRCLE,
                if (c.status == CaseStatus.PASSED) AC.Green else AC.Red,
                if (c.status == CaseStatus.PASSED) AC.GreenSoft else AC.RedSoft
            )
        }
    }
}

@Composable
private fun InfoCard(title: String, message: String, icon: AIcon, tone: Color, soft: Color) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(soft), contentAlignment = Alignment.Center) {
                AIconView(icon, tone, Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Text(message, color = AC.Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Detail cards
// ---------------------------------------------------------------------------

@Composable
private fun CardTitle(text: String, icon: AIcon) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(AC.BlueSoft), contentAlignment = Alignment.Center) {
            AIconView(icon, AC.Blue, Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun PeopleAndScheduleCard(c: CaseDetail, isAuthority: Boolean) {
    GlassCard {
        CardTitle("Assignment & schedule", AIcon.CALENDAR)
        val ins = c.inspector
        if (ins != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(ins.name, 44.dp, fontSize = 14)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ins.name, color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("${ins.officerId} • ${ins.office}", color = AC.Muted, fontSize = 12.sp, lineHeight = 16.sp)
                    if (isAuthority && ins.phone.isNotBlank()) Text(ins.phone, color = AC.Faint, fontSize = 12.sp)
                }
                if (ins.status != InspectorStatus.ACTIVE) {
                    val (fg, bg) = inspectorStatusStyle(ins.status)
                    Pill(inspectorStatusLabel(ins.status), fg, bg, size = 10)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(AC.OrangeSoft), contentAlignment = Alignment.Center) {
                    AIconView(AIcon.USER, AC.Orange, Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text("No inspector assigned yet", color = AC.Orange, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        HorizontalRule(Modifier.padding(vertical = 14.dp))
        Row(Modifier.fillMaxWidth()) {
            LabelValue("Inspection", fmtSchedule(c.scheduledDate, c.scheduledTime), Modifier.weight(1f))
            LabelValue(
                "Due by", fmtDate(c.dueDate), Modifier.weight(1f),
                valueColor = if (isOverdue(c.dueDate, c.status)) AC.Red else AC.Ink
            )
        }
        if (c.authority.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            LabelValue("Routed to", c.authority)
        }
    }
}

@Composable
private fun ProductCard(c: CaseDetail) {
    GlassCard {
        CardTitle("Product", AIcon.BOX)
        Row(Modifier.fillMaxWidth()) {
            LabelValue("Brand", c.brand, Modifier.weight(1f))
            LabelValue("Category", c.category, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            LabelValue("Manufacturer", c.manufacturer, Modifier.weight(1f))
            LabelValue("Batch no.", c.batchNo, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        LabelValue("MRP", c.mrp?.let { "₹" + java.text.DecimalFormat("#,##0.##").format(it) } ?: "—")
    }
}

@Composable
private fun EstablishmentCard(c: CaseDetail) {
    GlassCard {
        CardTitle("Establishment", AIcon.PIN)
        Text(c.establishment, color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(
            listOf(c.address, c.city, c.state).filter { it.isNotBlank() }.joinToString(", "),
            color = AC.Muted, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp)
        )
        if (c.establishmentType.isNotBlank()) Pill(c.establishmentType, AC.Blue, AC.BlueSoft, Modifier.padding(top = 10.dp), size = 10)
    }
}

@Composable
private fun CaseInfoCard(c: CaseDetail) {
    GlassCard {
        CardTitle("About this case", AIcon.DOC)
        Row(Modifier.fillMaxWidth()) {
            LabelValue("Inspection type", c.type, Modifier.weight(1f))
            LabelValue("Source", c.source, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        LabelValue("Filed on", fmtDateTime(c.submittedAt))
        if (c.complaint.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text("Reason for inspection", color = AC.Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(
                c.complaint, color = AC.Ink, fontSize = 13.5.sp, lineHeight = 19.sp,
                modifier = Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFFF4F8FE)).padding(12.dp)
            )
        }
    }
}

@Composable
private fun FindingsCard(c: CaseDetail) {
    GlassCard {
        CardTitle("Inspection findings", AIcon.FLAG)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.violations.toString(), color = if (c.violations > 0) AC.Red else AC.Green, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(8.dp))
            Text(if (c.violations == 1) "violation recorded" else "violations recorded", color = AC.Muted, fontSize = 13.sp)
        }
        if (c.violationTypes.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                c.violationTypes.forEach { Pill(it, AC.Red, AC.RedSoft, size = 11) }
            }
        }
        if (c.remarks.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text("Remarks", color = AC.Faint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(c.remarks, color = AC.Ink, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Track record
// ---------------------------------------------------------------------------

private fun eventTone(type: String, title: String): Color = when (type) {
    "Result" -> if (title.contains("passed", ignoreCase = true)) AC.Green else AC.Red
    "Review" -> AC.Amber
    "Started" -> AC.Teal
    "Scheduled", "Rescheduled" -> AC.Violet
    "Assigned", "Reassigned" -> AC.Blue
    "Returned" -> AC.Orange
    else -> AC.Slate
}

@Composable
private fun TrackRecordCard(events: List<CaseEvent>) {
    GlassCard {
        CardTitle("Track record", AIcon.CLOCK)
        if (events.isEmpty()) {
            Text("No activity recorded yet.", color = AC.Muted, fontSize = 13.sp)
            return@GlassCard
        }
        events.forEachIndexed { index, e ->
            val last = index == events.lastIndex
            val tone = eventTone(e.type, e.title)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Column(Modifier.width(26.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.padding(top = 3.dp).size(if (last) 16.dp else 12.dp).clip(CircleShape).background(tone)
                    )
                    if (!last) Box(Modifier.weight(1f).width(2.dp).background(AC.Line))
                }
                Column(Modifier.weight(1f).padding(bottom = if (last) 0.dp else 16.dp, start = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(e.title, color = AC.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
                        if (last) Pill("Latest", tone, tone.copy(alpha = 0.14f), Modifier.padding(start = 8.dp), size = 9)
                    }
                    if (e.detail.isNotBlank()) {
                        Text(e.detail, color = AC.Muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 1.dp))
                    }
                    Text(
                        listOf(e.actor, e.role).filter { it.isNotBlank() }.distinct().joinToString(" • ")
                            .let { if (it.isBlank()) fmtDateTime(e.at) else "$it  |  ${fmtDateTime(e.at)}" },
                        color = AC.Faint, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
