package com.example.complyvision

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.material3.ProvideTextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

private enum class CTab(val label: String, val icon: AIcon) {
    HOME("Home", AIcon.HOME),
    REPORTS("Reports", AIcon.CLIPBOARD),
    SCAN("Scan", AIcon.CAMERA),
    GUIDE("Guide", AIcon.SHIELD),
    PROFILE("Profile", AIcon.USER)
}

private enum class ReportFilter(val label: String, val verdict: String?) {
    ALL("All", null), PASSED("Passed", CaseStatus.PASSED), REVIEW("Review", CaseStatus.UNDER_REVIEW), FAILED("Failed", CaseStatus.FAILED)
}

private val BottomSpace = 112.dp

/**
 * The signed-in consumer's portal. Everything shown is this consumer's own data from Supabase; every
 * package check is saved under their account and can be reopened or exported later. The scanner,
 * rule engine, report view and PDF export are the same ones the Legal Metrology portal uses.
 */
@Composable
fun ConsumerPortalScreen(consumer: ConsumerSession, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    remember { AnalyzerClient.init(context) }
    val store = remember(consumer.token) {
        val asReporter = OfficerSession(consumer.token, consumer.email, consumer.name, "Consumer", "", OfficerRole.METROLOGY_INSPECTOR)
        ConsumerStore(asReporter, consumer, scope, onLogout)
    }
    val exporter = rememberReportExporter(store)
    val backend = remember(store) { ConsumerScanBackend(store) }

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var openScan by rememberSaveable { mutableStateOf<String?>(null) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var scanFromGallery by rememberSaveable { mutableStateOf(false) }
    var filterName by rememberSaveable { mutableStateOf(ReportFilter.ALL.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }

    val tab = CTab.entries[tabIndex]

    LaunchedEffect(store) {
        store.load()
        while (true) {
            delay(60_000)
            store.load(silent = true)
        }
    }

    fun startScan(gallery: Boolean) {
        scanFromGallery = gallery
        scanning = true
    }

    fun select(target: CTab) {
        if (target == CTab.SCAN) { startScan(false); return }
        tabIndex = target.ordinal
        openScan = null
    }

    BackHandler(enabled = !loggingOut && !scanning) {
        when {
            openScan != null -> openScan = null
            tab != CTab.HOME -> select(CTab.HOME)
            else -> confirmLogout = true
        }
    }

    val overview = (store.overview as? Load.Ready)?.data

    ProvideTextStyle(TextStyle(letterSpacing = 0.sp, lineHeight = 1.25.em)) {
        Box(Modifier.fillMaxSize().background(AC.page())) {
            Column(Modifier.fillMaxSize()) {
                if (openScan == null) ConsumerTopBar(consumer.name, onProfile = { select(CTab.PROFILE) })
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val code = openScan
                    when {
                        code != null -> StoredReportPage(
                            source = remember(store) {
                                ReportSource(store::scan, store::delete, store::handle) { m, err -> store.showToast(m, err) }
                            },
                            exporter = exporter, scanCode = code, heading = null, bottomSpace = BottomSpace,
                            product = overview?.scans?.firstOrNull { it.summary.scanCode == code }?.product,
                            onBack = { openScan = null },
                            onDeleted = { openScan = null },
                            extra = { _, report -> ConsumerAsk(overview?.scans?.firstOrNull { it.summary.scanCode == code }?.product ?: code, report) }
                        )
                        tab == CTab.HOME -> ConsumerHomeTab(
                            consumer = consumer, store = store,
                            onCamera = { startScan(false) }, onGallery = { startScan(true) },
                            onOpen = { openScan = it }, onAllReports = { select(CTab.REPORTS) }
                        )
                        tab == CTab.REPORTS -> ConsumerReportsTab(
                            store = store, filter = ReportFilter.valueOf(filterName), onFilter = { filterName = it.name },
                            query = query, onQuery = { query = it }, onOpen = { openScan = it }, onScan = { startScan(false) }
                        )
                        tab == CTab.GUIDE -> ConsumerGuideTab(onScan = { startScan(false) })
                        else -> ConsumerProfileTab(consumer, overview, onLogout = { confirmLogout = true })
                    }
                }
            }

            ConsumerBottomBar(selected = tab, onSelect = ::select, modifier = Modifier.align(Alignment.BottomCenter))

            if (scanning) {
                PackageScanScreen(
                    backend = backend,
                    exporter = exporter,
                    case = ScanTarget(
                        code = "", product = "Check a product", subtitle = "My reports",
                        savedScans = overview?.totals?.total ?: 0, savedNote = "saved to your reports"
                    ),
                    startWithGallery = scanFromGallery,
                    onClose = { scanning = false },
                    onOpenReport = { scan -> scanning = false; tabIndex = CTab.REPORTS.ordinal; openScan = scan }
                )
            }

            ToastBanner(
                toast = store.toast, onDismiss = { store.dismissToast(it) },
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { if (!loggingOut) confirmLogout = false },
            title = { Text("Log out?", fontWeight = FontWeight.Bold) },
            text = { Text("Your reports stay saved. Log in again any time to see them.") },
            confirmButton = {
                TextButton(enabled = !loggingOut, onClick = {
                    loggingOut = true
                    scope.launch {
                        ConsumerRepository.logout(consumer.token)
                        onLogout()
                    }
                }) { Text(if (loggingOut) "Logging out…" else "Log out", color = AC.Red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(enabled = !loggingOut, onClick = { confirmLogout = false }) { Text("Stay signed in") } }
        )
    }
}

// ---------------------------------------------------------------------------
// Chrome
// ---------------------------------------------------------------------------

@Composable
private fun ConsumerTopBar(name: String, onProfile: () -> Unit) {
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 2.dp).height(48.dp)) {
        BrandMark(Modifier.align(Alignment.CenterStart))
        Box(
            Modifier.align(Alignment.CenterEnd).clip(CircleShape).semantics { contentDescription = "Profile" }
                .clickable(role = Role.Button) { onProfile() }
        ) { Avatar(name, 42.dp, fontSize = 14) }
    }
}

@Composable
private fun ConsumerBottomBar(selected: CTab, onSelect: (CTab) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(30.dp)
    Row(
        modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)
            .shadow(14.dp, shape, ambientColor = Color(0x551E5FB0), spotColor = Color(0x551E5FB0))
            .clip(shape).background(Color(0xF7FFFFFF)).border(1.dp, Color.White, shape).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        CTab.entries.forEach { item ->
            if (item == CTab.SCAN) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.offset(y = (-14).dp).size(58.dp)
                            .shadow(10.dp, CircleShape, ambientColor = Color(0x881463F3), spotColor = Color(0x881463F3))
                            .clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF3B8BFF), Color(0xFF1148D6))))
                            .border(3.dp, Color.White, CircleShape)
                            .semantics { contentDescription = "Scan a product" }
                            .clickable(role = Role.Button) { onSelect(item) },
                        contentAlignment = Alignment.Center
                    ) { AIconView(AIcon.CAMERA, Color.White, Modifier.size(26.dp)) }
                }
            } else {
                val active = item == selected
                val lift by animateFloatAsState(if (active) 1f else 0f, label = "cNavLift")
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(Color(0xFFD9E8FF).copy(alpha = lift))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(item) }
                        .semantics { contentDescription = item.label }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AIconView(item.icon, if (active) AC.Blue else AC.Muted, Modifier.size(23.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(item.label, color = if (active) AC.Blue else AC.Muted, fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------

@Composable
private fun ConsumerHomeTab(
    consumer: ConsumerSession,
    store: ConsumerStore,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onOpen: (String) -> Unit,
    onAllReports: () -> Unit
) {
    val state = store.overview
    val hour = remember { LocalTime.now().hour }
    val greeting = when { hour < 5 -> "Good night"; hour < 12 -> "Good morning"; hour < 17 -> "Good afternoon"; else -> "Good evening" }
    val first = consumer.name.trim().substringBefore(' ')

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(top = 6.dp, bottom = BottomSpace),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text("$greeting,", color = AC.Muted, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("$first 👋", color = AC.Ink, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        }

        ScanHeroCard(onCamera, onGallery)

        when (state) {
            Load.Loading -> LoadingPanel("Loading your reports…")
            is Load.Failed -> ErrorPanel(state.message, { store.load() })
            is Load.Ready -> {
                val o = state.data
                StatsGrid(o.totals)
                if (o.totals.total > 0) DistributionCard(o.totals)

                SectionTitle("Recent reports", trailing = {
                    if (o.scans.size > 3) Text("View all", color = AC.Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { onAllReports() }.padding(8.dp))
                })
                if (o.scans.isEmpty()) {
                    EmptyPanel("No reports yet", "Scan the label of any packaged product and its compliance report will be saved here.", AIcon.CAMERA)
                } else {
                    o.scans.take(4).forEach { ReportRow(it) { onOpen(it.summary.scanCode) } }
                }
            }
        }

        TipCard()
    }
}

@Composable
private fun ScanHeroCard(onCamera: () -> Unit, onGallery: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier.fillMaxWidth().shadow(10.dp, shape, ambientColor = Color(0x661148D6), spotColor = Color(0x661148D6))
            .clip(shape).background(Brush.linearGradient(listOf(Color(0xFF1B6BFF), Color(0xFF0B3FBF))))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                AIconView(AIcon.SHIELD, Color.White, Modifier.size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Check a product", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Text("Photograph the label and get a full compliance report in seconds.", color = Color.White.copy(alpha = 0.85f), fontSize = 12.5.sp, lineHeight = 16.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeroAction("Take photo", AIcon.CAMERA, Modifier.weight(1f), filled = true, onClick = onCamera)
            HeroAction("From gallery", AIcon.UPLOAD, Modifier.weight(1f), filled = false, onClick = onGallery)
        }
    }
}

@Composable
private fun HeroAction(text: String, icon: AIcon, modifier: Modifier, filled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.height(48.dp).clip(shape)
            .background(if (filled) Color.White else Color.White.copy(alpha = 0.16f))
            .then(if (filled) Modifier else Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), shape))
            .clickable(role = Role.Button) { onClick() },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        AIconView(icon, if (filled) AC.Blue else Color.White, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (filled) AC.Blue else Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun StatsGrid(t: ConsumerTotals) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Products checked", fmtInt(t.total), AIcon.CLIPBOARD, AC.Blue, AC.BlueSoft, Modifier.weight(1f))
            StatTile("Compliant", fmtInt(t.passed), AIcon.CHECK_CIRCLE, AC.Green, AC.GreenSoft, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Non-compliant", fmtInt(t.failed), AIcon.CROSS_CIRCLE, AC.Red, AC.RedSoft, Modifier.weight(1f))
            StatTile("Needs review", fmtInt(t.review), AIcon.HOURGLASS, AC.Amber, AC.AmberSoft, Modifier.weight(1f))
        }
    }
}

