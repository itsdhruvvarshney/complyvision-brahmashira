package com.example.complyvision

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
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
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val ViolationCatalog = listOf(
    "Short net quantity", "MRP overcharging", "Missing mandatory declaration", "Unverified weighing scale",
    "Tampered stamping seal", "Unsealed or damaged packaging", "Incorrect manufacture date", "Misleading quantity claim"
)

private val TimeSlots = listOf("09:30", "11:00", "14:00", "16:00")
private val LongDate = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy", Locale.ENGLISH)

// ---------------------------------------------------------------------------
// Dialog frame shared by every case dialog
// ---------------------------------------------------------------------------

@Composable
internal fun SheetDialog(
    title: String,
    subtitle: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    footer: @Composable RowScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .fillMaxWidth()
                .heightIn(max = 690.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White)
        ) {
            Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 10.dp)) {
                Text(title, color = AC.Ink, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                if (subtitle != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(subtitle, color = AC.Muted, fontSize = 12.5.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp),
                content = body
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                content = footer
            )
        }
    }
}

@Composable
internal fun NoteField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, max: Int = 300) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 78.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFF4F8FE))
            .border(1.dp, AC.Line, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty()) Text(hint, color = AC.Faint, fontSize = 13.5.sp)
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.take(max)) },
            textStyle = TextStyle(color = AC.Ink, fontSize = 14.sp, lineHeight = 19.sp),
            cursorBrush = SolidColor(AC.Blue),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun InlineError(message: String?) {
    if (message != null) {
        Text(
            text = message, color = Color(0xFF9B2226), fontSize = 12.5.sp, lineHeight = 17.sp,
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(AC.RedSoft)
                .padding(horizontal = 12.dp, vertical = 9.dp)
        )
    }
}

@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = AC.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = modifier.padding(top = 14.dp, bottom = 8.dp))
}

// ---------------------------------------------------------------------------
// Date + time picking
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickDateDialog(initial: LocalDate?, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val todayMillis = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = (initial ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis >= todayMillis
        }
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }) { Text("Select", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) { DatePicker(state = state) }
}

@Composable
internal fun ScheduleFields(
    date: LocalDate?,
    onDate: (LocalDate) -> Unit,
    time: String?,
    onTime: (String?) -> Unit
) {
    var picking by remember { mutableStateOf(false) }
    val today = remember { LocalDate.now() }

    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterPill("Today", date == today, { onDate(today) })
        FilterPill("Tomorrow", date == today.plusDays(1), { onDate(today.plusDays(1)) })
        FilterPill("In 3 days", date == today.plusDays(3), { onDate(today.plusDays(3)) })
        val custom = date != null && date != today && date != today.plusDays(1) && date != today.plusDays(3)
        FilterPill(if (custom) date!!.format(DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)) else "Pick date…", custom, { picking = true })
    }
    if (date != null) {
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            AIconView(AIcon.CALENDAR, AC.Blue, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(date.format(LongDate), color = AC.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }

    FieldLabel("Time slot")
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterPill("Any time", time == null, { onTime(null) })
        TimeSlots.forEach { slot -> FilterPill(fmtTime(slot), time == slot, { onTime(slot) }) }
    }

    if (picking) {
        PickDateDialog(date, { picking = false }) { picked -> picking = false; onDate(picked) }
    }
}

// ---------------------------------------------------------------------------
// Assign (and reassign) an inspector, optionally scheduling in the same step
// ---------------------------------------------------------------------------

