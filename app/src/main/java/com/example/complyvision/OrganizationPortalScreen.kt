package com.example.complyvision

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val BisNavy = Color(0xFF0B3A82)
private val BisGold = Color(0xFFE0A526)
private val FssaiGreen = Color(0xFF2E9B3C)
private val FssaiOrange = Color(0xFFF08C1A)
private val OrgBottomSpace = 112.dp

private enum class OTab(val label: String, val icon: AIcon) {
    HOME("Home", AIcon.HOME),
    BIS("BIS", AIcon.SHIELD),
    FSSAI("FSSAI", AIcon.CHECK_CIRCLE),
    REPORTS("Reports", AIcon.CLIPBOARD),
    PROFILE("Profile", AIcon.BUILDING)
}

private enum class OrgFilter(val label: String, val authority: Authority?) {
    ALL("All", null), BIS("BIS", Authority.BIS), FSSAI("Pre-FSSAI", Authority.FSSAI)
}

private fun Authority.accent() = if (this == Authority.BIS) BisNavy else FssaiGreen
private fun Authority.soft() = if (this == Authority.BIS) Color(0xFFE2ECFB) else Color(0xFFE3F5E6)
private fun Authority.heading(product: String, org: String) =
    PdfHeading(tag, "$title Report", "$product • $org")

// ---------------------------------------------------------------------------
// Regulator symbols (drawn, no image assets)
// ---------------------------------------------------------------------------

/** BIS hallmark-style badge: navy shield, gold ring, "BIS" lettering and a quality tick */
@Composable
fun BisSymbol(modifier: Modifier = Modifier.size(56.dp)) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val shield = Path().apply {
            moveTo(w * 0.5f, h * 0.04f)
            lineTo(w * 0.92f, h * 0.2f)
            lineTo(w * 0.92f, h * 0.55f)
            cubicTo(w * 0.92f, h * 0.8f, w * 0.5f, h * 0.97f, w * 0.5f, h * 0.97f)
            cubicTo(w * 0.5f, h * 0.97f, w * 0.08f, h * 0.8f, w * 0.08f, h * 0.55f)
            lineTo(w * 0.08f, h * 0.2f)
            close()
        }
        drawPath(shield, Brush.verticalGradient(listOf(Color(0xFF1558C0), BisNavy)))
        drawPath(shield, BisGold, style = Stroke(width = w * 0.045f, join = StrokeJoin.Round))
        // inner triangle, the shape of the standard mark
        val tri = Path().apply {
            moveTo(w * 0.5f, h * 0.17f); lineTo(w * 0.77f, h * 0.55f); lineTo(w * 0.23f, h * 0.55f); close()
        }
        drawPath(tri, Color.White.copy(alpha = 0.95f), style = Stroke(width = w * 0.04f, join = StrokeJoin.Round))
        val label = measurer.measure("BIS", TextStyle(color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (w * 0.2f).toSp()))
        drawText(label, topLeft = Offset((w - label.size.width) / 2f, h * 0.6f))
        // tick
        drawCircle(Color(0xFF16A34A), radius = w * 0.17f, center = Offset(w * 0.8f, h * 0.8f))
        drawCircle(Color.White, radius = w * 0.17f, center = Offset(w * 0.8f, h * 0.8f), style = Stroke(width = w * 0.03f))
        val tick = Path().apply {
            moveTo(w * 0.72f, h * 0.8f); lineTo(w * 0.78f, h * 0.86f); lineTo(w * 0.89f, h * 0.73f)
        }
        drawPath(tick, Color.White, style = Stroke(width = w * 0.045f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** FSSAI-style badge: green and orange rings around a white disc with a tick and "fssai" lettering */
@Composable
fun FssaiSymbol(modifier: Modifier = Modifier.size(56.dp)) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val w = size.width
        val c = Offset(w / 2f, size.height / 2f)
        val r = w * 0.44f
        drawCircle(Color.White, radius = r + w * 0.05f, center = c)
        val ring = Stroke(width = w * 0.11f, cap = StrokeCap.Butt)
        drawArc(FssaiGreen, startAngle = 120f, sweepAngle = 200f, useCenter = false,
            topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2), style = ring)
        drawArc(FssaiOrange, startAngle = 320f, sweepAngle = 160f, useCenter = false,
            topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2), style = ring)
        val tick = Path().apply {
            moveTo(w * 0.34f, size.height * 0.4f); lineTo(w * 0.46f, size.height * 0.52f); lineTo(w * 0.68f, size.height * 0.26f)
        }
        drawPath(tick, FssaiGreen, style = Stroke(width = w * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val label = measurer.measure("fssai", TextStyle(color = FssaiGreen, fontWeight = FontWeight.ExtraBold, fontSize = (w * 0.2f).toSp()))
        drawText(label, topLeft = Offset((w - label.size.width) / 2f, size.height * 0.57f))
    }
}

