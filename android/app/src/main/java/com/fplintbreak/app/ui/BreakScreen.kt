package com.fplintbreak.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.material3.FilterChip
import com.fplintbreak.app.data.OnPitch
import com.fplintbreak.app.data.RestLevel
import java.time.LocalDate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.fplintbreak.app.data.PlayerCandidate
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fplintbreak.app.data.BreakMatch
import com.fplintbreak.app.data.CallUpStatus
import com.fplintbreak.app.data.DataSource
import com.fplintbreak.app.data.IntlMatch
import com.fplintbreak.app.data.Involvement
import com.fplintbreak.app.data.PlayerReport
import com.fplintbreak.app.data.Risk
import com.fplintbreak.app.data.Severity
import com.fplintbreak.app.data.TeamReport
import com.fplintbreak.app.ui.theme.LocalStatusColors
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK).withZone(ZoneId.systemDefault())
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.UK).withZone(ZoneId.systemDefault())
private fun fmtDay(iso: String) = dayFormat.format(Instant.parse(iso))
private fun fmtTime(iso: String) = timeFormat.format(Instant.parse(iso))

/** "today 19:45", "tomorrow 17:00", or "Sat 3 Oct" further out (phone's local time). */
private fun fmtWhen(iso: String): String {
    val day = Instant.parse(iso).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (day) {
        today -> "today ${fmtTime(iso)}"
        today.plusDays(1) -> "tomorrow ${fmtTime(iso)}"
        else -> fmtDay(iso)
    }
}

/** Like fmtWhen, but always with the kick-off time. */
private fun fmtWhenAt(iso: String) = fmtWhen(iso).let { if (':' in it) it else "$it ${fmtTime(iso)}" }

private fun fmtDays(d: Double?) = d?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "?"

private fun fmtThousands(n: Int) = (if (n > 0) "+" else if (n < 0) "−" else "") + "${kotlin.math.abs(n) / 1000}k"

private fun onPitchLabel(o: OnPitch) = when (o) {
    OnPitch.PLAYING -> "on the pitch"
    OnPitch.SUBBED_OFF -> "subbed off"
    OnPitch.BENCH -> "on the bench"
    OnPitch.NOT_IN_SQUAD -> "not in the matchday squad"
    OnPitch.UNKNOWN -> "line-ups not out yet"
}

// FPL's fixture difficulty colours.
private fun fdrColors(d: Int) = when (d) {
    1 -> Color(0xFF257D5A) to Color.White
    2 -> Color(0xFF00FF86) to Color(0xFF1C1D21)
    3 -> Color(0xFFE7E7E7) to Color(0xFF1C1D21)
    4 -> Color(0xFFFF1751) to Color.White
    else -> Color(0xFF80072D) to Color.White
}

private fun riskLabel(r: Risk) = when (r) {
    Risk.RED -> "Injury concern"
    Risk.AMBER -> "Keep an eye"
    Risk.GREEN -> "All clear"
}

private fun callUpLabel(s: CallUpStatus) = when (s) {
    CallUpStatus.CALLED -> "Yes"
    CallUpStatus.WITHDRAWN -> "Withdrew"
    CallUpStatus.NOT_CALLED -> "No"
    CallUpStatus.UNKNOWN -> "Unknown"
}

@Composable
private fun riskColor(r: Risk): Color {
    val c = LocalStatusColors.current
    return when (r) {
        Risk.RED -> c.red
        Risk.AMBER -> c.amber
        Risk.GREEN -> c.green
    }
}

/** A summary chip the list can be filtered by. Each one matches a set of players. */
enum class ChipFilter { CALLED, WITHDRAWN, NOT_CALLED, RED, AMBER, GREEN }

