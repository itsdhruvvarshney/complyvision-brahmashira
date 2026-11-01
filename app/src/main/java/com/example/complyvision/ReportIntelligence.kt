package com.example.complyvision

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

// Gemini-powered sections of a report. Consumer checks get product intelligence (ingredients, health, allergens,
// nutrition, dates, claims); inspector and organisation checks get a formal compliance review. Everything here is
// rendered from the analyzer's validated JSON; the rule engine's verdict above is never changed by these cards.

private fun impactTone(v: String): Pair<Color, Color> = when (v) {
    "negligible", "low" -> AC.Green to AC.GreenSoft
    "moderate" -> AC.Amber to AC.AmberSoft
    "high" -> AC.Red to AC.RedSoft
    else -> AC.Slate to AC.SlateSoft
}

private fun impactLabel(v: String) = when (v) {
    "negligible" -> "Negligible impact"
    "low" -> "Low impact"
    "moderate" -> "Moderate impact"
    "high" -> "Higher impact"
    else -> "Impact unknown"
}

private fun photoName(names: List<String>, index: Int?): String? =
    index?.let { names.getOrNull(it)?.takeIf { n -> n.isNotBlank() }?.let { n -> "$n photo" } ?: "photo ${it + 1}" }

// ---------------------------------------------------------------------------
// Consumer: product intelligence
// ---------------------------------------------------------------------------