@Composable
private fun AuthoritySymbol(a: Authority, size: androidx.compose.ui.unit.Dp) {
    if (a == Authority.BIS) BisSymbol(Modifier.size(size)) else FssaiSymbol(Modifier.size(size))
}

// ---------------------------------------------------------------------------
// Shell
// ---------------------------------------------------------------------------

/**
 * The signed-in organisation's portal: BIS verification and Pre-FSSAI verification (the same scanner,
 * analyzer and rule engine as Legal Metrology, limited to the regulator's own product categories),
 * every verification saved under the organisation, and the organisation's registered details.
 */
@Composable
fun OrganizationPortalScreen(org: OrgSession, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    remember { AnalyzerClient.init(context) }
    val store = remember(org.token) {
        val asReporter = OfficerSession(org.token, org.profile.orgCode, org.profile.name, "Organisation", org.profile.location, OfficerRole.METROLOGY_INSPECTOR)
        OrgStore(asReporter, org, scope, onLogout)
    }
    val exporter = rememberReportExporter(store)

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var openScan by rememberSaveable { mutableStateOf<String?>(null) }
    var scanningName by rememberSaveable { mutableStateOf<String?>(null) }
    var scanFromGallery by rememberSaveable { mutableStateOf(false) }
    var filterName by rememberSaveable { mutableStateOf(OrgFilter.ALL.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }

    val tab = OTab.entries[tabIndex]
    val scanning = scanningName?.let { Authority.valueOf(it) }
    val overview = (store.overview as? Load.Ready)?.data

    LaunchedEffect(store) {
        store.load()
        store.loadCategories()
        while (true) {
            delay(60_000)
            store.load(silent = true)
        }
    }

    fun startScan(a: Authority, gallery: Boolean) {
        scanFromGallery = gallery
        scanningName = a.name
    }

    fun select(t: OTab) { tabIndex = t.ordinal; openScan = null }

    BackHandler(enabled = !loggingOut && scanning == null) {
        when {
            openScan != null -> openScan = null
            tab != OTab.HOME -> select(OTab.HOME)
            else -> confirmLogout = true
        }
    }

    ProvideTextStyle(TextStyle(letterSpacing = 0.sp, lineHeight = 1.25.em)) {
        Box(Modifier.fillMaxSize().background(AC.page())) {
            Column(Modifier.fillMaxSize()) {
                if (openScan == null) OrgTopBar(org.profile.name, onProfile = { select(OTab.PROFILE) })
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val code = openScan
                    when {
                        code != null -> {
                            val item = overview?.scans?.firstOrNull { it.scan.summary.scanCode == code }
                            StoredReportPage(
                                source = remember(store) { ReportSource(store::scan, store::delete, store::handle) { m, err -> store.showToast(m, err) } },
                                exporter = exporter, scanCode = code, product = item?.scan?.product,
                                heading = item?.authority?.heading(item.scan.product, org.profile.name),
                                bottomSpace = OrgBottomSpace, onBack = { openScan = null }, onDeleted = { openScan = null }
                            )
                        }
                        tab == OTab.HOME -> OrgHomeTab(store, org.profile, ::startScan, onOpen = { openScan = it }, onAll = { select(OTab.REPORTS) },
                            onAuthority = { select(if (it == Authority.BIS) OTab.BIS else OTab.FSSAI) })
                        tab == OTab.BIS -> AuthorityTab(Authority.BIS, store, ::startScan) { openScan = it }
                        tab == OTab.FSSAI -> AuthorityTab(Authority.FSSAI, store, ::startScan) { openScan = it }
                        tab == OTab.REPORTS -> OrgReportsTab(store, OrgFilter.valueOf(filterName), { filterName = it.name }, query, { query = it }, { openScan = it })
                        else -> OrgProfileTab(overview?.profile ?: org.profile, overview?.totals, onLogout = { confirmLogout = true })
                    }
                }
            }

            OrgBottomBar(tab, ::select, Modifier.align(Alignment.BottomCenter))

            scanning?.let { a ->
                val scanned = overview?.scans?.count { it.authority == a } ?: 0
                PackageScanScreen(
                    backend = remember(store, a) { OrgScanBackend(store, a) },
                    exporter = exporter,
                    case = ScanTarget(
                        code = "", product = a.title, subtitle = org.profile.name, savedScans = scanned,
                        savedNote = "saved to ${a.short} verifications",
                        pdfHeading = PdfHeading(a.tag, "${a.title} Report", "${org.profile.name} • ${org.profile.orgCode}")
                    ),
                    startWithGallery = scanFromGallery,
                    onClose = { scanningName = null },
                    onOpenReport = { scan -> scanningName = null; tabIndex = OTab.REPORTS.ordinal; openScan = scan }
                )
            }

            ToastBanner(store.toast, { store.dismissToast(it) }, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp))
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { if (!loggingOut) confirmLogout = false },
            title = { Text("Log out?", fontWeight = FontWeight.Bold) },
            text = { Text("Your verifications stay saved under ${org.profile.name}.") },
            confirmButton = {
                TextButton(enabled = !loggingOut, onClick = {
                    loggingOut = true
                    scope.launch { OrgRepository.logout(org.token); onLogout() }
                }) { Text(if (loggingOut) "Logging out…" else "Log out", color = AC.Red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(enabled = !loggingOut, onClick = { confirmLogout = false }) { Text("Stay signed in") } }
        )
    }
}

@Composable
private fun OrgTopBar(name: String, onProfile: () -> Unit) {
    Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 2.dp).height(48.dp)) {
        BrandMark(Modifier.align(Alignment.CenterStart))
        Box(Modifier.align(Alignment.CenterEnd).clip(CircleShape).semantics { contentDescription = "Organisation profile" }
            .clickable(role = Role.Button) { onProfile() }) { Avatar(name, 42.dp, fontSize = 14) }
    }
}