private fun PlayerReport.matches(f: ChipFilter?) = when (f) {
    null -> true
    ChipFilter.CALLED -> callUp.status == CallUpStatus.CALLED
    ChipFilter.WITHDRAWN -> callUp.status == CallUpStatus.WITHDRAWN
    ChipFilter.NOT_CALLED -> callUp.status == CallUpStatus.NOT_CALLED || callUp.status == CallUpStatus.UNKNOWN
    ChipFilter.RED -> risk == Risk.RED
    ChipFilter.AMBER -> risk == Risk.AMBER
    ChipFilter.GREEN -> risk == Risk.GREEN
}

private val POSITIONS = listOf("All" to null, "GK" to "GKP", "DEF" to "DEF", "MID" to "MID", "FWD" to "FWD")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreakScreen(vm: BreakViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<ChipFilter?>(null) }
    var position by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Break Tracker", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading && state.report != null,
            onRefresh = vm::load,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Text(
                        "Did your players come back in one piece?",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    TeamIdField(vm.teamId, state.loading, vm::onTeamIdChange, vm::load)
                    Spacer(Modifier.height(16.dp))
                    state.error?.let { ErrorBox(it) }
                    if (state.loading && state.report == null) {
                        Text(
                            "Fetching your squad from FPL and every player's international matches from FotMob… the first load can take up to half a minute.",
                            color = LocalStatusColors.current.muted,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                state.report?.let { r ->
                    item { ViewTabs(state.view, vm::showView) }
                    when (state.view) {
                        View.SQUAD -> report(
                            r,
                            filter,
                            position,
                            onFilter = { f -> filter = if (filter == f) null else f },
                            onPosition = { position = it },
                            onFixMatch = vm::openMatchFix,
                        )
                        View.LEAGUE -> item {
                            LeagueSection(state.league, vm::selectLeague, vm::selectLimit, vm::compareLeague)
                        }
                    }
                }
            }
        }
    }

    state.matchFix?.let { fix ->
        MatchFixDialog(
            fix = fix,
            onQueryChange = vm::onMatchQueryChange,
            onSearch = vm::searchMatch,
            onPick = vm::pickMatch,
            onReset = vm::resetMatch,
            onDismiss = vm::closeMatchFix,
        )
    }
}

