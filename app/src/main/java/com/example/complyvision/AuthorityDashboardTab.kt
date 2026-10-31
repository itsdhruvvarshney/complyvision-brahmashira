package com.example.complyvision

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AuthorityDashboardTab(
    store: AuthorityStore,
    onOpenInspector: (String) -> Unit,
    onSeeAllInspectors: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        WelcomeHero(store.session)

        when (val state = store.dashboard) {
            Load.Loading -> LoadingPanel("Loading your dashboard…")
            is Load.Failed -> ErrorPanel(state.message, { store.loadDashboard() }, Modifier.padding(horizontal = 16.dp))
            is Load.Ready -> {
                val d = state.data
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    StatsRow(d)

                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth >= 372.dp) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                                OverviewCard(d, store, Modifier.weight(1.62f).fillMaxHeight())
                                TrendCard(d, Modifier.weight(1f).fillMaxHeight(), stretch = true)
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OverviewCard(d, store, Modifier.fillMaxWidth())
                                TrendCard(d, Modifier.fillMaxWidth(), stretch = false)
                            }
                        }
                    }

                    PerformanceCard(d, onOpenInspector, onSeeAllInspectors)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Welcome hero: the Supreme Authority artwork behind the greeting
// ---------------------------------------------------------------------------

@Composable
private fun WelcomeHero(session: OfficerSession) {
    Box(Modifier.fillMaxWidth().height(156.dp)) {
        Image(
            painter = painterResource(R.drawable.authority_hero_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.CenterEnd,
            modifier = Modifier.matchParentSize()
        )
        // Fade the artwork into the page so there is no visible edge
        Box(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, AC.PageMid)))
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.66f)
                .padding(start = 20.dp, top = 6.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text("Welcome,", color = AC.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = session.designation,
                color = AC.Blue,
                fontSize = 28.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "National Compliance Oversight Dashboard",
                color = AC.Muted,
                fontSize = 13.sp,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${session.name} • ${session.officerId}",
                color = AC.Faint,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Five headline numbers
// ---------------------------------------------------------------------------

@Composable
private fun StatsRow(d: AuthorityDashboard) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        StatTile(
            Modifier.weight(1f), AIcon.PEOPLE, AC.Blue, AC.BlueSoft,
            fmtInt(d.inspectorsTotal), "Total Inspectors", null, AC.Blue, AC.BlueSoft,
            listOf(Color(0xFFEAF3FF), Color(0xFFD9E9FF))
        )
        StatTile(
            Modifier.weight(1f), AIcon.CLIPBOARD, AC.Blue, AC.BlueSoft,
            fmtInt(d.total), "Total Inspections", null, AC.Blue, AC.BlueSoft,
            listOf(Color(0xFFEAF3FF), Color(0xFFD9E9FF))
        )
        StatTile(
            Modifier.weight(1f), AIcon.CHECK_CIRCLE, Color.White, AC.Green,
            fmtInt(d.passed), "Passed", fmtPctOf(d.passed, d.total), AC.Green, Color(0xFFCDF1DB),
            listOf(Color(0xFFEAF9F0), Color(0xFFD6F3E2))
        )
        StatTile(
            Modifier.weight(1f), AIcon.CROSS_CIRCLE, Color.White, AC.Red,
            fmtInt(d.failed), "Failed", fmtPctOf(d.failed, d.total), AC.Red, Color(0xFFFAD3D3),
            listOf(Color(0xFFFEEEEE), Color(0xFFFBDADA))
        )
        StatTile(
            Modifier.weight(1f), AIcon.HOURGLASS, Color.White, AC.Amber,
            fmtInt(d.review), "Under Review", fmtPctOf(d.review, d.total), Color(0xFFB7791F), Color(0xFFFFE3B3),
            listOf(Color(0xFFFFF6E5), Color(0xFFFFEACB))
        )
    }
}

