package com.example.complyvision

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class InsTab(val label: String, val icon: AIcon) {
    DASHBOARD("Dashboard", AIcon.HOME),
    INSPECTIONS("Inspections", AIcon.CLIPBOARD),
    HISTORY("History", AIcon.CLOCK),
    REPORTS("Reports", AIcon.CHART)
}

internal val InspectorBottomSpace = 104.dp

/** A package report opened from a case (or from a fresh scan) */
private data class OpenScan(val caseCode: String, val scanCode: String)

/**
 * Signed-in Metrology Inspector experience: Dashboard, Inspections (with the package scanner),
 * History and Reports. Every figure is fetched live from Supabase for this inspector only.
 */
@Composable
fun InspectorDashboardScreen(session: OfficerSession, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    val store = remember(session.token) { InspectorStore(session, scope, onLogout) }
    val exporter = rememberReportExporter(store)

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var caseCode by rememberSaveable { mutableStateOf<String?>(null) }
    var openScanCase by rememberSaveable { mutableStateOf<String?>(null) }
    var openScanCode by rememberSaveable { mutableStateOf<String?>(null) }
    var scanningCase by remember { mutableStateOf<MyCase?>(null) }
    var caseFilter by rememberSaveable { mutableStateOf(InspectionFilter.PENDING.name) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var profileOpen by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }
    var caseRefresh by remember { mutableIntStateOf(0) }

    val tab = InsTab.entries[tabIndex]
    val openScan = if (openScanCase != null && openScanCode != null) OpenScan(openScanCase!!, openScanCode!!) else null

    LaunchedEffect(store) {
        store.loadAll()
        store.checkAnalyzer()
        while (true) {
            delay(40_000)
            store.loadAll(silent = true)
        }
    }

    fun selectTab(target: InsTab) {
        tabIndex = target.ordinal
        caseCode = null
        openScanCase = null
        openScanCode = null
        menuOpen = false
    }

    // A case opens on top of the current tab, so Back returns to where the inspector came from
    fun openCase(code: String) {
        openScanCase = null
        openScanCode = null
        caseCode = code
    }

    BackHandler(enabled = !loggingOut && scanningCase == null) {
        when {
            menuOpen -> menuOpen = false
            openScan != null -> { openScanCase = null; openScanCode = null }
            caseCode != null -> caseCode = null
            tab != InsTab.DASHBOARD -> selectTab(InsTab.DASHBOARD)
            else -> confirmLogout = true
        }
    }

    val counts = (store.cases as? Load.Ready)?.data?.counts
    val pending = counts?.let { it.assigned + it.scheduled + it.inProgress } ?: 0
    val inDetail = caseCode != null || openScan != null

    ProvideTextStyle(TextStyle(letterSpacing = 0.sp, lineHeight = 1.25.em)) {
        Box(Modifier.fillMaxSize().background(AC.page())) {
            Column(Modifier.fillMaxSize()) {
                if (!inDetail) {
                    InspectorTopBar(
                        alerts = pending,
                        onMenu = { menuOpen = true },
                        onBell = { caseFilter = InspectionFilter.PENDING.name; selectTab(InsTab.INSPECTIONS) },
                        onProfile = { profileOpen = true }
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        openScan != null -> PackageReportPage(
                            store = store,
                            exporter = exporter,
                            caseCode = openScan.caseCode,
                            scanCode = openScan.scanCode,
                            bottomSpace = InspectorBottomSpace,
                            onBack = { openScanCase = null; openScanCode = null }
                        )

                        caseCode != null -> InspectorCasePage(
                            store = store,
                            exporter = exporter,
                            code = caseCode!!,
                            refreshKey = caseRefresh,
                            bottomSpace = InspectorBottomSpace,
                            onBack = { caseCode = null },
                            onScan = { scanningCase = it },
                            onOpenScan = { scan -> openScanCase = caseCode; openScanCode = scan }
                        )

                        else -> when (tab) {
                            InsTab.DASHBOARD -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                                InspectorDashboardTab(
                                    store = store,
                                    exporter = exporter,
                                    onOpenCase = ::openCase,
                                    onViewAllHistory = { selectTab(InsTab.HISTORY) },
                                    onOpenInspections = { filter -> caseFilter = filter.name; selectTab(InsTab.INSPECTIONS) }
                                )
                                Spacer(Modifier.height(InspectorBottomSpace))
                            }

                            InsTab.INSPECTIONS -> InspectorInspectionsTab(
                                store = store,
                                filter = InspectionFilter.valueOf(caseFilter),
                                onFilter = { caseFilter = it.name },
                                bottomSpace = InspectorBottomSpace,
                                onOpenCase = ::openCase,
                                onScan = { scanningCase = it }
                            )

                            InsTab.HISTORY -> InspectorHistoryTab(
                                store = store,
                                exporter = exporter,
                                bottomSpace = InspectorBottomSpace,
                                onOpenCase = ::openCase
                            )

                            InsTab.REPORTS -> InspectorReportsTab(
                                store = store,
                                exporter = exporter,
                                bottomSpace = InspectorBottomSpace,
                                onOpenCase = ::openCase
                            )
                        }
                    }
                }
            }

            InspectorBottomBar(
                selected = tab,
                pending = pending,
                onSelect = ::selectTab,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            InspectorSideMenu(
                visible = menuOpen,
                store = store,
                selected = tab,
                pending = pending,
                onSelect = ::selectTab,
                onRefresh = { menuOpen = false; store.loadAll(); store.checkAnalyzer(); store.showToast("Refreshing live data…") },
                onLogout = { menuOpen = false; confirmLogout = true },
                onDismiss = { menuOpen = false }
            )

            scanningCase?.let { target ->
                PackageScanScreen(
                    store = store,
                    exporter = exporter,
                    case = target,
                    onClose = {
                        scanningCase = null
                        caseRefresh++
                    },
                    onOpenReport = { scan ->
                        scanningCase = null
                        caseRefresh++
                        caseCode = target.code
                        openScanCase = target.code
                        openScanCode = scan
                    }
                )
            }

            ToastBanner(
                toast = store.toast,
                onDismiss = { store.dismissToast(it) },
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
            )
        }
    }

    if (profileOpen) {
        InspectorProfileDialog(
            session = session,
            profile = (store.overview as? Load.Ready)?.data?.profile,
            onDismiss = { profileOpen = false },
            onLogout = { profileOpen = false; confirmLogout = true }
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { if (!loggingOut) confirmLogout = false },
            title = { Text("Log out?", fontWeight = FontWeight.Bold) },
            text = { Text("You will need to sign in again to view your inspections.") },
            confirmButton = {
                TextButton(
                    enabled = !loggingOut,
                    onClick = {
                        loggingOut = true
                        scope.launch {
                            OfficerRepository.logout(session.token)
                            onLogout()
                        }
                    }
                ) { Text(if (loggingOut) "Logging out…" else "Log out", color = AC.Red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(enabled = !loggingOut, onClick = { confirmLogout = false }) { Text("Stay signed in") } }
        )
    }
}

// ---------------------------------------------------------------------------
// Top bar
// ---------------------------------------------------------------------------

@Composable
private fun InspectorTopBar(alerts: Int, onMenu: () -> Unit, onBell: () -> Unit, onProfile: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 2.dp)
            .height(48.dp)
    ) {
        Box(Modifier.align(Alignment.CenterStart)) { RoundIconButton(AIcon.MENU, "Open menu", onMenu, size = 42.dp) }
        BrandMark(Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            Box {
                RoundIconButton(
                    AIcon.BELL, if (alerts > 0) "Notifications, $alerts open inspections" else "Notifications",
                    onBell, size = 40.dp, shape = CircleShape
                )
                if (alerts > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 3.dp).size(11.dp)
                            .clip(CircleShape).background(AC.Red).border(1.5.dp, Color.White, CircleShape)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            RoundIconButton(AIcon.USER, "Profile", onProfile, size = 40.dp, tint = AC.Blue, shape = CircleShape)
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom navigation
// ---------------------------------------------------------------------------

@Composable
private fun InspectorBottomBar(selected: InsTab, pending: Int, onSelect: (InsTab) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(30.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .shadow(14.dp, shape, ambientColor = Color(0x551E5FB0), spotColor = Color(0x551E5FB0))
            .clip(shape)
            .background(Color(0xF7FFFFFF))
            .border(1.dp, Color.White, shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        InsTab.entries.forEach { item ->
            val active = item == selected
            val lift by animateFloatAsState(if (active) 1f else 0f, label = "insNavLift")
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFFD9E8FF).copy(alpha = lift))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(item) }
                    .semantics { contentDescription = item.label }
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(24.dp)) {
                    AIconView(item.icon, if (active) AC.Blue else AC.Muted, Modifier.size(24.dp))
                    if (item == InsTab.INSPECTIONS && pending > 0) {
                        Text(
                            text = if (pending > 99) "99+" else pending.toString(),
                            color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp)
                                .clip(RoundedCornerShape(50)).background(AC.Red).padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    item.label, color = if (active) AC.Blue else AC.Muted, fontSize = 11.5.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium, maxLines = 1
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Side menu
// ---------------------------------------------------------------------------

@Composable
private fun InspectorSideMenu(
    visible: Boolean,
    store: InspectorStore,
    selected: InsTab,
    pending: Int,
    onSelect: (InsTab) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    val session = store.session
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(Color(0x990B1F3A))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
        )
    }
    AnimatedVisibility(visible = visible, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(300.dp)
                .shadow(20.dp, RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
                .clip(RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
                .background(Color.White)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(session.name, 54.dp, fontSize = 18)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(session.name, color = AC.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, lineHeight = 19.sp)
                    Text(session.designation, color = AC.Blue, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    Text("ID ${session.officerId}", color = AC.Muted, fontSize = 11.5.sp)
                }
            }
            if (session.office.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(session.office, color = AC.Muted, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.height(18.dp))
            HorizontalRule()
            Spacer(Modifier.height(10.dp))

            InsTab.entries.forEach { item ->
                val active = item == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (active) AC.BlueSoft else Color.Transparent)
                        .clickable(role = Role.Button) { onSelect(item) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AIconView(item.icon, if (active) AC.Blue else AC.Muted, Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(
                        item.label, color = if (active) AC.Blue else AC.Ink, fontSize = 15.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold, modifier = Modifier.weight(1f)
                    )
                    if (item == InsTab.INSPECTIONS && pending > 0) Pill("$pending open", AC.Orange, AC.OrangeSoft, size = 10)
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalRule()
            Spacer(Modifier.height(10.dp))
            AnalyzerStatusRow(store)
            Spacer(Modifier.height(6.dp))
            MenuRow(AIcon.REFRESH, "Refresh live data", AC.Ink, onRefresh)
            MenuRow(AIcon.LOGOUT, "Log out", AC.Red, onLogout)

            Spacer(Modifier.weight(1f))
            Text(
                text = if (store.syncedAt > 0) "Last synced ${fmtClock(store.syncedAt)}" else "Connecting to server…",
                color = AC.Faint, fontSize = 11.sp
            )
            Text("ComplyVision • Legal Metrology", color = AC.Faint, fontSize = 11.sp)
        }
    }
}

@Composable
internal fun AnalyzerStatusRow(store: InspectorStore, modifier: Modifier = Modifier) {
    val health = store.analyzer
    val (tone, title, detail) = when {
        health != null && health.ocrReady -> Triple(AC.Green, "Analyzer online",
            "${health.ocrEngine} • " + if (health.aiEnabled) "Gemini (${health.aiKeys} key${if (health.aiKeys == 1) "" else "s"})" else "Gemini off")
        health != null -> Triple(AC.Amber, "Analyzer starting", "OCR models are loading…")
        store.analyzerError != null -> Triple(AC.Red, "Analyzer offline", store.analyzerError!!)
        else -> Triple(AC.Faint, "Checking analyzer…", AnalyzerClient.baseUrl)
    }
    Row(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(tone.copy(alpha = 0.08f))
            .clickable(role = Role.Button) { store.checkAnalyzer() }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(tone))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = AC.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(detail, color = AC.Muted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 3)
        }
    }
}

@Composable
private fun MenuRow(icon: AIcon, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AIconView(icon, tint, Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = tint, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------------------
// Profile
// ---------------------------------------------------------------------------

@Composable
private fun InspectorProfileDialog(session: OfficerSession, profile: MyProfile?, onDismiss: () -> Unit, onLogout: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Color.White)
                .verticalScroll(rememberScrollState()).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Avatar(session.name, 72.dp, fontSize = 24)
            Spacer(Modifier.height(12.dp))
            Text(profile?.name ?: session.name, color = AC.Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 22.sp)
            Pill(profile?.designation ?: session.designation, AC.Blue, AC.BlueSoft, Modifier.padding(top = 6.dp))
            if (profile != null && profile.status != InspectorStatus.ACTIVE) {
                val (fg, bg) = inspectorStatusStyle(profile.status)
                Pill(inspectorStatusLabel(profile.status), fg, bg, Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(16.dp))
            HorizontalRule()
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelValue("Inspector ID", session.officerId)
                LabelValue("Office", profile?.office ?: session.office)
                if (profile != null) {
                    LabelValue("Tenure", tenureLabel(profile.employeeSince))
                    if (profile.specialization.isNotBlank()) LabelValue("Specialisation", profile.specialization)
                    profile.reportsTo?.let { LabelValue("Reports to", "${it.name} (${it.officerId}), ${it.designation}") }
                    if (profile.email.isNotBlank()) LabelValue("E-mail", profile.email)
                    if (profile.phone.isNotBlank()) LabelValue("Phone", profile.phone)
                    if (profile.statusReason.isNotBlank()) LabelValue("Status note", profile.statusReason, valueColor = AC.Red)
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("Close", onDismiss, Modifier.weight(1f), height = 44.dp)
                SoftButton("Log out", onLogout, Modifier.weight(1f), fg = AC.Red, bg = AC.RedSoft, height = 44.dp)
            }
        }
    }
}

/** "Aug 2026 – Present" */
internal fun tenureLabel(since: String?): String =
    if (parseDate(since) == null) "—" else "${fmtMonthYear(since)} – Present"
