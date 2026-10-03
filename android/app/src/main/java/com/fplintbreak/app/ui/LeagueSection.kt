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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fplintbreak.app.data.RivalImpact
import com.fplintbreak.app.ui.theme.LocalStatusColors

/** Mini-league break impact: which rivals the break hit hardest. */
@Composable
fun LeagueSection(
    state: LeagueUiState,
    onSelectLeague: (Int) -> Unit,
    onSelectLimit: (Int) -> Unit,
    onCompare: () -> Unit,
) {
    val c = LocalStatusColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Who did the break hit hardest?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Checks the top teams in one of your mini-leagues for injuries, withdrawals and short rest before the " +
                "next gameweek. Tap a column to sort.",
            color = c.muted,
            fontSize = 13.sp,
        )

        val options = state.options
        when {
            options == null -> Text("Loading your mini-leagues…", color = c.muted, fontSize = 13.sp)
            options.isEmpty() && state.error == null -> Text("You're not in any private mini-leagues.", color = c.muted)
            options.isNotEmpty() -> {
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { menuOpen = true }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            options.find { it.id == state.selectedId }?.name ?: "Pick a mini-league",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        options.forEach { l ->
                            DropdownMenuItem(text = { Text(l.name) }, onClick = {
                                onSelectLeague(l.id)
                                menuOpen = false
                            })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 20).forEach { n ->
                        FilterChip(
                            selected = state.limit == n,
                            onClick = { onSelectLimit(n) },
                            label = { Text("Top $n") },
                            enabled = !state.loading,
                        )
                    }
                    Box(Modifier.weight(1f))
                    Button(onClick = onCompare, enabled = !state.loading && state.selectedId != null) {
                        Text(if (state.loading) "Checking…" else "Compare")
                    }
                }
            }
        }
        if (state.loading) {
            Text(
                "Checking every squad in the top ${state.limit}. The first time can take a minute or two; rivals share " +
                    "many players, so it gets faster.",
                color = c.muted,
                fontSize = 13.sp,
            )
        }
        state.error?.let {
            Text(
                it,
                color = c.red,
                modifier = Modifier.fillMaxWidth().background(c.redBg, RoundedCornerShape(10.dp)).padding(12.dp),
            )
        }

        val report = state.report
        if (report != null && !state.loading) {
            Text(report.leagueName, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            var sortKey by remember { mutableStateOf(SortKey.INJURED) }
            var desc by remember { mutableStateOf(true) }
            SortHeader(sortKey, desc) { key ->
                if (key == sortKey) desc = !desc else {
                    sortKey = key
                    desc = key != SortKey.RANK
                }
            }
            sortRivals(report.rivals, sortKey, desc).forEach { RivalRow(it) }
        }
    }
}

private enum class SortKey(val label: String) { INJURED("Inj"), WITHDREW("Out"), RANK("League rank") }

private fun RivalImpact.count(key: SortKey) = when (key) {
    SortKey.INJURED -> injured
    SortKey.WITHDREW -> withdrew
    SortKey.RANK -> rank
}

// Counts sort worst-first, league rank best-first; ties fall back to the other count, then rank.
private fun sortRivals(rivals: List<RivalImpact>, key: SortKey, desc: Boolean): List<RivalImpact> {
    val other = if (key == SortKey.INJURED) SortKey.WITHDREW else SortKey.INJURED
    val primary = compareBy<RivalImpact> { it.error != null }
        .then(if (desc) compareByDescending { it.count(key) } else compareBy { it.count(key) })
    val ties = if (key == SortKey.RANK) primary else primary.thenByDescending { it.count(other) }.thenBy { it.rank }
    return rivals.sortedWith(ties)
}

private val CountWidth = 44.dp

@Composable
private fun SortHeader(key: SortKey, desc: Boolean, onSort: (SortKey) -> Unit) {
    val c = LocalStatusColors.current
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        SortKey.entries.forEach { k ->
            val active = k == key
            Text(
                k.label.uppercase() + if (active) (if (desc) " ▼" else " ▲") else "",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (active) MaterialTheme.colorScheme.onSurface else c.muted,
                modifier = Modifier
                    .then(if (k == SortKey.RANK) Modifier.padding(start = 4.dp) else Modifier.width(CountWidth))
                    .clickable { onSort(k) }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun CountCell(value: Int?) {
    val c = LocalStatusColors.current
    Text(
        value?.toString() ?: "–",
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = if (value != null && value > 0) MaterialTheme.colorScheme.onSurface else c.muted,
        modifier = Modifier.width(CountWidth),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RivalRow(r: RivalImpact) {
    val c = LocalStatusColors.current
    Surface(
        color = if (r.isYou) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, c.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CountCell(if (r.error == null) r.injured else null)
            CountCell(if (r.error == null) r.withdrew else null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(r.teamName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (r.isYou) {
                        Text(
                            "You",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                Text("${r.manager} · #${r.rank} in league", color = c.muted, fontSize = 12.sp)
                if (r.error != null) {
                    Text("Couldn't check this squad: ${r.error}", color = c.muted, fontSize = 12.sp)
                } else if (r.flagged.isEmpty()) {
                    Text("Nothing flagged", color = c.muted, fontSize = 12.sp)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        r.flagged.forEach { f ->
                            Text(
                                "${f.name} · ${f.reason}",
                                fontSize = 11.sp,
                                color = if (f.bench) c.muted else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .border(1.dp, c.border, RoundedCornerShape(50))
                                    .background(Color.Transparent, RoundedCornerShape(50))
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