@Composable
private fun MatchFixDialog(
    fix: MatchFix,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPick: (PlayerCandidate) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalStatusColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which FotMob player is ${fix.player.webName}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${fix.player.position} · ${fix.player.club} in FPL. Pick the matching player; " +
                        "their international matches will be used from now on.",
                    fontSize = 13.sp,
                    color = c.muted,
                )
                OutlinedTextField(
                    value = fix.query,
                    onValueChange = onQueryChange,
                    label = { Text("Search FotMob") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                fix.error?.let { Text(it, color = c.red, fontSize = 13.sp) }
                when {
                    fix.searching -> Text("Searching…", color = c.muted, fontSize = 13.sp)
                    fix.results?.isEmpty() == true -> Text("No players found. Try another spelling.", color = c.muted, fontSize = 13.sp)
                }
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    fix.results.orEmpty().forEach { candidate ->
                        val current = candidate.fotmobId == fix.player.fotmob.id
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(candidate) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(candidate.name, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                                Text(candidate.team ?: "No club listed", color = c.muted, fontSize = 12.sp)
                            }
                            if (current) Text("Current", color = c.green, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        dismissButton = {
            if (fix.player.fotmob.confidence == "override") {
                TextButton(onClick = onReset) { Text("Reset to automatic") }
            }
        },
    )
}

@Composable
private fun ViewTabs(current: View, onSelect: (View) -> Unit) {
    TabRow(
        selectedTabIndex = current.ordinal,
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.padding(bottom = 16.dp),
    ) {
        Tab(selected = current == View.SQUAD, onClick = { onSelect(View.SQUAD) }, text = { Text("My squad") })
        Tab(selected = current == View.LEAGUE, onClick = { onSelect(View.LEAGUE) }, text = { Text("Mini-league") })
    }
}

@Composable
private fun TeamIdField(teamId: String, loading: Boolean, onChange: (String) -> Unit, onLoad: () -> Unit) {
    val focus = LocalFocusManager.current
    val onSubmit = {
        focus.clearFocus() // closes the keyboard so the results are visible
        onLoad()
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = teamId,
            onValueChange = onChange,
            label = { Text("FPL team ID") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onSubmit, enabled = teamId.isNotEmpty() && !loading, modifier = Modifier.height(56.dp)) {
            Text(if (loading) "Checking…" else "Check")
        }
    }
}

@Composable
private fun ErrorBox(message: String) {
    val c = LocalStatusColors.current
    Text(
        message,
        color = c.red,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(c.redBg, RoundedCornerShape(10.dp))
            .padding(12.dp),
    )
}

private fun LazyListScope.report(
    report: TeamReport,
    filter: ChipFilter?,
    position: String?,
    onFilter: (ChipFilter) -> Unit,
    onPosition: (String?) -> Unit,
    onFixMatch: (PlayerReport) -> Unit,
) {
    item { Summary(report, filter, onFilter) }
    item { PositionTabs(report, filter, position, onPosition, onClear = { filter?.let(onFilter) }) }
    val shown = report.players.filter { it.matches(filter) && (position == null || it.position == position) }
    if (shown.isEmpty()) {
        item { Text("No players match this filter.", color = LocalStatusColors.current.muted, modifier = Modifier.padding(8.dp)) }
    }
    val (bench, starters) = shown.partition { it.isBench }
    listOf("Starting XI" to starters, "Bench" to bench).forEach { (label, group) ->
        if (group.isEmpty()) return@forEach
        item(key = "label-$label") { GroupLabel(label) }
        items(group, key = { it.fplId }) { PlayerRow(it, onFixMatch) }
    }
    item { SourcesSection(report.sources, report.generatedAt) }
    item {
        Text(
            "Squad from GW${report.team.picksFromEvent} picks. Call-ups cross-checked between FotMob and Transfermarkt. " +
                "Pull down to refresh.",
            style = MaterialTheme.typography.bodySmall,
            color = LocalStatusColors.current.muted,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PositionTabs(report: TeamReport, filter: ChipFilter?, position: String?, onPosition: (String?) -> Unit, onClear: () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    ) {
        POSITIONS.forEach { (label, code) ->
            val n = report.players.count { it.matches(filter) && (code == null || it.position == code) }
            FilterChip(selected = position == code, onClick = { onPosition(code) }, label = { Text("$label  $n") })
        }
        if (filter != null) {
            TextButton(onClick = onClear) { Text("Clear filter ✕") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(report: TeamReport, filter: ChipFilter?, onFilter: (ChipFilter) -> Unit) {
    val c = LocalStatusColors.current
    val ps = report.players
    val away = ps.filter { it.isAway }
    // Several players can share a national team, so count each fixture once.
    val remaining = away.flatMap { it.upcoming }.distinctBy { it.fotmobMatchId }
    val nations = away.mapNotNull { it.nationalTeam }.distinct().size
    val lastMatch = remaining.maxByOrNull { it.date }

    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(report.team.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "${report.team.manager} · GW${report.window.afterEvent} → GW${report.window.beforeEvent} break",
            color = c.muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 4.dp)) {
            if (lastMatch != null) {
                Box(Modifier.padding(top = 7.dp).size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Break in progress — ${remaining.size} matches to come across $nations national teams, " +
                        "last one ${fmtDay(lastMatch.date)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text("Break over — no national-team matches left for your players", style = MaterialTheme.typography.bodyMedium)
            }
        }
        val live = ps.filter { it.live != null }
        if (live.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                LiveBadge("LIVE")
                Spacer(Modifier.width(8.dp))
                Text(
                    "${live.joinToString { it.webName }} ${if (live.size == 1) "is" else "are"} playing right now — refreshing automatically",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        // Each group covers all 15 players once, so its numbers add up to the squad.
        StatsLabel("Call-ups")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val withdrew = ps.count { it.callUp.status == CallUpStatus.WITHDRAWN }
            Stat(away.size, "With national team", null, filter == ChipFilter.CALLED) { onFilter(ChipFilter.CALLED) }
            Stat(withdrew, "Withdrew", if (withdrew > 0) c.amber to c.amberBg else null, filter == ChipFilter.WITHDRAWN) {
                onFilter(ChipFilter.WITHDRAWN)
            }
            Stat(
                ps.count { it.callUp.status == CallUpStatus.NOT_CALLED || it.callUp.status == CallUpStatus.UNKNOWN },
                "Not called up",
                null,
                filter == ChipFilter.NOT_CALLED,
            ) { onFilter(ChipFilter.NOT_CALLED) }
        }
        StatsLabel("Fitness")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Risk.entries.forEach { r ->
                val n = ps.count { it.risk == r }
                val tone = when (r) {
                    Risk.RED -> c.red to c.redBg
                    Risk.AMBER -> c.amber to c.amberBg
                    Risk.GREEN -> c.green to c.greenBg
                }
                val f = ChipFilter.valueOf(r.name)
                Stat(n, riskLabel(r), if (n > 0) tone else null, filter == f) { onFilter(f) }
            }
        }
        // Not a count: these players are a subset of the ones away, so name them instead.
        StatsLabel("Rest before GW${report.window.beforeEvent}")
        val tired = ps.filter { it.rest.level == RestLevel.TIGHT || it.rest.level == RestLevel.WATCH }
            .sortedBy { it.rest.restDays ?: 99.0 }
        if (tired.isEmpty()) {
            Text("Everyone away gets 4+ days before their club game.", fontSize = 13.sp, color = c.muted)
        } else {
            Text(
                tired.joinToString { p ->
                    val extra = listOfNotNull(
                        p.rest.longTrip?.let { "long trip" },
                        p.rest.breakMinutes.takeIf { it >= 270 }?.let { "$it'" },
                    )
                    "${p.webName} (${fmtDays(p.rest.restDays)} days${extra.joinToString("") { ", $it" }})"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (tired.any { it.rest.level == RestLevel.TIGHT }) c.red else c.amber,
            )
        }
    }
}

@Composable
private fun StatsLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = LocalStatusColors.current.muted,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun LiveBadge(text: String) {
    val c = LocalStatusColors.current
    val pulse = rememberInfiniteTransition(label = "live")
    val alpha by pulse.animateFloat(1f, 0.55f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha")
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .alpha(alpha)
            .background(c.red, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun FdrChip(d: Int) {
    val (bg, fg) = fdrColors(d)
    Text(
        "$d",
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = fg,
        modifier = Modifier.background(bg, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun RestChip(p: PlayerReport) {
    val c = LocalStatusColors.current
    val (fg, bg) = when (p.rest.level) {
        RestLevel.TIGHT -> c.red to c.redBg
        RestLevel.WATCH -> c.amber to c.amberBg
        else -> c.muted to MaterialTheme.colorScheme.background
    }
    Text(
        "${fmtDays(p.rest.restDays)}d rest${if (p.rest.longTrip != null) " ✈" else ""}",
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = fg,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

@Composable
private fun Stat(n: Int, label: String, tone: Pair<Color, Color>?, selected: Boolean, onClick: () -> Unit) {
    val c = LocalStatusColors.current
    val (fg, bg) = tone ?: (MaterialTheme.colorScheme.onSurface to MaterialTheme.colorScheme.surface)
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(bg, shape)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else if (tone == null) c.border else Color.Transparent,
                shape,
            )
            .clickable(enabled = n > 0 || selected, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text("$n ", fontWeight = FontWeight.Bold, color = fg, fontSize = 13.sp)
        Text(label, color = if (tone == null) c.muted else fg, fontSize = 13.sp)
    }
}

@Composable
private fun GroupLabel(label: String) {
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = LocalStatusColors.current.muted,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp, start = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerRow(p: PlayerReport, onFixMatch: (PlayerReport) -> Unit) {
    val c = LocalStatusColors.current
    var open by rememberSaveable(p.fplId) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    val edge = when (p.risk) {
        Risk.RED -> c.red
        Risk.AMBER -> c.amber
        Risk.GREEN -> Color.Transparent
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, c.border),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        Row(Modifier.drawBehind { drawRect(edge, size = Size(3.dp.toPx(), size.height)) }) {
            Column(Modifier.weight(1f).padding(start = 3.dp)) {
                Column(
                    Modifier
                        .clickable { open = !open }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(p.webName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                if (p.isCaptain) Tag("C")
                                if (p.isViceCaptain) Tag("VC")
                            }
                            val nation = p.nationalTeam?.let { " · $it" + if (p.nation?.capped == false) " (uncapped)" else "" }.orEmpty()
                            Text(
                                "${p.position} · ${p.club}$nation",
                                color = c.muted,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        CallUpPill(p.callUp.status)
                        if (p.callUp.agreement == "conflict") Text(" ⚠", color = c.amber)
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.size(9.dp).background(riskColor(p.risk), CircleShape))
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (open) "Hide details" else "Show details",
                            tint = c.muted,
                            modifier = Modifier.rotate(rotation),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val done = p.played
                        Box(Modifier.weight(1f)) {
                            if (done.isEmpty() && p.live == null) {
                                Text("—", color = c.muted)
                            } else {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    done.forEach { InvolvementChip(it) }
                                    p.live?.let { LiveBadge("LIVE ${it.minute}") }
                                    if (done.isNotEmpty()) {
                                        // Minutes, then matches featured in out of matches played.
                                        val minutes = done.sumOf { it.minutes ?: 0 }
                                        val featured = done.count { it.involvement == Involvement.STARTED || it.involvement == Involvement.SUB }
                                        Text(
                                            "$minutes' · $featured/${done.size}",
                                            color = c.muted,
                                            fontSize = 12.sp,
                                            modifier = Modifier.align(Alignment.CenterVertically),
                                        )
                                    }
                                }
                            }
                        }
                        val next = p.upcoming
                        if (p.isAway && next.isNotEmpty()) {
                            Text("${next.size}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "next ${fmtWhen(next[0].date)} ${if (next[0].home) "v" else "@"} ${next[0].opponent}",
                                color = c.muted,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Text("—", color = c.muted)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val f = p.nextFixtures.firstOrNull()
                        if (f == null) {
                            Text("No GW fixture", color = c.muted, fontSize = 13.sp)
                        } else {
                            Text("${if (f.home) "v" else "@"} ${f.opponent}", fontSize = 13.sp)
                            FdrChip(f.difficulty)
                            if (p.nextFixtures.size > 1) Text("+${p.nextFixtures.size - 1}", color = c.muted, fontSize = 12.sp)
                        }
                        if (p.rest.level != RestLevel.NA) RestChip(p)
                    }
                }
                AnimatedVisibility(open) { PlayerDetails(p, onFixMatch) }
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun CallUpPill(status: CallUpStatus) {
    val c = LocalStatusColors.current
    val (fg, bg) = when (status) {
        CallUpStatus.CALLED -> c.green to c.greenBg
        CallUpStatus.WITHDRAWN -> c.amber to c.amberBg
        else -> c.muted to MaterialTheme.colorScheme.background
    }
    Text(
        callUpLabel(status),
        color = fg,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun InvolvementChip(f: BreakMatch) {
    val c = LocalStatusColors.current
    val text = when (f.involvement) {
        Involvement.STARTED -> "${f.minutes}'"
        Involvement.SUB -> "+${f.minutes}'"
        Involvement.BENCH -> "Bench"
        Involvement.ABSENT -> "Not selected" // called up, but not in this match's squad (e.g. Germany's split squad)
        Involvement.UPCOMING -> ""
    }
    val solid = f.involvement == Involvement.STARTED
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = if (solid || f.involvement == Involvement.SUB) FontWeight.Bold else FontWeight.Medium,
        color = when {
            solid -> MaterialTheme.colorScheme.surface
            f.involvement == Involvement.SUB -> MaterialTheme.colorScheme.onSurface
            else -> c.muted
        },
        modifier = Modifier
            .background(if (solid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.background, RoundedCornerShape(6.dp))
            .then(if (solid) Modifier else Modifier.border(1.dp, c.border, RoundedCornerShape(6.dp)))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = LocalStatusColors.current.muted,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun PlayerDetails(p: PlayerReport, onFixMatch: (PlayerReport) -> Unit) {
    val c = LocalStatusColors.current
    val uri = LocalUriHandler.current
    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        p.live?.let { l ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveBadge("LIVE ${l.minute}")
                Text(
                    "${if (l.home) "v" else "@"} ${l.opponent}${l.score?.let { " · $it" }.orEmpty()} — ${onPitchLabel(l.onPitch)}",
                    fontSize = 14.sp,
                )
            }
        }
        SectionTitle("International matches")
        if (p.matches.isEmpty()) {
            Text("Didn't feature in any match during the break.", color = c.muted, fontSize = 13.sp)
        }
        p.matches.forEach { MatchLine(it) }

        val next = p.upcoming
        if (next.isNotEmpty()) {
            SectionTitle(if (p.isAway) "Still to play" else "${p.nationalTeam} still to play (not in the squad)")
            next.forEach { f ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, c.border, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("${if (f.home) "v" else "@"} ${f.opponent}", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Text("${fmtWhenAt(f.date)} · ${f.competition}", color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        SectionTitle("Back to club")
        p.nextFixtures.forEach { f ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, c.border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("${if (f.home) "v" else "@"} ${f.opponent}", fontSize = 14.sp)
                FdrChip(f.difficulty)
                Spacer(Modifier.weight(1f))
                Text(fmtWhenAt(f.kickoff), color = c.muted, fontSize = 12.sp)
            }
        }
        if (p.rest.note.isNotEmpty()) {
            Text(
                p.rest.note,
                fontSize = 13.sp,
                color = when (p.rest.level) {
                    RestLevel.TIGHT -> c.red
                    RestLevel.WATCH -> c.amber
                    else -> c.muted
                },
            )
        }
        val pr = p.price
        val change = if (pr.changeThisGw != 0.0) " (${if (pr.changeThisGw > 0) "+" else ""}${"%.1f".format(pr.changeThisGw)} since the deadline)" else ""
        val heavy = when {
            pr.netTransfers <= -50_000 -> " (heavy transfers out)"
            pr.netTransfers >= 100_000 -> " (heavy transfers in)"
            else -> ""
        }
        Text("£${"%.1f".format(pr.now)}m$change · net transfers ${fmtThousands(pr.netTransfers)}$heavy", color = c.muted, fontSize = 12.sp)

        SectionTitle("Call-up sources")
        if (p.callUp.checks.isEmpty()) Text("No source could place this player.", color = c.muted, fontSize = 13.sp)
        p.callUp.checks.forEach { check ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceTag(check.source)
                CallUpPill(check.status)
                Text(check.detail, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
            check.url?.let { url ->
                TextButton(onClick = { uri.openUri(url) }, contentPadding = PaddingValues(0.dp)) { Text("Open source", fontSize = 13.sp) }
            }
        }
        when {
            p.callUp.agreement == "agree" -> Text("✓ Sources agree", color = c.green, fontSize = 13.sp)
            p.callUp.agreement == "conflict" ->
                Text("⚠ Sources disagree — check the latest squad news before your transfers.", color = c.amber, fontSize = 13.sp)
            p.callUp.checks.isNotEmpty() -> Text("Only one source could check this player.", color = c.muted, fontSize = 12.sp)
        }

        if (p.signals.isNotEmpty()) {
            SectionTitle("Injury & availability")
            p.signals.forEach { s ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceTag(if (s.source == "fpl") "FPL" else "FotMob")
                    Text(
                        s.text,
                        fontSize = 13.sp,
                        color = when (s.severity) {
                            Severity.HIGH -> c.red
                            Severity.MEDIUM -> c.amber
                            Severity.INFO -> c.muted
                        },
                    )
                }
            }
        }
        if (p.confirmedByBoth) Text("Confirmed by both FotMob and FPL", color = c.red, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

        SectionTitle("FotMob player")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.fotmob.name ?: "Not found on FotMob", fontSize = 14.sp)
                val how = when (p.fotmob.confidence) {
                    "override" -> "Picked by you"
                    "high" -> "Matched automatically by name and club"
                    "low" -> "Best guess — please check"
                    else -> "The app couldn't match this player"
                }
                Text(how, color = if (p.fotmob.confidence == "low") c.amber else c.muted, fontSize = 12.sp)
            }
            TextButton(onClick = { onFixMatch(p) }) {
                Text(if (p.fotmob.id == null) "Find on FotMob" else "Wrong player? Change", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SourceTag(text: String) {
    val c = LocalStatusColors.current
    Text(
        text.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = c.muted,
        modifier = Modifier.border(1.dp, c.border, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun MatchLine(m: IntlMatch) {
    val c = LocalStatusColors.current
    val involvement = when {
        m.injuredOff -> "Injured, off ${m.subbedOffMinute ?: "?"}'"
        !m.played -> if (m.onBench) "Unused sub" else "Not in squad"
        !m.started -> "Sub on ${m.subbedOnMinute}'"
        m.subbedOffMinute != null -> "Off ${m.subbedOffMinute}'"
        else -> "Full match"
    }
    val extras = listOfNotNull(
        m.goals.takeIf { it > 0 }?.let { "$it G" },
        m.assists.takeIf { it > 0 }?.let { "$it A" },
        "YC".takeIf { m.yellowCards > 0 },
        "RC".takeIf { m.redCards > 0 },
        m.rating?.let { "★ $it" },
    )
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (m.injuredOff) c.redBg else MaterialTheme.colorScheme.background, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (m.played) "${m.minutes}'" else "–",
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(40.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(m.score, fontSize = 14.sp)
            Text("${fmtDay(m.date)} · ${m.competition}", color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(involvement, fontSize = 12.sp, color = if (m.injuredOff) c.red else MaterialTheme.colorScheme.onSurface)
            if (extras.isNotEmpty()) Text(extras.joinToString(" · "), fontSize = 12.sp, color = c.muted)
        }
    }
}

@Composable
private fun SourcesSection(sources: List<DataSource>, generatedAt: String) {
    val c = LocalStatusColors.current
    val uri = LocalUriHandler.current
    Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Data sources", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        sources.forEach { s ->
            val planned = s.status == "planned"
            Surface(
                color = if (planned) Color.Transparent else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, c.border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            s.name,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .weight(1f)
                                .then(if (s.url.isNotEmpty()) Modifier.clickable { uri.openUri(s.url) } else Modifier),
                        )
                        if (!planned) {
                            Box(Modifier.size(7.dp).background(c.green, CircleShape))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            when {
                                planned -> "Coming next"
                                s.oldestDataAt != null -> dataAge(s.oldestDataAt, generatedAt)
                                else -> "In use"
                            },
                            color = c.muted,
                            fontSize = 12.sp,
                        )
                    }
                    s.usedFor.forEach { Text("• $it", color = c.muted, fontSize = 13.sp) }
                }
            }
        }
        Text(
            "Fetched straight from FPL, FotMob and Transfermarkt by this phone, and cached on it for 10 minutes to 3 hours, " +
                "so a new withdrawal can take up to an hour to show.",
            color = c.muted,
            fontSize = 12.sp,
        )
    }
}

private fun dataAge(iso: String, now: String): String {
    val mins = Duration.between(Instant.parse(iso), Instant.parse(now)).toMinutes().coerceAtLeast(0)
    return when {
        mins < 1 -> "Fetched just now"
        mins < 60 -> "Data up to $mins min old"
        else -> "Data up to ${mins / 60} h old"
    }
}
