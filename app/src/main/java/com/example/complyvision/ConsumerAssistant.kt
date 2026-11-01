package com.example.complyvision

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// Consumer-only helpers built on the analyzer's Gemini endpoints (/ask, /compare). The API keys stay on the analyzer.

/** A compact, model-friendly view of one saved check: label facts, rule findings and the AI product analysis */
fun productContext(name: String, report: PackageReport): JSONObject {
    val problems = JSONArray()
    report.rules.filter { it.status == RuleStatus.FAIL || it.status == RuleStatus.REVIEW }.forEach {
        problems.put(JSONObject().put("rule", it.id).put("title", it.title).put("status", it.status).put("reason", it.reason.take(220)))
    }
    val declared = JSONObject()
    report.declarations.forEach { (k, v) -> declared.put(k, v.take(160)) }
    return JSONObject()
        .put("product", name)
        .put("category", report.classification?.categoryName.orEmpty())
        .put("compliance", JSONObject().put("verdict", report.verdictLabel).put("passed", report.passed).put("failed", report.failed)
            .put("needs_review", report.review).put("headline", report.headline))
        .put("rules_with_problems", problems)
        .put("declarations", declared)
        .put("analysis", report.raw.optJSONObject("intelligence") ?: JSONObject())
}

private data class ChatLine(val fromUser: Boolean, val text: String, val error: Boolean = false)

private val Starters = listOf(
    "Is this safe for children?", "Which ingredients should I watch out for?", "Does it contain allergens?",
    "Is it high in sugar or salt?", "Can I still eat it after the expiry date?"
)

/** "Ask ComplyVision": a chat that answers only from this product's scanned label and analysis */
@Composable
fun AskComplyVisionDialog(name: String, context: JSONObject, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf(ChatLine(false, "Hi! Ask me anything about $name. I answer from the label we scanned: ingredients, nutrition, dates, claims and the compliance check.")) }
    var suggestions by remember { mutableStateOf(Starters) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size, sending) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }

    fun send(question: String) {
        val q = question.trim()
        if (q.length < 2 || sending) return
        lines.add(ChatLine(true, q))
        input = ""
        sending = true
        val history = JSONArray()
        lines.dropLast(1).takeLast(8).forEach { history.put(JSONObject().put("role", if (it.fromUser) "user" else "assistant").put("text", it.text)) }
        scope.launch {
            try {
                val r = AnalyzerClient.postJson("/ask", JSONObject().put("question", q).put("product", context).put("history", history))
                lines.add(ChatLine(false, r.str("answer").ifBlank { "I could not find that on this label." }))
                suggestions = r.optJSONArray("follow_ups").strings().take(3)
            } catch (e: AnalyzerException) {
                lines.add(ChatLine(false, e.message ?: "The assistant is not available right now.", error = true))
            } finally {
                sending = false
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(AC.page()).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(AIcon.CLOSE, "Close chat", onDismiss, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Ask ComplyVision", color = AC.Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Text(name, color = AC.Muted, fontSize = 12.sp, maxLines = 1)
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(lines) { Bubble(it) }
                if (sending) item {
                    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = AC.Blue, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Thinking…", color = AC.Muted, fontSize = 12.5.sp)
                    }
                }
            }
            if (suggestions.isNotEmpty() && !sending) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach {
                        Text(it, color = AC.Blue, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(AC.BlueSoft).clickable(role = Role.Button) { send(it) }.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(Color.White).border(1.dp, AC.Line, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
                    if (input.isEmpty()) Text("Ask about ingredients, health, dates…", color = AC.Faint, fontSize = 14.sp)
                    BasicTextField(
                        value = input, onValueChange = { input = it.take(300) }, singleLine = true,
                        textStyle = TextStyle(color = AC.Ink, fontSize = 14.sp), cursorBrush = SolidColor(AC.Blue),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send(input) }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(if (input.isNotBlank() && !sending) AC.Blue else Color(0xFFBFD0EA))
                        .clickable(enabled = input.isNotBlank() && !sending, role = Role.Button) { send(input) },
                    contentAlignment = Alignment.Center
                ) { AIconView(AIcon.ARROW_RIGHT, Color.White, Modifier.size(22.dp)) }
            }
            Text("General information from the label, not medical advice.", color = AC.Faint, fontSize = 10.5.sp,
                modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 6.dp))
        }
    }
}

@Composable
private fun Bubble(l: ChatLine) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (l.fromUser) Arrangement.End else Arrangement.Start) {
        Text(
            l.text,
            color = if (l.fromUser) Color.White else if (l.error) AC.Red else AC.Ink, fontSize = 13.5.sp, lineHeight = 19.sp,
            modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp))
                .background(if (l.fromUser) AC.Blue else if (l.error) AC.RedSoft else Color.White)
                .then(if (l.fromUser) Modifier else Modifier.border(1.dp, AC.Line, RoundedCornerShape(18.dp)))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