@Composable
internal fun AssignDialog(
    code: String,
    product: String,
    city: String,
    currentInspectorId: String?,
    inspectors: List<InspectorProfile>,
    onDismiss: () -> Unit,
    submit: suspend (inspectorId: String, date: String?, time: String?, note: String) -> ActionResult,
    onDone: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<String?>(null) }
    var scheduleOn by remember { mutableStateOf(true) }
    var date by remember { mutableStateOf<LocalDate?>(LocalDate.now().plusDays(1)) }
    var time by remember { mutableStateOf<String?>("11:00") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val eligible = remember(inspectors, city) {
        inspectors.filter { it.status == InspectorStatus.ACTIVE }
            .sortedWith(
                compareByDescending<InspectorProfile> { it.office.contains(city, ignoreCase = true) && city.isNotBlank() }
                    .thenBy { it.open }
                    .thenByDescending { it.passRate }
            )
    }
    val unavailable = inspectors.count { it.status != InspectorStatus.ACTIVE }

    SheetDialog(
        title = if (currentInspectorId.isNullOrBlank()) "Assign Inspector" else "Reassign Inspector",
        subtitle = "$code • $product",
        busy = busy,
        onDismiss = onDismiss,
        footer = {
            SoftButton("Cancel", onDismiss, Modifier.weight(1f), fg = AC.Muted, bg = AC.SlateSoft, enabled = !busy, height = 46.dp)
            PrimaryButton(
                text = if (scheduleOn) "Assign & Schedule" else "Assign",
                onClick = {
                    val id = selected ?: return@PrimaryButton
                    busy = true; error = null
                    scope.launch {
                        val result = submit(id, if (scheduleOn) date?.toString() else null, if (scheduleOn) time else null, note)
                        busy = false
                        when (result) {
                            is ActionResult.Done -> {
                                val name = inspectors.firstOrNull { it.officerId == id }?.name ?: id
                                onDone("Assigned to $name" + if (scheduleOn && date != null) " • ${fmtDateShort(date.toString())}" else "")
                            }
                            is ActionResult.Rejected -> error = result.message
                        }
                    }
                },
                modifier = Modifier.weight(1.6f),
                enabled = selected != null && (!scheduleOn || date != null),
                loading = busy
            )
        }
    ) {
        FieldLabel("Choose an inspector", Modifier.padding(top = 4.dp))
        if (eligible.isEmpty()) {
            Text("No active inspectors are available right now.", color = AC.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
        }
        eligible.forEach { ins ->
            val isSelected = selected == ins.officerId
            val sameCity = city.isNotBlank() && ins.office.contains(city, ignoreCase = true)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isSelected) AC.BlueSoft else Color(0xFFF6F9FE))
                    .border(1.5.dp, if (isSelected) AC.Blue else Color.Transparent, RoundedCornerShape(18.dp))
                    .clickable(role = Role.RadioButton) { selected = ins.officerId }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(ins.name, 38.dp, fontSize = 12)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ins.name, color = AC.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (sameCity) Pill("Same city", AC.Green, AC.GreenSoft, Modifier.padding(start = 6.dp), size = 9)
                        if (ins.officerId == currentInspectorId) Pill("Current", AC.Violet, AC.VioletSoft, Modifier.padding(start = 6.dp), size = 9)
                    }
                    Text(
                        "${ins.officerId} • ${ins.city.ifBlank { ins.office }}",
                        color = AC.Muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${ins.open} open case${if (ins.open == 1) "" else "s"} • ${fmtPct(ins.passRate)} pass rate",
                        color = if (ins.open >= 5) AC.Orange else AC.Faint, fontSize = 11.sp
                    )
                }
                Box(
                    Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (isSelected) AC.Blue else AC.Line, CircleShape),
                    contentAlignment = Alignment.Center
                ) { if (isSelected) Box(Modifier.size(12.dp).clip(CircleShape).background(AC.Blue)) }
            }
        }
        if (unavailable > 0) {
            Text(
                "$unavailable inspector${if (unavailable == 1) " is" else "s are"} on hold or suspended and can't take new cases.",
                color = AC.Faint, fontSize = 11.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        HorizontalRule(Modifier.padding(vertical = 10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Schedule the inspection now", color = AC.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("The inspector sees the date straight away", color = AC.Muted, fontSize = 11.5.sp)
            }
            Switch(
                checked = scheduleOn, onCheckedChange = { scheduleOn = it },
                colors = SwitchDefaults.colors(checkedTrackColor = AC.Blue)
            )
        }
        if (scheduleOn) {
            FieldLabel("Inspection date")
            ScheduleFields(date, { date = it }, time, { time = it })
        }

        FieldLabel("Note for the inspector (optional)")
        NoteField(note, { note = it }, "e.g. Check the digital scale seals and batch labels")
        InlineError(error)
        Spacer(Modifier.height(4.dp))
    }
}

// ---------------------------------------------------------------------------
// Schedule / reschedule
// ---------------------------------------------------------------------------

