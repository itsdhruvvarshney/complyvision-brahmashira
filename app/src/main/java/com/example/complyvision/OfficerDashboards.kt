package com.example.complyvision

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private sealed interface DashState<out T> {
    data object Loading : DashState<Nothing>
    data class Ready<T>(val data: T) : DashState<T>
    data class Failed(val message: String) : DashState<Nothing>
}

private val InspectorHeader = listOf(Color(0xFF1D5FE0), Color(0xFF3B8BFF))

// ============================================================================
// Inspector dashboard (the Supreme Authority has its own shell in AuthorityShell.kt)
// ============================================================================

@Composable
fun InspectorDashboardScreen(session: OfficerSession, onLogout: () -> Unit) {
    var reloadKey by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<DashState<InspectorDashboardData>>(DashState.Loading) }
    var filter by rememberSaveable { mutableStateOf("All") }
    var openCode by rememberSaveable { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<Toast?>(null) }

    LaunchedEffect(session.token, reloadKey) {
        if (state !is DashState.Ready) state = DashState.Loading
        try {
            state = DashState.Ready(DashboardRepository.inspector(session.token))
        } catch (e: SessionExpiredException) {
            onLogout()
        } catch (e: ApiException) {
            if (state !is DashState.Ready) state = DashState.Failed(e.userMessage())
        }
    }

    val code = openCode
    if (code != null) {
        BackHandler { openCode = null }
        androidx.compose.material3.ProvideTextStyle(androidx.compose.ui.text.TextStyle(letterSpacing = 0.sp, lineHeight = 1.25.em)) {
        Box(Modifier.fillMaxSize().background(portalPageBrush())) {
            CaseDetailPage(
                token = session.token,
                code = code,
                role = OfficerRole.METROLOGY_INSPECTOR,
                inspectors = emptyList(),
                bottomSpace = 24.dp,
                mutate = { it() },
                onToast = { message, isError -> toast = Toast(System.nanoTime(), message, isError) },
                onBack = { openCode = null },
                onChanged = { reloadKey++ },
                onSessionExpired = onLogout
            )
            ToastBanner(
                toast = toast,
                onDismiss = { toast = null },
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp)
            )
        }
        }
    } else {
        DashboardFrame(
            session = session,
            headerColors = InspectorHeader,
            title = "Inspector Dashboard",
            onRefresh = { reloadKey++ },
            onLogout = onLogout
        ) {
            when (val s = state) {
                DashState.Loading -> LoadingBlock()
                is DashState.Failed -> ErrorBlock(s.message) { reloadKey++ }
                is DashState.Ready -> {
                    val d = s.data
                    StatGrid(
                        listOf(
                            StatItem("Assigned", d.assigned, Color(0xFF1D5FE0), Color(0xFFE3EEFF)),
                            StatItem("In Progress", d.inProgress, Color(0xFF0E9AA7), Color(0xFFD9F4F6)),
                            StatItem("In Review", d.inReview, Color(0xFFB7791F), Color(0xFFFFF1D6)),
                            StatItem("Completed", d.completed, Color(0xFF1E8E4E), Color(0xFFDDF5E7)),
                            StatItem("Violations Found", d.violations, Color(0xFFC53030), Color(0xFFFDE4E4)),
                            StatItem("Total Cases", d.inspections.size, Color(0xFF5B3FC4), Color(0xFFEBE5FF))
                        )
                    )

                    SectionTitle("My Inspections")

                    val statuses = listOf("All", CaseStatus.ASSIGNED, CaseStatus.SCHEDULED, CaseStatus.IN_PROGRESS, CaseStatus.UNDER_REVIEW, CaseStatus.PASSED, CaseStatus.FAILED)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        statuses.forEach { label ->
                            FilterChipPill(label = label, selected = filter == label) { filter = label }
                        }
                    }

                    val shown = d.inspections.filter { filter == "All" || it.status == filter }
                    if (shown.isEmpty()) {
                        EmptyBlock(
                            if (d.inspections.isEmpty()) "No inspections have been assigned to you yet."
                            else "No inspections with status \"$filter\"."
                        )
                    } else {
                        shown.forEach { InspectionCard(it) { openCode = it.code } }
                    }
                }
            }
        }
    }
}

