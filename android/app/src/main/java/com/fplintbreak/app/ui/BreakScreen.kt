package com.fplintbreak.app.ui

import androidx.compose.animation.AnimatedVisibility
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
private fun fmtDay(iso: String) = dayFormat.format(Instant.parse(iso))

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BreakScreen(vm: BreakViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

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
                state.report?.let { report(it, vm::openMatchFix) }
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

private fun LazyListScope.report(report: TeamReport, onFixMatch: (PlayerReport) -> Unit) {
    item { Summary(report) }
    val (bench, starters) = report.players.partition { it.isBench }
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
private fun Summary(report: TeamReport) {
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
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            val withdrew = ps.count { it.callUp.status == CallUpStatus.WITHDRAWN }
            Stat(away.size, "With national team")
            Stat(withdrew, "Withdrew", if (withdrew > 0) c.amber to c.amberBg else null)
            Stat(ps.count { it.callUp.status == CallUpStatus.NOT_CALLED || it.callUp.status == CallUpStatus.UNKNOWN }, "Not called up")
            Risk.entries.forEach { r ->
                val n = ps.count { it.risk == r }
                val tone = when (r) {
                    Risk.RED -> c.red to c.redBg
                    Risk.AMBER -> c.amber to c.amberBg
                    Risk.GREEN -> c.green to c.greenBg
                }
                Stat(n, riskLabel(r), if (n > 0) tone else null)
            }
        }
    }
}

@Composable
private fun Stat(n: Int, label: String, tone: Pair<Color, Color>? = null) {
    val c = LocalStatusColors.current
    val (fg, bg) = tone ?: (MaterialTheme.colorScheme.onSurface to MaterialTheme.colorScheme.surface)
    Row(
        Modifier
            .background(bg, RoundedCornerShape(50))
            .then(if (tone == null) Modifier.border(1.dp, c.border, RoundedCornerShape(50)) else Modifier)
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
                            if (done.isEmpty()) {
                                Text("—", color = c.muted)
                            } else {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    done.forEach { InvolvementChip(it) }
                                    val minutes = done.sumOf { it.minutes ?: 0 }
                                    if (done.size > 1 && minutes > 0) {
                                        Text("$minutes' total", color = c.muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))
                                    }
                                }
                            }
                        }
                        val next = p.upcoming
                        if (p.isAway && next.isNotEmpty()) {
                            Text("${next.size}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "next ${fmtDay(next[0].date)} ${if (next[0].home) "v" else "@"} ${next[0].opponent}",
                                color = c.muted,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Text("—", color = c.muted)
                        }
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
        Involvement.ABSENT -> "Out"
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
                    Text("${fmtDay(f.date)} · ${f.competition}", color = c.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

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