@Composable
internal fun ScheduleDialog(
    code: String,
    product: String,
    reschedule: Boolean,
    initialDate: String?,
    initialTime: String?,
    onDismiss: () -> Unit,
    submit: suspend (date: String, time: String?, note: String) -> ActionResult,
    onDone: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val start = parseDate(initialDate)?.takeIf { !it.isBefore(LocalDate.now()) } ?: LocalDate.now().plusDays(1)
    var date by remember { mutableStateOf<LocalDate?>(start) }
    var time by remember { mutableStateOf(initialTime?.take(5)) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SheetDialog(
        title = if (reschedule) "Reschedule Inspection" else "Schedule Inspection",
        subtitle = "$code • $product",
        busy = busy,
        onDismiss = onDismiss,
        footer = {
            SoftButton("Cancel", onDismiss, Modifier.weight(1f), fg = AC.Muted, bg = AC.SlateSoft, enabled = !busy, height = 46.dp)
            PrimaryButton(
                text = if (reschedule) "Reschedule" else "Schedule",
                onClick = {
                    val d = date ?: return@PrimaryButton
                    busy = true; error = null
                    scope.launch {
                        val result = submit(d.toString(), time, note)
                        busy = false
                        when (result) {
                            is ActionResult.Done -> onDone((if (reschedule) "Rescheduled" else "Scheduled") + " for ${fmtDateShort(d.toString())}")
                            is ActionResult.Rejected -> error = result.message
                        }
                    }
                },
                modifier = Modifier.weight(1.6f), enabled = date != null, loading = busy
            )
        }
    ) {
        FieldLabel("Inspection date", Modifier.padding(top = 4.dp))
        ScheduleFields(date, { date = it }, time, { time = it })
        FieldLabel("Note (optional)")
        NoteField(note, { note = it }, "Reason for the change or special instructions")
        InlineError(error)
        Spacer(Modifier.height(4.dp))
    }
}

// ---------------------------------------------------------------------------
// Authority decision on submitted findings
// ---------------------------------------------------------------------------

@Composable
internal fun ReviewDialog(
    code: String,
    product: String,
    violations: Int,
    violationTypes: List<String>,
    onDismiss: () -> Unit,
    submit: suspend (verdict: String, remarks: String) -> ActionResult,
    onDone: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var verdict by remember { mutableStateOf(if (violations > 0) "Failed" else "Passed") }
    var remarks by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SheetDialog(
        title = "Review Inspection Result",
        subtitle = "$code • $product",
        busy = busy,
        onDismiss = onDismiss,
        footer = {
            SoftButton("Cancel", onDismiss, Modifier.weight(1f), fg = AC.Muted, bg = AC.SlateSoft, enabled = !busy, height = 46.dp)
            PrimaryButton(
                text = when (verdict) { "Passed" -> "Approve as Passed"; "Failed" -> "Mark as Failed"; else -> "Send Back" },
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        val result = submit(verdict, remarks)
                        busy = false
                        when (result) {
                            is ActionResult.Done -> onDone(
                                when (verdict) {
                                    "Passed" -> "Inspection approved as Passed"
                                    "Failed" -> "Inspection marked as Failed"
                                    else -> "Sent back for re-inspection"
                                }
                            )
                            is ActionResult.Rejected -> error = result.message
                        }
                    }
                },
                modifier = Modifier.weight(1.6f), loading = busy,
                colors = when (verdict) {
                    "Passed" -> listOf(Color(0xFF15A34A), Color(0xFF34C77B))
                    "Failed" -> listOf(Color(0xFFE5484D), Color(0xFFF2777B))
                    else -> listOf(Color(0xFFE59A0B), Color(0xFFF5B83D))
                }
            )
        }
    ) {
        Row(
            Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (violations > 0) AC.RedSoft else AC.GreenSoft).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AIconView(if (violations > 0) AIcon.FLAG else AIcon.CHECK_CIRCLE, if (violations > 0) AC.Red else AC.Green, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    if (violations == 0) "Inspector reported no violations" else "$violations violation${if (violations == 1) "" else "s"} reported",
                    color = AC.Ink, fontSize = 13.5.sp, fontWeight = FontWeight.Bold
                )
                if (violationTypes.isNotEmpty()) {
                    Text(violationTypes.joinToString(", "), color = AC.Muted, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }

        FieldLabel("Your decision")
        listOf(
            Triple("Passed", "Approve as Passed", "Product and premises are compliant" to AC.Green),
            Triple("Failed", "Mark as Failed", "Non-compliance confirmed; case is closed" to AC.Red),
            Triple("Reinspect", "Send back for re-inspection", "Return to the inspector for a fresh visit" to AC.Amber)
        ).forEach { (key, title, sub) ->
            val on = verdict == key
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) sub.second.copy(alpha = 0.12f) else Color(0xFFF6F9FE))
                    .border(1.5.dp, if (on) sub.second else Color.Transparent, RoundedCornerShape(18.dp))
                    .clickable(role = Role.RadioButton) { verdict = key }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = AC.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(sub.first, color = AC.Muted, fontSize = 12.sp)
                }
                Box(
                    Modifier.size(22.dp).clip(CircleShape).border(2.dp, if (on) sub.second else AC.Line, CircleShape),
                    contentAlignment = Alignment.Center
                ) { if (on) Box(Modifier.size(12.dp).clip(CircleShape).background(sub.second)) }
            }
        }

        FieldLabel("Remarks (optional)")
        NoteField(remarks, { remarks = it }, "Add a note that is saved in the track record")
        InlineError(error)
        Spacer(Modifier.height(4.dp))
    }
}