// ============================================================================
// Shared dashboard building blocks
// ============================================================================

@Composable
private fun DashboardFrame(
    session: OfficerSession,
    headerColors: List<Color>,
    title: String,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    var confirmLogout by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }

    // Back never silently leaves a signed-in dashboard
    BackHandler(enabled = !loggingOut) { confirmLogout = true }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(portalPageBrush())
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
        ) {
            // Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.linearGradient(headerColors),
                        RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(top = 14.dp, bottom = 24.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    HeaderIconButton(Glyph.REFRESH, "Refresh", onRefresh)
                    Spacer(modifier = Modifier.width(8.dp))
                    HeaderIconButton(Glyph.LOGOUT, "Log out") { confirmLogout = true }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Welcome back,",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp
                )
                Text(
                    text = session.name,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    lineHeight = 30.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeaderPill(session.designation)
                    Spacer(modifier = Modifier.width(8.dp))
                    HeaderPill(session.officerId)
                }

                if (session.office.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = session.office,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp,
                        lineHeight = 17.sp
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 18.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                content = content
            )
        }
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
                ) { Text(if (loggingOut) "Logging out…" else "Log out", color = Color(0xFFC53030), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(enabled = !loggingOut, onClick = { confirmLogout = false }) { Text("Stay signed in") }
            }
        )
    }
}

@Composable
private fun HeaderIconButton(glyph: Glyph, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f))
            .semantics { contentDescription = description }
            .clickable(role = Role.Button) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        GlyphIcon(glyph, Color.White, Modifier.size(20.dp))
    }
}

@Composable
private fun HeaderPill(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.2f))
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

private data class StatItem(val label: String, val value: Int, val color: Color, val tint: Color)

@Composable
private fun StatGrid(items: List<StatItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { item ->
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .shadow(3.dp, RoundedCornerShape(20.dp), ambientColor = item.color.copy(alpha = 0.2f), spotColor = item.color.copy(alpha = 0.2f)),
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(item.tint),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = item.value.toString(),
                                    color = item.color,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = item.label,
                                color = PortalNavy,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }
                if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FilterChipPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) Color.White else Color(0xFF3B5C96),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color(0xFF1D5FE0) else Color.White)
            .border(1.dp, if (selected) Color(0xFF1D5FE0) else Color(0xFFD5E2F5), RoundedCornerShape(50))
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun InspectionCard(item: InspectionItem, onClick: () -> Unit) {
    val (fg, bg) = statusStyle(item.status)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(20.dp), ambientColor = Color(0x261E5FB0), spotColor = Color(0x261E5FB0))
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button) { onClick() },
        shape = RoundedCornerShape(20.dp),
        color = Color.White
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.code,
                    color = Color(0xFF6A7C98),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (item.priority.isNotBlank()) {
                    PriorityPill(item.priority)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = item.status,
                    color = fg,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(bg)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = item.product,
                color = PortalNavy,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 20.sp
            )
            Text(
                text = "${item.establishment} • ${item.city}",
                color = PortalMuted,
                fontSize = 13.sp,
                lineHeight = 17.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.type,
                    color = Color(0xFF3B5C96),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = fmtSchedule(item.scheduledDate, item.scheduledTime),
                    color = PortalMuted,
                    fontSize = 12.sp
                )
            }

            if (item.violations > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${item.violations} violation${if (item.violations == 1) "" else "s"} recorded",
                    color = Color(0xFFC53030),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = when (item.status) {
                    CaseStatus.ASSIGNED, CaseStatus.SCHEDULED -> "Tap to open and start"
                    CaseStatus.IN_PROGRESS -> "Tap to submit your findings"
                    else -> "Tap for the full track record"
                },
                color = Color(0xFF1D5FE0),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun LoadingBlock() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color(0xFF1D5FE0), strokeWidth = 3.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text("Loading your dashboard…", color = PortalMuted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ErrorBlock(message: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFFFDECEC)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            GlyphIcon(Glyph.WARNING, Color(0xFFD93A3A), Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(message, color = Color(0xFF9B2226), fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Try again",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF1D5FE0))
                    .clickable(role = Role.Button) { onRetry() }
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun EmptyBlock(message: String) {
    Text(
        text = message,
        color = PortalMuted,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp)
    )
}