@Composable
internal fun StatTile(label: String, value: String, icon: AIcon, fg: Color, bg: Color, modifier: Modifier) {
    GlassCard(modifier, corner = 20.dp, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
                AIconView(icon, fg, Modifier.size(22.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(value, color = AC.Ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text(label, color = AC.Muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun DistributionCard(t: ConsumerTotals) {
    GlassCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Compliance overview", color = AC.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold)
                Text("${fmtInt(t.thisMonth)} checked this month", color = AC.Muted, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmtPct(t.passRate), color = rateColor(t.passRate), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Text("compliant", color = AC.Muted, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(50)).background(AC.SlateSoft)) {
            if (t.passed > 0) Box(Modifier.weight(t.passed.toFloat()).fillMaxHeight().background(AC.Green))
            if (t.review > 0) Box(Modifier.weight(t.review.toFloat()).fillMaxHeight().background(AC.Amber))
            if (t.failed > 0) Box(Modifier.weight(t.failed.toFloat()).fillMaxHeight().background(AC.Red))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend("Passed", AC.Green); Legend("Review", AC.Amber); Legend("Failed", AC.Red)
            Spacer(Modifier.weight(1f))
            Text("Avg score ${String.format(java.util.Locale.US, "%.0f", t.averageScore)}", color = AC.Muted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun Legend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, color = AC.Muted, fontSize = 11.5.sp)
    }
}

@Composable
internal fun TipCard() {
    val tips = remember {
        listOf(
            "Good light, no glare: tilt the pack slightly if the label shines.",
            "Fill the guide frame with the label that shows MRP, net quantity and dates.",
            "Hold steady for a second before tapping the shutter for a sharper read.",
            "Check the front and the back of the pack for the best coverage."
        )
    }
    val tip = remember { tips.random() }
    GlassCard(tint = AC.GreenSoft.copy(alpha = 0.7f), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AIconView(AIcon.SHIELD, AC.Green, Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Scanning tip", color = AC.Green, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                Text(tip, color = AC.Ink, fontSize = 13.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
internal fun ReportRow(
    item: ConsumerScan, badge: String? = null, badgeColors: Pair<Color, Color> = AC.Blue to AC.BlueSoft, onClick: () -> Unit
) {
    val s = item.summary
    val (fg, bg) = statusStyle(s.verdict)
    val icon = when (s.verdict) { CaseStatus.PASSED -> AIcon.CHECK_CIRCLE; CaseStatus.FAILED -> AIcon.CROSS_CIRCLE; else -> AIcon.HOURGLASS }
    GlassCard(corner = 20.dp, padding = 12.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(bg), contentAlignment = Alignment.Center) {
                AIconView(icon, fg, Modifier.size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.product, color = AC.Ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(item.brand, s.category).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { "Package check" },
                    color = AC.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                val issues = s.failed + s.review
                Text(
                    (if (issues == 0) "All ${s.passed} rules passed" else "$issues of ${s.passed + issues} rules need attention") + " • " + relativeTime(s.createdAt),
                    color = if (issues == 0) AC.Green else AC.Muted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (badge != null) { Pill(badge, badgeColors.first, badgeColors.second, size = 10); Spacer(Modifier.height(4.dp)) }
                Pill(s.verdict, fg, bg, size = 10)
                Spacer(Modifier.height(6.dp))
                Text("${String.format(java.util.Locale.US, "%.0f", s.score)}%", color = rateColor(s.score), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Reports
// ---------------------------------------------------------------------------

@Composable
private fun ConsumerReportsTab(
    store: ConsumerStore,
    filter: ReportFilter,
    onFilter: (ReportFilter) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    onOpen: (String) -> Unit,
    onScan: () -> Unit
) {
    var compareMode by rememberSaveable { mutableStateOf(false) }
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    var comparing by remember { mutableStateOf<Pair<ConsumerScan, ConsumerScan>?>(null) }
    val allScans = (store.overview as? Load.Ready)?.data?.scans.orEmpty()

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 14.dp).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("My reports", color = AC.Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 4.dp).weight(1f))
                if (allScans.size >= 2) SoftButton(if (compareMode) "Cancel" else "Compare", { compareMode = !compareMode; picked = emptyList() },
                    icon = if (compareMode) AIcon.CLOSE else AIcon.SORT, height = 38.dp)
            }
            if (compareMode) Text("Pick two products to compare their ingredients, nutrition, allergens and compliance (${picked.size}/2 selected).",
                color = AC.Muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 4.dp))
            SearchBox(query, onQuery, "Search product, brand or category")
        }
        when (val state = store.overview) {
            Load.Loading -> LoadingPanel("Loading your reports…")
            is Load.Failed -> ErrorPanel(state.message, { store.load() }, Modifier.padding(16.dp))
            is Load.Ready -> {
                val all = state.data.scans
                val q = query.trim().lowercase()
                val shown = all.filter { item ->
                    (filter.verdict == null || item.summary.verdict == filter.verdict) &&
                        (q.isEmpty() || listOf(item.product, item.brand, item.summary.category, item.summary.scanCode).any { it.lowercase().contains(q) })
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScrollCompat().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReportFilter.entries.forEach { f ->
                        FilterPill(f.label, f == filter, { onFilter(f) },
                            count = all.count { f.verdict == null || it.summary.verdict == f.verdict })
                    }
                }
                if (shown.isEmpty()) {
                    Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (all.isEmpty()) {
                            EmptyPanel("No reports yet", "Every product you check is saved here under your account.", AIcon.CLIPBOARD)
                            PrimaryButton("Scan your first product", onScan, Modifier.fillMaxWidth(), icon = AIcon.CAMERA, height = 48.dp)
                        } else EmptyPanel("Nothing matches", "Try a different search or filter.", AIcon.SEARCH)
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = BottomSpace),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        shown.forEach { item ->
                            val code = item.summary.scanCode
                            val on = code in picked
                            Box(Modifier.then(if (on) Modifier.border(2.dp, AC.Blue, RoundedCornerShape(22.dp)) else Modifier)) {
                                ReportRow(item) {
                                    if (!compareMode) onOpen(code)
                                    else picked = if (on) picked - code else (picked + code).takeLast(2)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (compareMode && picked.size == 2) {
            PrimaryButton("Compare these 2 products", {
                val a = allScans.firstOrNull { it.summary.scanCode == picked[0] }
                val b = allScans.firstOrNull { it.summary.scanCode == picked[1] }
                if (a != null && b != null) comparing = a to b
            }, Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = BottomSpace), icon = AIcon.SORT, height = 50.dp)
        }
    }
    comparing?.let { (a, b) -> CompareDialog(store, a, b) { comparing = null } }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

/** Where a stored-report page loads from and deletes through (consumer and organisation portals share the page) */
internal class ReportSource(
    val scan: suspend (String) -> Pair<ScanSummary, PackageReport>,
    val delete: suspend (String) -> Boolean,
    val handle: (Exception) -> String?,
    val toast: (String, Boolean) -> Unit
)

/** A stored package report, with export and delete */
@Composable
internal fun StoredReportPage(
    source: ReportSource,
    exporter: ReportExporter,
    scanCode: String,
    product: String?,
    heading: PdfHeading?,
    bottomSpace: Dp,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    /** Extra block shown above the report (consumer: Ask ComplyVision) */
    extra: (@Composable (ScanSummary, PackageReport) -> Unit)? = null
) {
    var state by remember(scanCode) { mutableStateOf<Load<Pair<ScanSummary, PackageReport>>>(Load.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(scanCode, reload) {
        if (state !is Load.Ready) state = Load.Loading
        state = try {
            Load.Ready(source.scan(scanCode))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Load.Failed(source.handle(e) ?: return@LaunchedEffect)
        }
    }

    Column(Modifier.fillMaxSize()) {
        DetailHeader(product ?: scanCode, (state as? Load.Ready)?.data?.second?.verdictLabel, onBack)
        when (val s = state) {
            Load.Loading -> LoadingPanel("Loading report…")
            is Load.Failed -> ErrorPanel(s.message, { reload++ }, Modifier.padding(16.dp))
            is Load.Ready -> {
                val (summary, report) = s.data
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)
                        .padding(top = 4.dp, bottom = bottomSpace + 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("$scanCode • ${fmtDateTime(summary.createdAt)}", color = AC.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    extra?.invoke(summary, report)
                    PackageReportContent(report, summary, resolving = false, onResolve = null)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PrimaryButton("Download PDF", { exporter.packagePdf(null, summary, report, heading) }, Modifier.weight(1f),
                            loading = exporter.busy == "pdf:${summary.scanCode}", icon = AIcon.DOWNLOAD, height = 44.dp)
                        SoftButton("Export JSON", { exporter.packageJson(summary, report) }, Modifier.weight(1f),
                            enabled = exporter.busy == null, icon = AIcon.DOC, height = 44.dp)
                    }
                    SoftButton("Delete this report", { confirmDelete = true }, Modifier.fillMaxWidth(), fg = AC.Red, bg = AC.RedSoft,
                        icon = AIcon.BAN, height = 44.dp)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text("Delete report?", fontWeight = FontWeight.Bold) },
            text = { Text("This permanently removes $scanCode from your account.") },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    deleting = true
                    scope.launch {
                        val ok = source.delete(scanCode)
                        deleting = false
                        confirmDelete = false
                        if (ok) { source.toast("Report deleted", false); onDeleted() }
                        else source.toast("Could not delete the report", true)
                    }
                }) { Text(if (deleting) "Deleting…" else "Delete", color = AC.Red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(enabled = !deleting, onClick = { confirmDelete = false }) { Text("Keep it") } }
        )
    }
}

// ---------------------------------------------------------------------------
// Guide
// ---------------------------------------------------------------------------

@Composable
private fun ConsumerGuideTab(onScan: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(top = 6.dp, bottom = BottomSpace),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Consumer guide", color = AC.Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 4.dp))
        Text("Know what a compliant package must show, and how to get the best result from a scan.", color = AC.Muted, fontSize = 13.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 4.dp))

        GuideCard("What a packaged product must declare", AIcon.DOC, AC.Blue, AC.BlueSoft, listOf(
            "Name and address of the manufacturer, packer or importer",
            "Net quantity in standard units (g, kg, ml, l, pieces)",
            "Maximum Retail Price, inclusive of all taxes",
            "Month and year of manufacture or packing",
            "Consumer-care contact details",
            "Country of origin for imported goods"
        ))
        GuideCard("Beyond Legal Metrology", AIcon.SHIELD, AC.Green, AC.GreenSoft, listOf(
            "Food: FSSAI licence number, veg / non-veg mark, ingredients and nutrition",
            "Cosmetics and drugs: licence, batch number, expiry and warnings",
            "Electricals and toys: BIS / ISI standard marks where mandatory",
            "Tobacco and alcohol: statutory health warnings"
        ))
        GuideCard("Getting a clean scan", AIcon.CAMERA, AC.Teal, AC.TealSoft, listOf(
            "Use bright, even light and avoid glare on glossy packs",
            "Keep the label flat and inside the guide frame",
            "Hold still for a moment before the shutter",
            "Scan each side of the pack that carries declarations"
        ))
        GuideCard("Reading your result", AIcon.CHECK_CIRCLE, AC.Violet, AC.VioletSoft, listOf(
            "Passed: every applicable rule for the product category was met",
            "Under review: something could not be read with certainty; check the pack yourself",
            "Failed: a mandatory declaration is missing or wrong. You can download the PDF as evidence",
            "Reports are guidance from automated checks and do not replace an inspector's decision"
        ))
        PrimaryButton("Scan a product now", onScan, Modifier.fillMaxWidth(), icon = AIcon.CAMERA, height = 50.dp)
    }
}

@Composable
internal fun GuideCard(title: String, icon: AIcon, fg: Color, bg: Color, points: List<String>) {
    GlassCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) { AIconView(icon, fg, Modifier.size(21.dp)) }
            Spacer(Modifier.width(12.dp))
            Text(title, color = AC.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        points.forEach {
            Row(Modifier.padding(vertical = 3.dp)) {
                Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(fg))
                Spacer(Modifier.width(10.dp))
                Text(it, color = AC.Ink, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Profile
// ---------------------------------------------------------------------------

@Composable
private fun ConsumerProfileTab(consumer: ConsumerSession, overview: ConsumerOverview?, onLogout: () -> Unit) {
    val profile = overview?.profile
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(top = 6.dp, bottom = BottomSpace),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard(padding = 20.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(consumer.name, 78.dp, fontSize = 26)
                Spacer(Modifier.height(12.dp))
                Text(consumer.name, color = AC.Ink, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                Text(consumer.email, color = AC.Muted, fontSize = 13.5.sp)
                Spacer(Modifier.height(10.dp))
                Pill("Consumer account", AC.Blue, AC.BlueSoft)
            }
        }

        overview?.totals?.let { t ->
            GlassCard(padding = 16.dp) {
                Text("Your activity", color = AC.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(10.dp))
                LabelRow("Products checked", fmtInt(t.total))
                LabelRow("Compliant", "${fmtInt(t.passed)} (${fmtPct(t.passRate)})")
                LabelRow("Issues found", fmtInt(t.failed + t.review))
                LabelRow("Checked this month", fmtInt(t.thisMonth))
            }
        }

        GlassCard(padding = 16.dp) {
            Text("Account", color = AC.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            LabelRow("Member since", fmtDate(profile?.memberSince ?: consumer.memberSince))
            LabelRow("Last sign-in", profile?.lastLoginAt?.let { fmtDateTime(it) } ?: "This session")
            LabelRow("Privacy", "Reports are visible only to you")
        }

        SoftButton("Log out", onLogout, Modifier.fillMaxWidth(), fg = AC.Red, bg = AC.RedSoft, icon = AIcon.LOGOUT, height = 48.dp)
    }
}

@Composable
internal fun LabelRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AC.Muted, fontSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        Text(value, color = AC.Ink, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ConsumerAsk(name: String, report: PackageReport) {
    var open by rememberSaveable { mutableStateOf(false) }
    AskCard { open = true }
    if (open) AskComplyVisionDialog(name, remember(report) { productContext(name, report) }) { open = false }
}
