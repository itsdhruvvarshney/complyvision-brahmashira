package com.example.complyvision

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

// ============================================================================
// Category picker: the inspector can let the engine auto-detect the category or fix it
// ============================================================================

@Composable
fun CategoryPickerDialog(
    catalog: CategoryCatalog?,
    selectedId: String?,
    onPick: (ProductCategory?) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(AC.page()).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(AIcon.CLOSE, "Close", onDismiss, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Product category", color = AC.Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        catalog?.let { "${it.categories.size} categories • ${if (it.source == "database") "rule catalog from Supabase" else "rule catalog from the analyzer"}" }
                            ?: "Loading the rule catalog…",
                        color = AC.Muted, fontSize = 12.sp
                    )
                }
            }
            SearchBox(query, { query = it }, "Search rice, toothpaste, LED bulb…", Modifier.fillMaxWidth().padding(horizontal = 14.dp))
            Spacer(Modifier.height(10.dp))
            val q = query.trim().lowercase(Locale.ENGLISH)
            val items = catalog?.categories.orEmpty().filter {
                q.isEmpty() || it.name.lowercase(Locale.ENGLISH).contains(q) || it.groupName.lowercase(Locale.ENGLISH).contains(q) ||
                    it.id.contains(q.replace(' ', '_'))
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                if (q.isEmpty()) {
                    item {
                        PickerRow("Auto-detect (recommended)", "The engine reads the label, the case record and the AI reader to pick the category",
                            selectedId == null, AIcon.SEARCH) { onPick(null) }
                    }
                }
                items.groupBy { it.groupName }.forEach { (group, cats) ->
                    item {
                        Text(group.uppercase(Locale.ENGLISH), color = AC.Faint, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp, start = 4.dp))
                    }
                    items(cats, key = { it.id }) { c ->
                        PickerRow(c.name, "${c.rules} rules • ${c.laws.drop(1).firstOrNull() ?: c.laws.firstOrNull().orEmpty()}", c.id == selectedId) { onPick(c) }
                    }
                }
                if (catalog != null && items.isEmpty()) {
                    item { EmptyPanel("No matching category", "Try another word, or keep auto-detect.", AIcon.SEARCH) }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun PickerRow(title: String, subtitle: String, selected: Boolean, icon: AIcon? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) AC.BlueSoft else Color.White)
            .border(1.dp, if (selected) AC.Blue else AC.Line, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button) { onClick() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            AIconView(icon, AC.Blue, Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = AC.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = AC.Muted, fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
        }
        if (selected) AIconView(AIcon.CHECK_CIRCLE, AC.Blue, Modifier.size(20.dp))
    }
}

// ============================================================================
// Categorisation card: which category the engine chose, why, and which standards that brings in
// ============================================================================

