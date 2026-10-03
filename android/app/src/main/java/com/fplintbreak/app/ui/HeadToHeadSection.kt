package com.fplintbreak.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fplintbreak.app.data.H2HPlayer
import com.fplintbreak.app.data.H2HSeason
import com.fplintbreak.app.data.H2HStatus
import com.fplintbreak.app.data.Matchup
import com.fplintbreak.app.data.MatchupSide
import com.fplintbreak.app.engine.parseInstant
import com.fplintbreak.app.ui.theme.LocalStatusColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val kickoffFormat = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.UK)

/** "2025/26" → "25/26" */
private fun shortSeason(s: String) = s.drop(2)

/** The next gameweek's matches through history: last meetings, who did the damage, and your players' record. */
internal fun LazyListScope.headToHead(state: H2HUiState, onRetry: () -> Unit) {
    item {
        val c = LocalStatusColors.current
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            Text("Historic numbers against next rivals", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                (state.report?.let { "Gameweek ${it.event}. " } ?: "") +
                    "For each of your matches: the last five Premier League meetings, each side's top scorers and " +
                    "assisters in them (current players only), and your players' record against the opponent with any " +
                    "club since ${state.report?.firstSeason ?: "five seasons ago"}. Tap a player for each season.",
                color = c.muted,
                fontSize = 13.sp,
            )
            if (state.loading) Text("Loading match histories… the first time takes a few seconds.", color = c.muted, fontSize = 13.sp)
            state.error?.let {
                Text(it, color = c.red)
                OutlinedButton(onClick = onRetry) { Text("Try again") }
            }
        }
    }
    val report = state.report ?: return
    items(report.matchups, key = { "${it.home.short}-${it.away.short}" }) { MatchupCard(it) }
    item {
        val c = LocalStatusColors.current
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            if (report.blanks.isNotEmpty()) {
                Text(
                    "No match this gameweek: " + report.blanks.joinToString { "${it.name} (${it.club})" } + ".",
                    color = c.muted,
                    fontSize = 12.sp,
                )
            }
            Text(
                "This season from FPL; earlier seasons from the vaastav/Fantasy-Premier-League archive of FPL data.",
                color = c.muted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp,
        color = LocalStatusColors.current.muted,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MatchupCard(m: Matchup) {
    val c = LocalStatusColors.current
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, c.border),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(
                    buildAnnotatedString {
                        append(m.home.name)
                        withStyle(SpanStyle(color = c.muted)) { append("  v  ") }
                        append(m.away.name)
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
                m.kickoff?.let {
                    Text(kickoffFormat.format(parseInstant(it).atZone(ZoneId.systemDefault())), color = c.muted, fontSize = 12.sp)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label(if (m.meetings.isEmpty()) "Last meetings" else "Last ${m.meetings.size} meetings")
                if (m.meetings.isEmpty()) {
                    Text("None in the last six seasons", color = c.muted, fontSize = 12.sp)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        m.meetings.forEach { x ->
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = c.muted)) { append(shortSeason(x.season) + "  ") }
                                    append(x.home + " ")
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${x.homeGoals}-${x.awayGoals}") }
                                    append(" " + x.away)
                                },
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .border(1.dp, c.border, RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(50))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            if (m.meetings.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TopList(m.home, Modifier.weight(1f))
                    TopList(m.away, Modifier.weight(1f))
                }
            }

            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Label("Your players vs opponent", Modifier.weight(1f))
                    listOf("Apps", "G", "A", "Pts").forEach { Label(it, Modifier.width(NumWidth)) }
                }
                HorizontalDivider(color = c.border)
                // Grouped by side, so each row's opponent is the other club.
                listOf(m.home to m.away.short, m.away to m.home.short).forEach { (side, opponent) ->
                    m.players.filter { it.club == side.short }.forEach { PlayerRow(it, opponent) }
                }
            }
        }
    }
}

private val NumWidth = 36.dp

@Composable
private fun TopList(side: MatchupSide, modifier: Modifier) {
    val c = LocalStatusColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Label("${side.short} top G+A")
        if (side.top.isEmpty()) Text("None from current players", color = c.muted, fontSize = 12.sp)
        side.top.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.name,
                    fontSize = 13.sp,
                    fontWeight = if (p.mine) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (p.mine) Box(Modifier.padding(start = 4.dp).size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                Box(Modifier.weight(1f))
                Text(
                    listOfNotNull(p.goals.takeIf { it > 0 }?.let { "${it}G" }, p.assists.takeIf { it > 0 }?.let { "${it}A" }).joinToString(" "),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun NumCell(value: Int, strong: Boolean) {
    Text(
        "$value",
        fontSize = 13.sp,
        fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal,
        color = if (strong) MaterialTheme.colorScheme.onSurface else LocalStatusColors.current.muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(NumWidth),
    )
}

@Composable
private fun PlayerRow(p: H2HPlayer, opponent: String) {
    val c = LocalStatusColors.current
    var open by rememberSaveable(p.id, opponent) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (open) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f) else Color.Transparent)
            .clickable { open = !open },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Text(if (open) "▾" else "▸", color = c.muted, fontSize = 11.sp, modifier = Modifier.width(14.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(p.name) }
                    withStyle(SpanStyle(color = c.muted, fontSize = 11.sp)) { append(" " + p.position + if (p.bench) " · bench" else "") }
                },
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            NumCell(p.apps, strong = false)
            NumCell(p.goals, strong = p.goals > 0)
            NumCell(p.assists, strong = p.assists > 0)
            NumCell(p.points, strong = true)
        }
        if (open) p.seasons.forEach { SeasonRow(it, opponent) }
        HorizontalDivider(color = c.border)
    }
}

private fun emptyNote(s: H2HSeason, opponent: String): String? = when (s.status) {
    H2HStatus.NO_PLAYER -> "Not in the PL"
    H2HStatus.NO_OPPONENT -> "$opponent not in the PL"
    H2HStatus.OWN_CLUB -> "Played for $opponent"
    H2HStatus.NOT_MET -> "No meeting yet"
    H2HStatus.UNAVAILABLE -> "Couldn't load"
    H2HStatus.PLAYED -> null
}

@Composable
private fun SeasonRow(s: H2HSeason, opponent: String) {
    val c = LocalStatusColors.current
    Row(Modifier.padding(start = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.Top) {
        Text(shortSeason(s.season), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(42.dp))
        val note = emptyNote(s, opponent)
        if (note != null) {
            Text(note, fontSize = 12.sp, color = c.muted, modifier = Modifier.weight(1f))
        } else {
            Text(
                s.club.orEmpty() + if (s.matches.isEmpty()) " · unused"
                else s.matches.joinToString("") { m -> " · ${m.result} ${if (m.home) "H" else "A"} ${m.minutes}'" },
                fontSize = 11.sp,
                color = c.muted,
                modifier = Modifier.weight(1f),
            )
            NumCell(s.apps, strong = false)
            NumCell(s.goals, strong = false)
            NumCell(s.assists, strong = false)
            NumCell(s.points, strong = true)
        }
    }
}