@Composable
private fun OrgBottomBar(selected: OTab, onSelect: (OTab) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(30.dp)
    Row(
        modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)
            .shadow(14.dp, shape, ambientColor = Color(0x551E5FB0), spotColor = Color(0x551E5FB0))
            .clip(shape).background(Color(0xF7FFFFFF)).border(1.dp, Color.White, shape).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        OTab.entries.forEach { item ->
            val active = item == selected
            val lift by animateFloatAsState(if (active) 1f else 0f, label = "oNavLift")
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(Color(0xFFD9E8FF).copy(alpha = lift))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(item) }
                    .semantics { contentDescription = item.label }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (item) {
                    OTab.BIS -> BisSymbol(Modifier.size(24.dp))
                    OTab.FSSAI -> FssaiSymbol(Modifier.size(24.dp))
                    else -> AIconView(item.icon, if (active) AC.Blue else AC.Muted, Modifier.size(23.dp))
                }
                Spacer(Modifier.height(3.dp))
                Text(item.label, color = if (active) AC.Blue else AC.Muted, fontSize = 11.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------

@Composable
private fun OrgHomeTab(
    store: OrgStore,
    profile: OrgProfile,
    onScan: (Authority, Boolean) -> Unit,
    onOpen: (String) -> Unit,
    onAll: () -> Unit,
    onAuthority: (Authority) -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(top = 6.dp, bottom = OrgBottomSpace),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        OrgIdentityCard(profile)

        val state = store.overview
        val totals = (state as? Load.Ready)?.data?.totals
        AuthorityCard(Authority.BIS, totals?.bis, onOpen = { onAuthority(Authority.BIS) }, onCamera = { onScan(Authority.BIS, false) }, onGallery = { onScan(Authority.BIS, true) })
        AuthorityCard(Authority.FSSAI, totals?.fssai, onOpen = { onAuthority(Authority.FSSAI) }, onCamera = { onScan(Authority.FSSAI, false) }, onGallery = { onScan(Authority.FSSAI, true) })

        when (state) {
            Load.Loading -> LoadingPanel("Loading your verifications…")
            is Load.Failed -> ErrorPanel(state.message, { store.load() })
            is Load.Ready -> {
                val o = state.data
                StatsGrid(o.totals.base)
                if (o.totals.base.total > 0) DistributionCard(o.totals.base)
                SectionTitle("Recent verifications", trailing = {
                    if (o.scans.size > 3) Text("View all", color = AC.Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { onAll() }.padding(8.dp))
                })
                if (o.scans.isEmpty()) EmptyPanel("No verifications yet", "Run a BIS or Pre-FSSAI check on a product label and the report is saved here.", AIcon.CAMERA)
                else o.scans.take(4).forEach { ReportRow(it.scan, it.authority.short, it.authority.accent() to it.authority.soft()) { onOpen(it.scan.summary.scanCode) } }
            }
        }
        LicenceCard(profile)
    }
}

@Composable
private fun OrgIdentityCard(p: OrgProfile) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier.fillMaxWidth().shadow(10.dp, shape, ambientColor = Color(0x661148D6), spotColor = Color(0x661148D6))
            .clip(shape).background(Brush.linearGradient(listOf(Color(0xFF1B6BFF), Color(0xFF0B3FBF)))).padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                AIconView(AIcon.BUILDING, Color.White, Modifier.size(28.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Welcome back", color = Color.White.copy(alpha = 0.8f), fontSize = 12.5.sp)
                Text(p.name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(p.orgCode, Color.White, Color.White.copy(alpha = 0.18f), size = 11)
            Pill(p.businessType, Color.White, Color.White.copy(alpha = 0.18f), size = 11)
            if (p.location.isNotBlank()) Pill(p.location, Color.White, Color.White.copy(alpha = 0.18f), size = 11)
        }
    }
}

@Composable
private fun AuthorityCard(a: Authority, count: Int?, onOpen: () -> Unit, onCamera: () -> Unit, onGallery: () -> Unit) {
    GlassCard(padding = 16.dp, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AuthoritySymbol(a, 62.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(a.title, color = AC.Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text(a.body, color = AC.Muted, fontSize = 12.sp, lineHeight = 15.sp)
                Spacer(Modifier.height(6.dp))
                Pill(if (count == null) "…" else "$count verification${if (count == 1) "" else "s"}", a.accent(), a.soft(), size = 10)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Take photo", onCamera, Modifier.weight(1f), icon = AIcon.CAMERA, height = 44.dp,
                colors = if (a == Authority.BIS) listOf(Color(0xFF0B3A82), Color(0xFF1558C0)) else listOf(Color(0xFF1F8A32), Color(0xFF3DB04F)))
            SoftButton("From gallery", onGallery, Modifier.weight(1f), fg = a.accent(), bg = a.soft(), icon = AIcon.UPLOAD, height = 44.dp)
        }
    }
}

@Composable
private fun LicenceCard(p: OrgProfile) {
    GlassCard(padding = 16.dp) {
        Text("Registered licences", color = AC.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        LabelRow("FSSAI licence", p.fssaiLicence.ifBlank { "Not provided" })
        LabelRow("BIS licence / CRS", p.bisLicence.ifBlank { "Not provided" })
        LabelRow("GSTIN", p.gstin.ifBlank { "Not provided" })
    }
}

// ---------------------------------------------------------------------------
// BIS / FSSAI tabs
// ---------------------------------------------------------------------------

private fun checks(a: Authority) = if (a == Authority.BIS) listOf(
    "BIS standard mark (ISI / CRS registration) on products under a Quality Control Order",
    "Manufacturer or importer name and address",
    "Net quantity, MRP (inclusive of all taxes) and month / year of manufacture",
    "Consumer-care details, country of origin and batch number",
    "Model number, ratings and safety warnings where the product category requires them"
) else listOf(
    "FSSAI logo with the 14-digit licence or registration number",
    "Vegetarian / non-vegetarian symbol and the ingredients list",
    "Nutritional information and allergen declaration",
    "Net quantity, MRP, date of manufacture and best-before / use-by",
    "Category-specific statements such as health-supplement, infant-food or alcohol warnings"
)

@Composable
private fun AuthorityTab(a: Authority, store: OrgStore, onScan: (Authority, Boolean) -> Unit, onOpen: (String) -> Unit) {
    val catalog = store.catalog?.forAuthority(a)
    val scans = ((store.overview as? Load.Ready)?.data?.scans).orEmpty().filter { it.authority == a }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(top = 6.dp, bottom = OrgBottomSpace),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val shape = RoundedCornerShape(26.dp)
        Column(
            Modifier.fillMaxWidth().shadow(10.dp, shape, ambientColor = a.accent().copy(alpha = 0.4f), spotColor = a.accent().copy(alpha = 0.4f))
                .clip(shape).background(
                    Brush.linearGradient(if (a == Authority.BIS) listOf(Color(0xFF0B3A82), Color(0xFF1B6BFF)) else listOf(Color(0xFF1B7F2E), Color(0xFF45B65A)))
                ).padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(74.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.95f)), contentAlignment = Alignment.Center) {
                    AuthoritySymbol(a, 58.dp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 25.sp)
                    Text(a.body, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, lineHeight = 15.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (a == Authority.BIS) "Photograph the pack and check the product against BIS marking and the common label declarations before it reaches the market."
                else "Photograph the food label and check it against FSSAI labelling requirements before you apply for or renew your licence.",
                color = Color.White.copy(alpha = 0.92f), fontSize = 13.sp, lineHeight = 18.sp
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(50)).background(Color.White).clickable(role = Role.Button) { onScan(a, false) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) { AIconView(AIcon.CAMERA, a.accent(), Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Take photo", color = a.accent(), fontWeight = FontWeight.Bold, fontSize = 14.5.sp) }
                Row(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f))
                        .border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(50)).clickable(role = Role.Button) { onScan(a, true) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
                ) { AIconView(AIcon.UPLOAD, Color.White, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("From gallery", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.5.sp) }
            }
        }

        GlassCard(padding = 16.dp) {
            Text("What is checked", color = AC.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            checks(a).forEach {
                Row(Modifier.padding(vertical = 3.dp)) {
                    Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(a.accent()))
                    Spacer(Modifier.width(10.dp))
                    Text(it, color = AC.Ink, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }

        GlassCard(padding = 16.dp) {
            Text("Product categories under ${a.short}", color = AC.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold)
            Text("Only these categories can be selected in a ${a.title.lowercase()}.", color = AC.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            if (catalog == null) LoadingPanel("Loading categories…") else {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    catalog.categories.forEach { Pill(it.name, a.accent(), a.soft(), size = 11) }
                }
            }
        }

        SectionTitle("Recent ${a.short} verifications")
        if (scans.isEmpty()) EmptyPanel("Nothing here yet", "Your ${a.title.lowercase()} reports will appear here.", AIcon.CLIPBOARD)
        else scans.take(5).forEach { ReportRow(it.scan, a.short, a.accent() to a.soft()) { onOpen(it.scan.summary.scanCode) } }
    }
}

// ---------------------------------------------------------------------------
// Reports
// ---------------------------------------------------------------------------

@Composable
private fun OrgReportsTab(
    store: OrgStore, filter: OrgFilter, onFilter: (OrgFilter) -> Unit, query: String, onQuery: (String) -> Unit, onOpen: (String) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 14.dp).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Verification reports", color = AC.Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 4.dp))
            SearchBox(query, onQuery, "Search product, brand or report ID")
        }
        when (val state = store.overview) {
            Load.Loading -> LoadingPanel("Loading reports…")
            is Load.Failed -> ErrorPanel(state.message, { store.load() }, Modifier.padding(16.dp))
            is Load.Ready -> {
                val all = state.data.scans
                val q = query.trim().lowercase()
                val shown = all.filter { s ->
                    (filter.authority == null || s.authority == filter.authority) &&
                        (q.isEmpty() || listOf(s.scan.product, s.scan.brand, s.scan.summary.category, s.scan.summary.scanCode).any { it.lowercase().contains(q) })
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrgFilter.entries.forEach { f -> FilterPill(f.label, f == filter, { onFilter(f) }, count = all.count { f.authority == null || it.authority == f.authority }) }
                }
                if (shown.isEmpty()) {
                    Box(Modifier.padding(horizontal = 14.dp)) {
                        if (all.isEmpty()) EmptyPanel("No reports yet", "Run a BIS or Pre-FSSAI verification to create your first report.", AIcon.CLIPBOARD)
                        else EmptyPanel("Nothing matches", "Try a different search or filter.", AIcon.SEARCH)
                    }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = OrgBottomSpace), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        shown.forEach { ReportRow(it.scan, it.authority.short, it.authority.accent() to it.authority.soft()) { onOpen(it.scan.summary.scanCode) } }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Profile
// ---------------------------------------------------------------------------

@Composable
private fun OrgProfileTab(p: OrgProfile, totals: OrgTotals?, onLogout: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(top = 6.dp, bottom = OrgBottomSpace),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard(padding = 20.dp) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(p.name, 78.dp, fontSize = 26)
                Spacer(Modifier.height(12.dp))
                Text(p.name, color = AC.Ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 24.sp)
                Text(p.orgCode, color = AC.Blue, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(p.businessType, AC.Blue, AC.BlueSoft)
                    Pill(p.status.replaceFirstChar { it.uppercase() }, AC.Green, AC.GreenSoft)
                }
            }
        }
        totals?.let { t ->
            GlassCard(padding = 16.dp) {
                Text("Verification activity", color = AC.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                LabelRow("BIS verifications", fmtInt(t.bis))
                LabelRow("Pre-FSSAI verifications", fmtInt(t.fssai))
                LabelRow("Compliant", "${fmtInt(t.base.passed)} (${fmtPct(t.base.passRate)})")
                LabelRow("Issues found", fmtInt(t.base.failed + t.base.review))
            }
        }
        GlassCard(padding = 16.dp) {
            Text("Organisation details", color = AC.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            LabelRow("Business type", p.businessType)
            if (p.industry.isNotBlank()) LabelRow("Industry", p.industry)
            LabelRow("GSTIN", p.gstin.ifBlank { "Not provided" })
            LabelRow("PAN", p.pan.ifBlank { "Not provided" })
            LabelRow("FSSAI licence", p.fssaiLicence.ifBlank { "Not provided" })
            LabelRow("BIS licence / CRS", p.bisLicence.ifBlank { "Not provided" })
            LabelRow("Address", p.fullAddress.ifBlank { "Not provided" })
        }
        GlassCard(padding = 16.dp) {
            Text("Authorised person", color = AC.Ink, fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(8.dp))
            LabelRow("Name", p.person)
            if (p.designation.isNotBlank()) LabelRow("Designation", p.designation)
            LabelRow("Email", p.email)
            if (p.phone.isNotBlank()) LabelRow("Mobile", p.phone)
            LabelRow("Registered on", fmtDate(p.memberSince))
            LabelRow("Last sign-in", p.lastLoginAt?.let { fmtDateTime(it) } ?: "This session")
        }
        Text("Organisation details are managed on the ComplyVision website.", color = AC.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp))
        SoftButton("Log out", onLogout, Modifier.fillMaxWidth(), fg = AC.Red, bg = AC.RedSoft, icon = AIcon.LOGOUT, height = 48.dp)
    }
}
