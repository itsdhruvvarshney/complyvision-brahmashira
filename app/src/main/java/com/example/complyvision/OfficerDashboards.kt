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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private sealed interface DashState<out T> {
    data object Loading : DashState<Nothing>
    data class Ready<T>(val data: T) : DashState<T>
    data class Failed(val message: String) : DashState<Nothing>
}

private val InspectorHeader = listOf(Color(0xFF1D5FE0), Color(0xFF3B8BFF))
private val AuthorityHeader = listOf(Color(0xFF0B2E59), Color(0xFF1B6B5A))

// ============================================================================
// Inspector dashboard
// ============================================================================

@Composable
fun InspectorDashboardScreen(session: OfficerSession, onLogout: () -> Unit) {
    var reloadKey by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<DashState<InspectorDashboardData>>(DashState.Loading) }
    var filter by rememberSaveable { mutableStateOf("All") }

    LaunchedEffect(session.token, reloadKey) {
        state = DashState.Loading
        state = try {
            DashState.Ready(DashboardRepository.inspector(session.token))
        } catch (e: SessionExpiredException) {
            onLogout()
            return@LaunchedEffect
        } catch (e: ApiException) {
            DashState.Failed(e.userMessage())
        }
    }

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
                        StatItem("In Progress", d.inProgress, Color(0xFFB7791F), Color(0xFFFFF1D6)),
                        StatItem("Completed", d.completed, Color(0xFF1E8E4E), Color(0xFFDDF5E7)),
                        StatItem("Violations", d.violations, Color(0xFFC53030), Color(0xFFFDE4E4))
                    )
                )

                SectionTitle("My Inspections")

                val statuses = listOf("All", "Scheduled", "In Progress", "Completed", "Violation Found")
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
                    shown.forEach { InspectionCard(it, showInspector = false) }
                }
            }
        }
    }
}

// ============================================================================
// Supreme Authority dashboard
// ============================================================================

@Composable
fun AuthorityDashboardScreen(session: OfficerSession, onLogout: () -> Unit) {
    var reloadKey by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<DashState<AuthorityDashboardData>>(DashState.Loading) }

    LaunchedEffect(session.token, reloadKey) {
        state = DashState.Loading
        state = try {
            DashState.Ready(DashboardRepository.authority(session.token))
        } catch (e: SessionExpiredException) {
            onLogout()
            return@LaunchedEffect
        } catch (e: ApiException) {
            DashState.Failed(e.userMessage())
        }
    }

    DashboardFrame(
        session = session,
        headerColors = AuthorityHeader,
        title = "Supreme Authority Dashboard",
        onRefresh = { reloadKey++ },
        onLogout = onLogout
    ) {
        when (val s = state) {
            DashState.Loading -> LoadingBlock()
            is DashState.Failed -> ErrorBlock(s.message) { reloadKey++ }
            is DashState.Ready -> {
                val d = s.data

                CompletionCard(rate = d.completionRate, completed = d.completed, total = d.totalInspections)

                StatGrid(
                    listOf(
                        StatItem("Inspectors", d.inspectors, Color(0xFF1D5FE0), Color(0xFFE3EEFF)),
                        StatItem("In Progress", d.inProgress, Color(0xFFB7791F), Color(0xFFFFF1D6)),
                        StatItem("Scheduled", d.scheduled, Color(0xFF5B3FC4), Color(0xFFEBE5FF)),
                        StatItem("Violations", d.violations, Color(0xFFC53030), Color(0xFFFDE4E4))
                    )
                )

                SectionTitle("Inspector Monitoring")
                if (d.inspectorSummaries.isEmpty()) {
                    EmptyBlock("No inspectors found.")
                } else {
                    d.inspectorSummaries.forEach { InspectorMonitorCard(it) }
                }

                SectionTitle("Recent Inspections")
                if (d.recent.isEmpty()) {
                    EmptyBlock("No inspections recorded yet.")
                } else {
                    d.recent.forEach { InspectionCard(it, showInspector = true) }
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
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = PortalNavy,
        fontSize = 19.sp,
        fontWeight = FontWeight.ExtraBold,
        modifier = Modifier.padding(top = 6.dp)
    )
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

private fun statusColors(status: String): Pair<Color, Color> = when (status) {
    "Completed" -> Color(0xFF1E8E4E) to Color(0xFFDDF5E7)
    "In Progress" -> Color(0xFFB7791F) to Color(0xFFFFF1D6)
    "Violation Found" -> Color(0xFFC53030) to Color(0xFFFDE4E4)
    else -> Color(0xFF1D5FE0) to Color(0xFFE3EEFF)
}

private val IsoDate = DateTimeFormatter.ISO_LOCAL_DATE
private val ShownDate = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)

private fun prettyDate(iso: String): String =
    try { LocalDate.parse(iso, IsoDate).format(ShownDate) } catch (_: Exception) { iso }

@Composable
private fun InspectionCard(item: InspectionItem, showInspector: Boolean) {
    val (fg, bg) = statusColors(item.status)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(20.dp), ambientColor = Color(0x261E5FB0), spotColor = Color(0x261E5FB0)),
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
                text = item.establishment,
                color = PortalNavy,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 20.sp
            )
            Text(
                text = "${item.city} • ${item.commodity}",
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
                    text = prettyDate(item.scheduledDate),
                    color = PortalMuted,
                    fontSize = 12.sp
                )
            }

            if (showInspector && item.inspectorName.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = "Inspector: ${item.inspectorName}", color = PortalMuted, fontSize = 12.sp)
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
        }
    }
}

@Composable
private fun CompletionCard(rate: Int, completed: Int, total: Int) {
    val safeRate = rate.coerceIn(0, 100)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(22.dp), ambientColor = Color(0x331B6B5A), spotColor = Color(0x331B6B5A)),
        shape = RoundedCornerShape(22.dp),
        color = Color.White
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "$safeRate%",
                    color = Color(0xFF1B6B5A),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "inspection completion rate",
                    color = PortalMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            ProgressBar(fraction = safeRate / 100f, color = Color(0xFF1B9E77))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$completed of $total inspections completed",
                color = PortalMuted,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFE6EEF8))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}

@Composable
private fun InspectorMonitorCard(i: InspectorSummary) {
    val fraction = if (i.assigned == 0) 0f else i.completed.toFloat() / i.assigned
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(20.dp), ambientColor = Color(0x261E5FB0), spotColor = Color(0x261E5FB0)),
        shape = RoundedCornerShape(20.dp),
        color = Color.White
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE3EEFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = i.name.firstOrNull()?.uppercase() ?: "?",
                        color = Color(0xFF1D5FE0),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(i.name, color = PortalNavy, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
                    Text("${i.officerId} • ${i.office}", color = PortalMuted, fontSize = 12.sp, lineHeight = 15.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            ProgressBar(fraction = fraction, color = Color(0xFF1D5FE0))
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                MiniStat("Assigned", i.assigned, PortalNavy)
                MiniStat("Done", i.completed, Color(0xFF1E8E4E))
                MiniStat("Active", i.inProgress, Color(0xFFB7791F))
                MiniStat("Violations", i.violations, Color(0xFFC53030))
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), color = color, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = PortalMuted, fontSize = 11.sp)
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