/** Ask ComplyVision entry card shown on a saved consumer report */
@Composable
fun AskCard(onClick: () -> Unit) {
    GlassCard(padding = 14.dp, onClick = onClick, tint = AC.VioletSoft.copy(alpha = 0.7f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                AIconView(AIcon.SHIELD, AC.Violet, Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Ask ComplyVision", color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Text("Questions about this product's ingredients, health impact or expiry", color = AC.Muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            AIconView(AIcon.ARROW_RIGHT, AC.Violet, Modifier.size(20.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Product comparison
// ---------------------------------------------------------------------------

@Composable
fun CompareDialog(store: ConsumerStore, a: ConsumerScan, b: ConsumerScan, onDismiss: () -> Unit) {
    var state by remember { mutableStateOf<Load<JSONObject>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        state = Load.Loading
        state = try {
            val (_, ra) = store.scan(a.summary.scanCode)
            val (_, rb) = store.scan(b.summary.scanCode)
            Load.Ready(AnalyzerClient.postJson("/compare", JSONObject().put("a", productContext(a.product, ra)).put("b", productContext(b.product, rb))))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: AnalyzerException) {
            Load.Failed(e.message ?: "The comparison is not available right now.")
        } catch (e: Exception) {
            Load.Failed(store.handle(e) ?: return@LaunchedEffect)
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(AC.page()).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(AIcon.CLOSE, "Close comparison", onDismiss, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Compare products", color = AC.Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${a.product}  vs  ${b.product}", color = AC.Muted, fontSize = 12.sp, maxLines = 1)
                }
            }
            when (val s = state) {
                Load.Loading -> LoadingPanel("Comparing ingredients, nutrition and compliance…")
                is Load.Failed -> ErrorPanel(s.message, { reload++ }, Modifier.padding(16.dp))
                is Load.Ready -> ComparisonBody(s.data, a, b)
            }
        }
    }
}

@Composable
private fun ComparisonBody(r: JSONObject, a: ConsumerScan, b: ConsumerScan) {
    fun name(side: String) = if (side == "a") a.product else if (side == "b") b.product else null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val verdict = r.obj("verdict")
        GlassCard(padding = 16.dp, tint = AC.BlueSoft.copy(alpha = 0.7f)) {
            val better = verdict?.str("better_overall").orEmpty()
            Text(
                when (better) { "a", "b" -> "${name(better)} looks like the better choice"; "similar" -> "These products are quite similar"; else -> "No clear winner" },
                color = AC.Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 22.sp
            )
            if (r.str("summary").isNotBlank()) Text(r.str("summary"), color = AC.Ink, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
            verdict?.str("reason")?.takeIf { it.isNotBlank() }?.let { Text(it, color = AC.Muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 6.dp)) }
            verdict?.optJSONArray("caveats").strings().forEach { Text("• $it", color = AC.Muted, fontSize = 11.5.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp)) }
        }

        val nutrition = r.optJSONArray("nutrition").jsonObjects()
        if (nutrition.isNotEmpty()) GlassCard(padding = 14.dp) {
            SectionHeader(AIcon.CHART, "Nutrition side by side")
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Spacer(Modifier.weight(1.1f))
                Text(a.product, color = AC.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
                Text(b.product, color = AC.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
            }
            nutrition.forEach {
                val better = it.str("better")
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it.str("metric"), color = AC.Ink, fontSize = 12.5.sp, modifier = Modifier.weight(1.1f))
                    Text(it.str("a").ifBlank { "-" }, color = if (better == "a") AC.Green else AC.Ink, fontSize = 12.5.sp, fontWeight = if (better == "a") FontWeight.ExtraBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    Text(it.str("b").ifBlank { "-" }, color = if (better == "b") AC.Green else AC.Ink, fontSize = 12.5.sp, fontWeight = if (better == "b") FontWeight.ExtraBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                }
            }
        }

        r.obj("ingredients")?.let { ing ->
            GlassCard(padding = 14.dp) {
                SectionHeader(AIcon.DOC, "Ingredients")
                listOf("Only in ${a.product}" to ing.optJSONArray("a_only").strings(), "Only in ${b.product}" to ing.optJSONArray("b_only").strings(),
                    "In both" to ing.optJSONArray("common").strings()).filter { it.second.isNotEmpty() }.forEach { (t, l) ->
                    LabelValue(t, l.joinToString(", "), Modifier.padding(top = 8.dp))
                }
                if (ing.str("note").isNotBlank()) Text(ing.str("note"), color = AC.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        r.obj("allergens")?.let { al ->
            GlassCard(padding = 14.dp) {
                SectionHeader(AIcon.FLAG, "Allergens")
                LabelValue(a.product, al.optJSONArray("a").strings().joinToString(", ").ifBlank { "None declared" }, Modifier.padding(top = 8.dp))
                LabelValue(b.product, al.optJSONArray("b").strings().joinToString(", ").ifBlank { "None declared" }, Modifier.padding(top = 6.dp))
                if (al.str("note").isNotBlank()) Text(al.str("note"), color = AC.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        r.obj("compliance")?.let { c ->
            GlassCard(padding = 14.dp) {
                SectionHeader(AIcon.SHIELD, "Compliance findings")
                LabelValue(a.product, c.str("a").ifBlank { a.summary.verdict }, Modifier.padding(top = 8.dp))
                LabelValue(b.product, c.str("b").ifBlank { b.summary.verdict }, Modifier.padding(top = 6.dp))
                if (c.str("note").isNotBlank()) Text(c.str("note"), color = AC.Muted, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Text("General information from the labels, not medical advice.", color = AC.Faint, fontSize = 11.sp)
    }
}
