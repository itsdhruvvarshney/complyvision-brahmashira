package com.example.complyvision

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AuthTab(val label: String, val icon: AIcon) {
    DASHBOARD("Dashboard", AIcon.HOME),
    INSPECTIONS("Inspections", AIcon.CLIPBOARD),
    INSPECTORS("Inspectors", AIcon.PEOPLE),
    REPORTS("Reports", AIcon.CHART)
}

private val BottomBarSpace = 104.dp

/**
 * Signed-in Supreme Authority experience: Dashboard, Inspections, Inspectors and Reports behind a
 * floating bottom bar. All data comes from Supabase through [AuthorityStore].
 */
@Composable
fun AuthorityShellScreen(session: OfficerSession, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    val store = remember(session.token) { AuthorityStore(session, scope, onLogout) }

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var caseCode by rememberSaveable { mutableStateOf<String?>(null) }
    var inspectorId by rememberSaveable { mutableStateOf<String?>(null) }
    var caseFilter by rememberSaveable { mutableStateOf(CaseStatus.PENDING) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var profileOpen by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }

    val tab = AuthTab.entries[tabIndex]

    // Initial load, then a quiet refresh every 45 s so numbers stay live
    LaunchedEffect(store) {
        store.loadAll()
        while (true) {
            delay(45_000)
            store.loadAll(silent = true)
        }
    }
    // Reports are the "real time" view: refresh faster while they are on screen
    LaunchedEffect(tab) {
        if (tab == AuthTab.REPORTS) {
            while (true) {
                delay(20_000)
                store.loadReport(silent = true)
            }
        }
    }

    fun selectTab(target: AuthTab) {
        tabIndex = target.ordinal
        caseCode = null
        inspectorId = null
        menuOpen = false
    }

    fun openCase(code: String) {
        tabIndex = AuthTab.INSPECTIONS.ordinal
        inspectorId = null
        caseCode = code
    }

    BackHandler(enabled = !loggingOut) {
        when {
            menuOpen -> menuOpen = false
            caseCode != null -> caseCode = null
            inspectorId != null -> inspectorId = null
            tab != AuthTab.DASHBOARD -> selectTab(AuthTab.DASHBOARD)
            else -> confirmLogout = true
        }
    }

    val pendingCount = (store.dashboard as? Load.Ready)?.data?.queuePending ?: 0
    val reviewCount = (store.dashboard as? Load.Ready)?.data?.queueReview ?: 0
    val inDetail = caseCode != null || (tab == AuthTab.INSPECTORS && inspectorId != null)

    // The app theme adds letter spacing to body text; the dense authority layouts read better without it
    ProvideTextStyle(TextStyle(letterSpacing = 0.sp, lineHeight = 1.25.em)) {
    Box(Modifier.fillMaxSize().background(AC.page())) {
        Column(Modifier.fillMaxSize()) {
            if (!inDetail) {
                AuthorityTopBar(
                    alerts = pendingCount + reviewCount,
                    onMenu = { menuOpen = true },
                    onBell = {
                        caseFilter = if (pendingCount > 0) CaseStatus.PENDING else CaseStatus.UNDER_REVIEW
                        selectTab(AuthTab.INSPECTIONS)
                    },
                    onProfile = { profileOpen = true }
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    caseCode != null -> CaseDetailPage(
                        token = session.token,
                        code = caseCode!!,
                        role = OfficerRole.SUPREME_AUTHORITY,
                        inspectors = (store.inspectors as? Load.Ready)?.data.orEmpty(),
                        bottomSpace = BottomBarSpace,
                        mutate = { store.mutate(it) },
                        onToast = { message, error -> store.showToast(message, error) },
                        onBack = { caseCode = null },
                        onChanged = { },
                        onSessionExpired = onLogout
                    )

                    tab == AuthTab.INSPECTORS && inspectorId != null -> InspectorDetailPage(
                        store = store,
                        officerId = inspectorId!!,
                        bottomSpace = BottomBarSpace,
                        onBack = { inspectorId = null },
                        onOpenCase = { openCase(it) }
                    )

                    else -> when (tab) {
                        AuthTab.DASHBOARD -> Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        ) {
                            AuthorityDashboardTab(
                                store = store,
                                onOpenInspector = { id -> inspectorId = id; tabIndex = AuthTab.INSPECTORS.ordinal },
                                onSeeAllInspectors = { selectTab(AuthTab.INSPECTORS) }
                            )
                            Spacer(Modifier.height(BottomBarSpace))
                        }

                        AuthTab.INSPECTIONS -> AuthorityInspectionsTab(
                            store = store,
                            filter = caseFilter,
                            onFilter = { caseFilter = it },
                            bottomSpace = BottomBarSpace,
                            onOpenCase = { openCase(it) }
                        )

                        AuthTab.INSPECTORS -> AuthorityInspectorsTab(
                            store = store,
                            bottomSpace = BottomBarSpace,
                            onOpen = { inspectorId = it }
                        )

                        AuthTab.REPORTS -> AuthorityReportsTab(store = store, bottomSpace = BottomBarSpace)
                    }
                }
            }
        }

        BottomBar(
            selected = tab,
            pending = pendingCount,
            onSelect = ::selectTab,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        ToastBanner(
            toast = store.toast,
            onDismiss = { store.dismissToast(it) },
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
        )

        SideMenu(
            visible = menuOpen,
            session = session,
            selected = tab,
            pending = pendingCount,
            syncedAt = store.syncedAt,
            onSelect = ::selectTab,
            onRefresh = { menuOpen = false; store.loadAll(); store.showToast("Refreshing live data…") },
            onLogout = { menuOpen = false; confirmLogout = true },
            onDismiss = { menuOpen = false }
        )
    }
    }

    if (profileOpen) {
        ProfileDialog(
            session = session,
            teamSize = (store.dashboard as? Load.Ready)?.data?.inspectorsTotal,
            onDismiss = { profileOpen = false },
            onLogout = { profileOpen = false; confirmLogout = true }
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { if (!loggingOut) confirmLogout = false },
            title = { Text("Log out?", fontWeight = FontWeight.Bold) },
            text = { Text("You will need to sign in again to view your dashboard.") },
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
            dismissButton = {
                TextButton(enabled = !loggingOut, onClick = { confirmLogout = false }) { Text("Stay signed in") }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Top bar: menu, brand, notifications, profile
// ---------------------------------------------------------------------------

@Composable
private fun AuthorityTopBar(alerts: Int, onMenu: () -> Unit, onBell: () -> Unit, onProfile: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 2.dp)
            .height(48.dp)
    ) {
        Box(Modifier.align(Alignment.CenterStart)) {
            RoundIconButton(AIcon.MENU, "Open menu", onMenu, size = 42.dp)
        }
        BrandMark(Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            Box {
                RoundIconButton(
                    AIcon.BELL,
                    if (alerts > 0) "Notifications, $alerts need attention" else "Notifications",
                    onBell, size = 40.dp, shape = CircleShape
                )
                if (alerts > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 2.dp, end = 3.dp)
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(AC.Red)
                            .border(1.5.dp, Color.White, CircleShape)
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            RoundIconButton(AIcon.USER, "Profile", onProfile, size = 40.dp, tint = AC.Blue, shape = CircleShape)
        }
    }
}

// ---------------------------------------------------------------------------
// Floating bottom navigation
// ---------------------------------------------------------------------------

@Composable
private fun BottomBar(selected: AuthTab, pending: Int, onSelect: (AuthTab) -> Unit, modifier: Modifier = Modifier) {
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
        AuthTab.entries.forEach { item ->
            val active = item == selected
            val lift by animateFloatAsState(if (active) 1f else 0f, label = "navLift")
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFFD9E8FF).copy(alpha = lift))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab
                    ) { onSelect(item) }
                    .semantics { contentDescription = item.label }
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.size(24.dp)) {
                    AIconView(item.icon, if (active) AC.Blue else AC.Muted, Modifier.size(24.dp))
                    if (item == AuthTab.INSPECTIONS && pending > 0) {
                        Text(
                            text = if (pending > 99) "99+" else pending.toString(),
                            color = Color.White, fontSize = 8.5.sp, fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 10.dp, y = (-6).dp)
                                .clip(RoundedCornerShape(50))
                                .background(AC.Red)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    item.label,
                    color = if (active) AC.Blue else AC.Muted,
                    fontSize = 11.5.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Toast
// ---------------------------------------------------------------------------

@Composable
internal fun ToastBanner(toast: Toast?, onDismiss: (Long) -> Unit, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf<Toast?>(null) }
    LaunchedEffect(toast?.id) {
        if (toast != null) {
            last = toast
            delay(if (toast.action != null) 6500 else 3600)
            onDismiss(toast.id)
        }
    }
    AnimatedVisibility(
        visible = toast != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier
    ) {
        val t = toast ?: last
        if (t != null) {
            val tone = if (t.isError) AC.Red else AC.Green
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .shadow(12.dp, RoundedCornerShape(18.dp), ambientColor = Color(0x551E5FB0), spotColor = Color(0x551E5FB0))
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
                    .border(1.dp, tone.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                    .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(tone.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) { AIconView(if (t.isError) AIcon.CLOSE else AIcon.CHECK_CIRCLE, tone, Modifier.size(15.dp)) }
                Spacer(Modifier.width(10.dp))
                Text(
                    t.message, color = AC.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    lineHeight = 17.sp, modifier = Modifier.weight(1f, fill = false)
                )
                if (t.actionLabel != null && t.action != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        t.actionLabel, color = AC.Blue, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(role = Role.Button) { t.action.invoke(); onDismiss(t.id) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Slide-in menu
// ---------------------------------------------------------------------------

@Composable
private fun SideMenu(
    visible: Boolean,
    session: OfficerSession,
    selected: AuthTab,
    pending: Int,
    syncedAt: Long,
    onSelect: (AuthTab) -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x990B1F3A))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() }
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { -it },
        exit = slideOutHorizontally { -it }
    ) {
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

            AuthTab.entries.forEach { item ->
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
                    if (item == AuthTab.INSPECTIONS && pending > 0) {
                        Pill("$pending pending", AC.Orange, AC.OrangeSoft, size = 10)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalRule()
            Spacer(Modifier.height(6.dp))
            MenuAction(AIcon.REFRESH, "Refresh live data", AC.Ink, onRefresh)
            MenuAction(AIcon.LOGOUT, "Log out", AC.Red, onLogout)

            Spacer(Modifier.weight(1f))
            Text(
                text = if (syncedAt > 0) "Last synced ${fmtClock(syncedAt)}" else "Connecting to server…",
                color = AC.Faint, fontSize = 11.sp
            )
            Text("ComplyVision • Legal Metrology", color = AC.Faint, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MenuAction(icon: AIcon, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button) { onClick() }
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
private fun ProfileDialog(session: OfficerSession, teamSize: Int?, onDismiss: () -> Unit, onLogout: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Color.White)
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Avatar(session.name, 72.dp, fontSize = 24)
            Spacer(Modifier.height(12.dp))
            Text(session.name, color = AC.Ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 22.sp)
            Pill(session.designation, AC.Blue, AC.BlueSoft, Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            HorizontalRule()
            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabelValue("Officer ID", session.officerId)
                LabelValue("Office", session.office)
                if (teamSize != null) LabelValue("Inspectors reporting to you", fmtInt(teamSize))
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton("Close", onDismiss, Modifier.weight(1f), height = 44.dp)
                SoftButton("Log out", onLogout, Modifier.weight(1f), fg = AC.Red, bg = AC.RedSoft, height = 44.dp)
            }
        }
    }
}