// ---------------------------------------------------------------------------
// Inspector: submit findings
// ---------------------------------------------------------------------------

@Composable
internal fun SubmitFindingsDialog(
    code: String,
    product: String,
    onDismiss: () -> Unit,
    submit: suspend (violations: Int, types: List<String>, remarks: String) -> ActionResult,
    onDone: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var violations by remember { mutableIntStateOf(0) }
    var types by remember { mutableStateOf(setOf<String>()) }
    var remarks by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val needsTypes = violations > 0 && types.isEmpty()

    SheetDialog(
        title = "Submit Findings",
        subtitle = "$code • $product",
        busy = busy,
        onDismiss = onDismiss,
        footer = {
            SoftButton("Cancel", onDismiss, Modifier.weight(1f), fg = AC.Muted, bg = AC.SlateSoft, enabled = !busy, height = 46.dp)
            PrimaryButton(
                text = "Submit for Review",
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        val result = submit(violations, ViolationCatalog.filter { it in types }, remarks)
                        busy = false
                        when (result) {
                            is ActionResult.Done -> onDone("Findings submitted for review")
                            is ActionResult.Rejected -> error = result.message
                        }
                    }
                },
                modifier = Modifier.weight(1.6f), enabled = !needsTypes, loading = busy
            )
        }
    ) {
        FieldLabel("Violations found", Modifier.padding(top = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperButton("–", enabled = violations > 0) { violations -= 1; if (violations == 0) types = emptySet() }
            Text(
                violations.toString(), color = AC.Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.width(72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            StepperButton("+", enabled = violations < 20) { violations += 1 }
            Spacer(Modifier.width(14.dp))
            Text(
                if (violations == 0) "Fully compliant" else "Non-compliance noted",
                color = if (violations == 0) AC.Green else AC.Red, fontSize = 12.5.sp, fontWeight = FontWeight.Bold
            )
        }

        if (violations > 0) {
            FieldLabel("What was wrong? (select all that apply)")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ViolationCatalog.forEach { type ->
                    val on = type in types
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (on) AC.RedSoft else Color(0xFFF6F9FE))
                            .border(1.dp, if (on) AC.Red else Color.Transparent, RoundedCornerShape(14.dp))
                            .clickable(role = Role.Checkbox) { types = if (on) types - type else types + type }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(type, color = AC.Ink, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        if (on) AIconView(AIcon.CHECK_CIRCLE, AC.Red, Modifier.size(20.dp))
                    }
                }
            }
        }

        FieldLabel("Remarks (optional)")
        NoteField(remarks, { remarks = it }, "What did you observe on site?")
        InlineError(error)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, size: Dp = 44.dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) AC.BlueSoft else AC.SlateSoft)
            .clickable(enabled = enabled, role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (enabled) AC.Blue else AC.Faint, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold) }
}