@Composable
private fun StatTile(
    modifier: Modifier,
    icon: AIcon,
    iconTint: Color,
    iconBg: Color,
    value: String,
    label: String,
    pill: String?,
    pillFg: Color,
    pillBg: Color,
    gradient: List<Color>
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(Brush.verticalGradient(gradient))
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(iconBg),
            contentAlignment = Alignment.Center
        ) { AIconView(icon, iconTint, Modifier.size(16.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(
            text = value,
            color = AC.Ink,
            fontSize = if (value.length > 5) 14.sp else 18.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
        Text(label, color = AC.Muted, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        if (pill != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = pill,
                color = pillFg,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(pillBg).padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Inspection overview (donut + legend + period picker)
// ---------------------------------------------------------------------------

@Composable
private fun OverviewCard(d: AuthorityDashboard, store: AuthorityStore, modifier: Modifier) {
    GlassCard(modifier = modifier, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(AC.BlueSoft),
                contentAlignment = Alignment.Center
            ) { AIconView(AIcon.CHART, AC.Blue, Modifier.size(15.dp)) }
            Spacer(Modifier.width(8.dp))
            Text(
                "Inspection Overview",
                color = AC.Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold,
                lineHeight = 17.sp, modifier = Modifier.weight(1f)
            )
            PeriodPicker(store.days) { store.changeDays(it) }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DonutChart(
                values = listOf(d.passed.toFloat(), d.failed.toFloat(), d.review.toFloat()),
                colors = listOf(AC.Green, AC.Red, AC.Amber),
                modifier = Modifier.size(92.dp),
                thickness = 14.dp
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(fmtInt(d.total), color = AC.Ink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Text("Inspections", color = AC.Muted, fontSize = 9.sp)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                LegendRow("Passed", d.passed, d.total, AC.Green)
                LegendRow("Failed", d.failed, d.total, AC.Red)
                LegendRow("Under Review", d.review, d.total, AC.Amber)
            }
        }
    }
}

@Composable
private fun LegendRow(label: String, count: Int, total: Int, color: Color) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, color = AC.Ink, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Text(
            text = "${fmtInt(count)} (${fmtPctOf(count, total)})",
            color = AC.Muted, fontSize = 11.sp, maxLines = 1,
            modifier = Modifier.padding(start = 14.dp)
        )
    }
}

@Composable
private fun PeriodPicker(days: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White)
                .clickable(role = Role.Button) { open = true }
                .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(periodLabel(days), color = AC.Muted, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(2.dp))
            AIconView(AIcon.CHEVRON_DOWN, AC.Muted, Modifier.size(12.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PeriodOptions.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            periodLabel(option),
                            fontWeight = if (option == days) FontWeight.Bold else FontWeight.Normal,
                            color = if (option == days) AC.Blue else AC.Ink
                        )
                    },
                    onClick = { open = false; onPick(option) }
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Compliance trend
// ---------------------------------------------------------------------------

@Composable
private fun TrendCard(d: AuthorityDashboard, modifier: Modifier, stretch: Boolean) {
    GlassCard(modifier = modifier, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AIconView(AIcon.TREND_UP, AC.Blue, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Compliance Trend", color = AC.Ink, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(10.dp))

        val delta = d.delta
        if (delta == null) {
            Text("—", color = AC.Faint, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (d.days == 0) "All-time view has no earlier period to compare" else "No earlier results to compare yet",
                color = AC.Muted, fontSize = 11.5.sp, lineHeight = 15.sp
            )
        } else {
            val up = delta >= 0
            val tone = if (up) AC.Green else AC.Red
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = (if (up) "+" else "") + String.format(java.util.Locale.US, "%.1f%%", delta),
                    color = tone, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1
                )
                Spacer(Modifier.width(4.dp))
                AIconView(if (up) AIcon.ARROW_UP else AIcon.ARROW_DOWN, tone, Modifier.size(17.dp))
            }
            Text(
                text = "vs previous ${if (d.days == 365) "12 months" else "${d.days} days"}",
                color = AC.Muted, fontSize = 11.5.sp, lineHeight = 15.sp
            )
        }
        Text(
            text = "Compliance rate ${fmtPct(d.complianceRate)}",
            color = AC.Faint, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 2.dp)
        )

        Spacer(Modifier.height(8.dp))
        val points = d.series.map { if (it.total > 0) it.rate else null }
        Sparkline(
            values = points,
            color = if ((delta ?: 0.0) >= 0) AC.Green else AC.Red,
            modifier = if (stretch) Modifier.fillMaxWidth().weight(1f, fill = true).heightIn(min = 54.dp)
            else Modifier.fillMaxWidth().height(72.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Inspector performance table
// ---------------------------------------------------------------------------

private const val COL_NAME = 2.3f
private const val COL_NUM = 0.82f
private const val COL_REVIEW = 0.95f
private const val COL_RATE = 1.25f
private const val COL_MORE = 0.5f

@Composable
private fun PerformanceCard(d: AuthorityDashboard, onOpen: (String) -> Unit, onSeeAll: () -> Unit) {
    var descending by rememberSaveable { mutableStateOf(true) }
    val rows = remember(d.performers, descending) {
        (if (descending) d.performers.sortedByDescending { it.total } else d.performers.sortedBy { it.total }).take(5)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            AIconView(AIcon.USER, AC.Blue, Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Inspector Performance", color = AC.Ink, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { onSeeAll() }.padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("View All", color = AC.Blue, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                AIconView(AIcon.ARROW_RIGHT, AC.Blue, Modifier.size(14.dp))
            }
        }

        GlassCard(padding = 0.dp, corner = 20.dp) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderCell("Inspector", Modifier.weight(COL_NAME), TextAlign.Start)
                Row(
                    modifier = Modifier.weight(COL_NUM).clickable(role = Role.Button) { descending = !descending },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Total", color = AC.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    AIconView(AIcon.SORT, AC.Blue, Modifier.size(11.dp))
                }
                HeaderCell("Passed", Modifier.weight(COL_NUM))
                HeaderCell("Failed", Modifier.weight(COL_NUM))
                HeaderCell("Under\nReview", Modifier.weight(COL_REVIEW))
                HeaderCell("Pass Rate", Modifier.weight(COL_RATE))
                Spacer(Modifier.weight(COL_MORE))
            }
            HorizontalRule()

            if (rows.isEmpty()) {
                EmptyPanel("No inspectors yet", "Inspectors reporting to you will appear here.", AIcon.PEOPLE)
            } else {
                rows.forEachIndexed { index, row ->
                    PerformanceRowView(row, onOpen)
                    if (index < rows.lastIndex) HorizontalRule(Modifier.padding(horizontal = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier, align: TextAlign = TextAlign.Center) {
    Text(
        text = text, color = AC.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        lineHeight = 12.sp, textAlign = align, modifier = modifier
    )
}

@Composable
private fun PerformanceRowView(row: PerformanceRow, onOpen: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val (statusFg, _) = inspectorStatusStyle(row.status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { onOpen(row.officerId) }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(COL_NAME), verticalAlignment = Alignment.CenterVertically) {
            Avatar(row.name, 30.dp, fontSize = 10)
            Spacer(Modifier.width(7.dp))
            Column {
                Text(row.name, color = AC.Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = if (row.status == InspectorStatus.ACTIVE) "ID: ${row.officerId}" else "${row.officerId} • ${inspectorStatusLabel(row.status)}",
                    color = if (row.status == InspectorStatus.ACTIVE) AC.Muted else statusFg,
                    fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        NumCell(row.total, AC.Ink, Modifier.weight(COL_NUM))
        NumCell(row.passed, AC.Green, Modifier.weight(COL_NUM))
        NumCell(row.failed, AC.Red, Modifier.weight(COL_NUM))
        NumCell(row.review, AC.Amber, Modifier.weight(COL_REVIEW))
        Box(Modifier.weight(COL_RATE), contentAlignment = Alignment.Center) {
            val tone = rateColor(row.passRate)
            Text(
                text = fmtPct(row.passRate),
                color = tone, fontSize = 10.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(tone.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 4.dp)
            )
        }
        Box(Modifier.weight(COL_MORE), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).clickable(role = Role.Button) { menu = true },
                contentAlignment = Alignment.Center
            ) { AIconView(AIcon.MORE, AC.Muted, Modifier.size(16.dp)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("View full profile") }, onClick = { menu = false; onOpen(row.officerId) })
            }
        }
    }
}

@Composable
private fun NumCell(value: Int, color: Color, modifier: Modifier) {
    Text(
        text = fmtInt(value), color = color, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center, maxLines = 1, modifier = modifier
    )
}