@Composable
fun ProductIntelligenceSection(intel: JSONObject, photoNames: List<String>) {
    if (!intel.optBoolean("available")) {
        GlassCard(padding = 14.dp) {
            SectionHeader(AIcon.SHIELD, "AI product analysis", "Ingredients, allergens, nutrition and claims")
            Text(intel.str("reason").ifBlank { "The AI assistant was not available for this scan, so only the rule check is shown." },
                color = AC.Muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp))
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OverallCard(intel.obj("overall"))
        intel.optJSONArray("ingredients").jsonObjects().takeIf { it.isNotEmpty() }?.let { IngredientsCard(it) }
        AllergenCard(intel.obj("allergens"), intel.optJSONArray("ingredients").jsonObjects().filter { it.optBoolean("allergen") }.map { it.str("name") })
        NutritionCard(intel.obj("nutrition"))
        ExpiryCard(intel.obj("dates"))
        intel.optJSONArray("claims_check").jsonObjects().takeIf { it.isNotEmpty() }?.let { ClaimsCard(it, photoNames) }
        intel.optJSONArray("transparency").jsonObjects().takeIf { it.isNotEmpty() }?.let { TransparencyCard(it) }
        Text(intel.str("disclaimer").ifBlank { "General information from the label, not medical advice." },
            color = AC.Faint, fontSize = 11.sp, fontStyle = FontStyle.Italic, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun OverallCard(o: JSONObject?) {
    if (o == null) return
    val good = o.optJSONArray("good_points").strings()
    val watch = o.optJSONArray("watch_points").strings()
    if (o.str("summary").isBlank() && good.isEmpty() && watch.isEmpty()) return
    GlassCard(padding = 16.dp) {
        SectionHeader(AIcon.SHIELD, "What this product is", "AI summary of the label")
        if (o.str("summary").isNotBlank()) Text(o.str("summary"), color = AC.Ink, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
        good.forEach { Bullet(it, AC.Green) }
        watch.forEach { Bullet(it, AC.Amber) }
    }
}

@Composable
private fun Bullet(text: String, color: Color) {
    Row(Modifier.padding(top = 6.dp)) {
        Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(text, color = AC.Ink, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun IngredientsCard(items: List<JSONObject>) {
    GlassCard(padding = 14.dp) {
        SectionHeader(AIcon.DOC, "Ingredients explained", "${items.size} listed on the pack • tap one for details")
        Spacer(Modifier.height(8.dp))
        items.forEachIndexed { i, it -> IngredientRow(it, i == 0 && items.size == 1) }
        Text("Natural is not automatically safe and synthetic is not automatically harmful. Impact reflects typical amounts used in food.",
            color = AC.Faint, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun IngredientRow(it: JSONObject, startOpen: Boolean) {
    var open by rememberSaveable(it.str("name")) { mutableStateOf(startOpen) }
    val impact = it.str("health_impact").ifBlank { "unknown" }
    val (fg, bg) = impactTone(impact)
    val careful = it.optJSONArray("who_should_be_careful").strings()
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(Color.White)
            .border(1.dp, if (open) fg.copy(alpha = 0.4f) else AC.Line, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button) { open = !open }.padding(10.dp).animateContentSize()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(it.str("name"), color = AC.Ink, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, lineHeight = 17.sp)
                it.str("purpose").takeIf { p -> p.isNotBlank() }?.let { p -> Text(p, color = AC.Muted, fontSize = 11.5.sp) }
            }
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Pill(impactLabel(impact), fg, bg, size = 9)
                if (it.optBoolean("allergen")) Pill("Allergen", AC.Red, AC.RedSoft, size = 9)
            }
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(it.str("origin").replaceFirstChar { c -> c.uppercase() }.ifBlank { "Unknown" } + " origin", AC.Blue, AC.BlueSoft, size = 10)
                if (!it.optBoolean("verified_in_ocr")) Pill("Read by AI", AC.Violet, AC.VioletSoft, size = 10)
            }
            it.str("uses").takeIf { u -> u.isNotBlank() }?.let { u -> LabelValue("Commonly used for", u, Modifier.padding(top = 8.dp)) }
            it.str("concern").takeIf { c -> c.isNotBlank() }?.let { c -> LabelValue("Health note", c, Modifier.padding(top = 6.dp), valueColor = fg) }
            if (careful.isNotEmpty()) LabelValue("Be careful if", careful.joinToString(", "), Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun AllergenCard(a: JSONObject?, flagged: List<String>) {
    val declared = a?.optJSONArray("declared").strings()
    val related = a?.optJSONArray("sensitivity_related").strings()
    val cross = a?.str("cross_contamination_warning").orEmpty()
    if (declared.isEmpty() && related.isEmpty() && cross.isBlank() && flagged.isEmpty()) return
    GlassCard(padding = 14.dp, tint = AC.RedSoft.copy(alpha = 0.55f)) {
        SectionHeader(AIcon.FLAG, "Allergens & sensitivities")
        Spacer(Modifier.height(8.dp))
        if (declared.isNotEmpty()) LabelValue("Declared allergens", declared.joinToString(", "), valueColor = AC.Red)
        if (related.isNotEmpty()) LabelValue("Sensitivity-related", related.joinToString(", "), Modifier.padding(top = 6.dp))
        val extra = flagged.filter { f -> declared.none { d -> d.equals(f, true) } }
        if (extra.isNotEmpty()) LabelValue("Ingredients flagged as allergens", extra.joinToString(", "), Modifier.padding(top = 6.dp))
        if (cross.isNotBlank()) Text(cross, color = AC.Ink, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun NutritionCard(n: JSONObject?) {
    if (n == null) return
    val rows = listOf(
        "Energy" to n.dblOrNull("calories_kcal")?.let { "${trim(it)} kcal" }, "Protein" to n.dblOrNull("protein_g")?.let { "${trim(it)} g" },
        "Carbohydrate" to n.dblOrNull("carbohydrate_g")?.let { "${trim(it)} g" }, "Total sugars" to n.dblOrNull("sugar_g")?.let { "${trim(it)} g" },
        "Added sugars" to n.dblOrNull("added_sugar_g")?.let { "${trim(it)} g" }, "Total fat" to n.dblOrNull("fat_g")?.let { "${trim(it)} g" },
        "Saturated fat" to n.dblOrNull("saturated_fat_g")?.let { "${trim(it)} g" }, "Trans fat" to n.dblOrNull("trans_fat_g")?.let { "${trim(it)} g" },
        "Sodium" to n.dblOrNull("sodium_mg")?.let { "${trim(it)} mg" }, "Fibre" to n.dblOrNull("fibre_g")?.let { "${trim(it)} g" }
    ).filter { it.second != null }
    val obs = n.optJSONArray("observations").jsonObjects()
    if (rows.isEmpty() && obs.isEmpty()) return
    GlassCard(padding = 14.dp) {
        SectionHeader(AIcon.CHART, "Nutrition", n.str("per").takeIf { it.isNotBlank() }?.let { "Per $it" } ?: "As printed on the pack")
        Spacer(Modifier.height(6.dp))
        rows.forEach { (k, v) -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(k, color = AC.Muted, fontSize = 13.sp); Text(v.orEmpty(), color = AC.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold) } }
        obs.forEach {
            val (fg, _) = when (it.str("level")) { "good" -> AC.Green to AC.GreenSoft; "watch" -> AC.Amber to AC.AmberSoft; else -> AC.Blue to AC.BlueSoft }
            Bullet(it.str("text"), fg)
        }
    }
}

private fun trim(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)

@Composable
private fun ExpiryCard(d: JSONObject?) {
    if (d == null) return
    val life = d.obj("shelf_life")
    val rows = listOf("Manufactured" to d.str("manufacture"), "Expiry" to d.str("expiry"), "Best before" to d.str("best_before"), "Batch" to d.str("batch_no"))
        .filter { it.second.isNotBlank() }
    if (rows.isEmpty() && life?.str("status") in listOf(null, "unknown")) return
    GlassCard(padding = 14.dp) {
        SectionHeader(AIcon.CALENDAR, "Dates & shelf life")
        Spacer(Modifier.height(6.dp))
        rows.forEach { (k, v) -> Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(k, color = AC.Muted, fontSize = 13.sp); Text(v, color = AC.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold) } }
        if (life != null && life.str("status") != "unknown" && life.str("status").isNotBlank()) {
            val left = life.int("days_left")
            val (fg, bg, text) = when (life.str("status")) {
                "expired" -> Triple(AC.Red, AC.RedSoft, "Expired ${-left} day${if (-left == 1) "" else "s"} ago (${fmtDate(life.str("date"))})")
                "expiring_soon" -> Triple(AC.Amber, AC.AmberSoft, "Expires in $left day${if (left == 1) "" else "s"} (${fmtDate(life.str("date"))})")
                else -> Triple(AC.Green, AC.GreenSoft, "$left days of shelf life left (until ${fmtDate(life.str("date"))})")
            }
            Spacer(Modifier.height(8.dp))
            Pill(text, fg, bg, size = 12)
        } else {
            Text("Remaining shelf life could not be worked out from the printed dates.", color = AC.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
        d.str("date_format_note").takeIf { it.isNotBlank() }?.let { Text(it, color = AC.Faint, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun ClaimsCard(claims: List<JSONObject>, photoNames: List<String>) {
    GlassCard(padding = 14.dp) {
        SectionHeader(AIcon.FLAG, "Front-of-pack claims checked", "Compared with the ingredients and nutrition on the back")
        Spacer(Modifier.height(8.dp))
        claims.forEach { c ->
            val verdict = c.str("verdict")
            val (fg, bg, label) = when (verdict) {
                "supported" -> Triple(AC.Green, AC.GreenSoft, "Supported")
                "misleading" -> Triple(AC.Red, AC.RedSoft, "Misleading")
                "unsupported" -> Triple(AC.Red, AC.RedSoft, "Not supported")
                else -> Triple(AC.Slate, AC.SlateSoft, "Could not verify")
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Color.White)
                .border(1.dp, AC.Line, RoundedCornerShape(14.dp)).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("“${c.str("claim")}”", color = AC.Ink, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    Pill(label, fg, bg, size = 10)
                }
                c.str("explanation").takeIf { it.isNotBlank() }?.let { Text(it, color = AC.Ink, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp)) }
                c.str("proof").takeIf { it.isNotBlank() }?.let { proof ->
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(AC.SlateSoft).padding(10.dp)) {
                        Text("Proof on the label" + (photoName(photoNames, c.optInt("proof_image", -1).takeIf { i -> i >= 0 })?.let { n -> " ($n)" } ?: ""),
                            color = AC.Muted, fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold)
                        Text("“$proof”", color = AC.Ink, fontSize = 12.5.sp, fontStyle = FontStyle.Italic, lineHeight = 17.sp, modifier = Modifier.padding(top = 2.dp))
                        if (!c.optBoolean("proof_verified")) Text("Read by AI from the photo", color = AC.Violet, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TransparencyCard(items: List<JSONObject>) {
    GlassCard(padding = 14.dp) {
        SectionHeader(AIcon.SEARCH, "Label transparency", "Missing information is not the same as a violation")
        Spacer(Modifier.height(8.dp))
        items.forEach {
            val kind = it.str("kind")
            val (fg, bg, label) = when (kind) {
                "misleading" -> Triple(AC.Red, AC.RedSoft, "Misleading")
                "missing" -> Triple(AC.Amber, AC.AmberSoft, "Not on the label")
                "unreadable" -> Triple(AC.Slate, AC.SlateSoft, "Unreadable")
                else -> Triple(AC.Blue, AC.BlueSoft, "Unclear")
            }
            Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                Pill(label, fg, bg, size = 9)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(it.str("item"), color = AC.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    if (it.str("detail").isNotBlank()) Text(it.str("detail"), color = AC.Muted, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Inspector / organisation: formal compliance review
// ---------------------------------------------------------------------------

@Composable
fun ComplianceReviewSection(rv: JSONObject, organisation: Boolean) {
    if (!rv.optBoolean("available")) {
        GlassCard(padding = 14.dp) {
            SectionHeader(AIcon.SHIELD, "AI cross-verification", "Analysis of all photos against the rule results")
            Text(rv.str("reason").ifBlank { "The AI assistant was not available for this check; the rule engine result above is complete." },
                color = AC.Muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp))
        }
        return
    }
    val cross = rv.optJSONArray("cross_check").jsonObjects()
    val inconsistencies = rv.optJSONArray("inconsistencies").jsonObjects()
    val missing = rv.optJSONArray("missing_information").jsonObjects()
    val docs = rv.optJSONArray("missing_documents").strings()
    val recs = rv.optJSONArray("recommendations").jsonObjects()
    val manual = rv.optJSONArray("manual_review").strings()
    val findings = rv.optJSONArray("rule_findings").jsonObjects()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard(padding = 16.dp) {
            SectionHeader(AIcon.SHIELD, "AI cross-verification", rv.str("note").ifBlank { "The rule engine remains the final authority." })
            if (rv.str("summary").isNotBlank()) Text(rv.str("summary"), color = AC.Ink, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
            val disputes = findings.count { it.str("gemini_opinion") == "disputes" }
            val supports = findings.count { it.str("gemini_opinion") == "supports" }
            if (findings.isNotEmpty()) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("$supports rule results supported", AC.Green, AC.GreenSoft, size = 10)
                if (disputes > 0) Pill("$disputes disputed - review", AC.Amber, AC.AmberSoft, size = 10)
            }
        }
        if (cross.isNotEmpty()) GlassCard(padding = 14.dp) {
            SectionHeader(AIcon.SORT, "Engine vs AI reading", "Field by field")
            Spacer(Modifier.height(6.dp))
            cross.forEach {
                val (fg, bg, label) = when (it.str("status")) {
                    "agrees" -> Triple(AC.Green, AC.GreenSoft, "Agrees")
                    "differs" -> Triple(AC.Red, AC.RedSoft, "Differs")
                    "engine_missed" -> Triple(AC.Amber, AC.AmberSoft, "Engine missed")
                    else -> Triple(AC.Slate, AC.SlateSoft, "Unsure")
                }
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(it.str("field").replace('_', ' ').replaceFirstChar { c -> c.uppercase() }, color = AC.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text("Engine: ${it.str("engine").ifBlank { "-" }}  •  AI: ${it.str("gemini").ifBlank { "-" }}", color = AC.Muted, fontSize = 11.5.sp, lineHeight = 15.sp)
                    }
                    Pill(label, fg, bg, size = 9)
                }
            }
        }
        if (inconsistencies.isNotEmpty()) GlassCard(padding = 14.dp, tint = AC.AmberSoft.copy(alpha = 0.6f)) {
            SectionHeader(AIcon.FLAG, "Inconsistencies between photos or declarations")
            inconsistencies.forEach { Bullet("${it.str("item")}: ${it.str("detail")}", AC.Amber) }
        }
        if (missing.isNotEmpty()) GlassCard(padding = 14.dp) {
            SectionHeader(AIcon.SEARCH, "Missing or unreadable information")
            missing.forEach { Bullet(it.str("item") + (it.strOrNull("rule_id")?.let { r -> " ($r)" } ?: "") + (it.str("why_it_matters").takeIf { w -> w.isNotBlank() }?.let { w -> " - $w" } ?: ""), AC.Red) }
        }
        if (organisation && docs.isNotEmpty()) GlassCard(padding = 14.dp) {
            SectionHeader(AIcon.DOC, "Supporting documents to prepare")
            docs.forEach { Bullet(it, AC.Blue) }
        }
        if (organisation && recs.isNotEmpty()) GlassCard(padding = 14.dp) {
            SectionHeader(AIcon.CHECK_CIRCLE, "Corrective recommendations")
            Spacer(Modifier.height(6.dp))
            recs.forEach {
                val (fg, bg) = when (it.str("priority")) { "high" -> AC.Red to AC.RedSoft; "low" -> AC.Slate to AC.SlateSoft; else -> AC.Amber to AC.AmberSoft }
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(Color.White).border(1.dp, AC.Line, RoundedCornerShape(14.dp)).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(it.str("issue"), color = AC.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Pill(it.str("priority").replaceFirstChar { c -> c.uppercase() }.ifBlank { "Medium" }, fg, bg, size = 9)
                    }
                    Text(it.str("action"), color = AC.Ink, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp))
                    it.strOrNull("rule_id")?.let { r -> Text("Rule $r", color = AC.Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
        if (manual.isNotEmpty()) GlassCard(padding = 14.dp, tint = AC.VioletSoft.copy(alpha = 0.6f)) {
            SectionHeader(AIcon.USER, "For manual review")
            manual.forEach { Bullet(it, AC.Violet) }
        }
    }
}

@Composable
fun GeminiAuditLine(audit: JSONObject, photos: Int) {
    val confirmed = audit.int("confirmed_by_gemini")
    val flagged = audit.int("flagged_for_review")
    val checked = audit.int("checked")
    if (checked == 0 && photos <= 1) return
    Text(
        buildString {
            if (photos > 1) append("$photos photos analysed together. ")
            if (checked > 0) append("AI re-checked $checked uncertain rule${if (checked == 1) "" else "s"}")
            if (confirmed > 0) append(", confirmed $confirmed on the label")
            if (flagged > 0) append(", flagged $flagged for manual review")
            if (checked > 0) append(".")
        },
        color = AC.Violet, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 15.sp, modifier = Modifier.padding(horizontal = 4.dp)
    )
}