@Composable
fun ClassificationCard(c: Classification, onChangeCategory: (() -> Unit)?) {
    var showSkipped by rememberSaveable { mutableStateOf(false) }
    val tone = when {
        c.setByInspector -> AC.Violet
        c.needsConfirmation -> AC.Amber
        else -> AC.Teal
    }
    GlassCard(padding = 14.dp, modifier = Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(tone.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                AIconView(AIcon.BOX, tone, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Step 1 • Product category", color = AC.Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(c.categoryName, color = AC.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 19.sp)
                Text(listOf(c.groupName, c.packageTypeName).filter { it.isNotBlank() }.joinToString(" • "), color = AC.Muted, fontSize = 12.sp)
            }
            Pill(
                when (c.source) { "inspector" -> "Set by you"; "fallback" -> "Not matched"; else -> "Auto ${(c.confidence * 100).toInt()}%" },
                tone, tone.copy(alpha = 0.13f), size = 10
            )
        }
        if (c.source == "auto") {
            Spacer(Modifier.height(8.dp))
            ProgressTrack(c.confidence.toFloat(), tone, height = 5.dp)
        }
        if (c.evidence.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                c.evidence.take(5).forEach { e ->
                    Pill("${e.source}: ${e.detail}".take(40), AC.Muted, AC.SlateSoft, size = 10)
                }
            }
        }
        if (c.needsConfirmation && c.alternatives.isNotEmpty()) {
            Text("Also possible: " + c.alternatives.take(3).joinToString(", ") { it.name }, color = AC.Amber, fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        }
        if (c.gate.isNotBlank()) {
            Text(c.gate, color = AC.Ink, fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AC.AmberSoft).padding(10.dp))
        }

        Spacer(Modifier.height(12.dp))
        Text("Step 2 • Standards applied", color = AC.Faint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        c.laws.forEach { law ->
            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 6.dp).size(5.dp).clip(CircleShape).background(tone))
                Spacer(Modifier.width(8.dp))
                Text(law, color = AC.Ink, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        if (c.quantityRef.isNotBlank()) {
            Text("Net quantity by ${c.quantityDims.joinToString(" or ") { dimLabel(it) }} — ${c.quantityRef}", color = AC.Muted, fontSize = 11.5.sp,
                lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (c.standardsLaw.isNotBlank()) {
            Text("BIS: ${c.standardsLaw}", color = AC.Muted, fontSize = 11.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${c.rulesApplied} of ${c.rulesInCatalog} catalog rules apply" + if (c.notRelevant.isNotEmpty()) " • ${c.notRelevant.size} not relevant" else "",
                color = AC.Ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)
            )
            if (c.notRelevant.isNotEmpty()) {
                Text(if (showSkipped) "Hide" else "Show", color = AC.Blue, fontSize = 12.5.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { showSkipped = !showSkipped }.padding(6.dp))
            }
        }
        if (showSkipped) {
            Text("Rules for other categories (not evaluated for this product):", color = AC.Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            c.notRelevant.forEach { (id, title) ->
                Text("$id  $title", color = AC.Muted, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (onChangeCategory != null) {
            Spacer(Modifier.height(10.dp))
            SoftButton(if (c.needsConfirmation) "Choose the category and re-check" else "Change category & re-check", onChangeCategory,
                Modifier.fillMaxWidth(), icon = AIcon.REFRESH, height = 40.dp)
        }
    }
}

private fun dimLabel(d: String) = when (d) {
    "mass" -> "weight"
    "number" -> "number"
    else -> d
}

// ============================================================================
// Review resolution: the inspector settles each REVIEW rule against the physical pack
// ============================================================================

@Composable
fun ReviewResolutionCard(rules: List<RuleResult>, busy: Boolean, onSubmit: (List<RuleDecision>) -> Unit) {
    val decisions = remember(rules) { mutableStateMapOf<String, Boolean>() }
    val notes = remember(rules) { mutableStateMapOf<String, String>() }
    GlassCard(padding = 14.dp, tint = Color(0xF2FFF8EA)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(AC.AmberSoft), contentAlignment = Alignment.Center) {
                AIconView(AIcon.HOURGLASS, AC.Amber, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Step 3 • Confirm on the physical pack", color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Text("${rules.size} rule${if (rules.size == 1) "" else "s"} need your eyes. The camera and AI can only flag these — you decide.",
                    color = AC.Muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        rules.forEach { r ->
            val choice = decisions[r.id]
            Column(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Color.White)
                    .border(1.dp, when (choice) { true -> AC.Green; false -> AC.Red; null -> AC.Line }, RoundedCornerShape(14.dp)).padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.id, color = AC.Blue, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.width(46.dp))
                    Text(r.title, color = AC.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 16.sp, modifier = Modifier.weight(1f))
                }
                Text(r.reason, color = AC.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SoftButton("Compliant", { decisions[r.id] = true }, Modifier.weight(1f),
                        fg = if (choice == true) Color.White else AC.Green, bg = if (choice == true) AC.Green else AC.GreenSoft,
                        icon = AIcon.CHECK_CIRCLE, height = 36.dp)
                    SoftButton("Violation", { decisions[r.id] = false }, Modifier.weight(1f),
                        fg = if (choice == false) Color.White else AC.Red, bg = if (choice == false) AC.Red else AC.RedSoft,
                        icon = AIcon.CROSS_CIRCLE, height = 36.dp)
                }
                if (choice != null) {
                    Spacer(Modifier.height(8.dp))
                    NoteField(notes[r.id].orEmpty(), { notes[r.id] = it.take(300) },
                        if (choice) "What you saw (optional), e.g. veg mark on the side panel" else "Describe the violation (optional)")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            if (decisions.isEmpty()) "Mark each item above" else "Save ${decisions.size} decision${if (decisions.size == 1) "" else "s"}",
            { onSubmit(decisions.map { (id, ok) -> RuleDecision(id, ok, notes[id].orEmpty()) }) },
            Modifier.fillMaxWidth(), enabled = decisions.isNotEmpty(), loading = busy, icon = AIcon.CHECK_CIRCLE, height = 44.dp
        )
    }
}

@Composable
private fun NoteField(value: String, onChange: (String) -> Unit, hint: String) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AC.PageMid).border(1.dp, AC.Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        if (value.isEmpty()) Text(hint, color = AC.Faint, fontSize = 12.5.sp)
        BasicTextField(value, onChange, textStyle = TextStyle(color = AC.Ink, fontSize = 13.sp), cursorBrush = SolidColor(AC.Blue),
            modifier = Modifier.fillMaxWidth())
    }
}

// ============================================================================
// Section summary strip used above the rule list
// ============================================================================

@Composable
internal fun SectionSummary(sections: List<SectionStat>, selected: String?, onSelect: (String?) -> Unit) {
    if (sections.size < 2) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterPill("All sections", selected == null, { onSelect(null) })
        sections.forEach { s ->
            val applicable = s.passed + s.failed + s.review
            FilterPill(
                s.section + if (s.failed > 0) " • ${s.failed} fail" else if (s.review > 0) " • ${s.review} review" else if (applicable > 0) " ✓" else "",
                selected == s.section, { onSelect(if (selected == s.section) null else s.section) }
            )
        }
    }
}
