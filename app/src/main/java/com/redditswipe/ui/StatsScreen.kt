package com.redditswipe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.redditswipe.data.DayStats
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun StatsScreen(stats: Map<String, DayStats>, modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    val week = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val weekStats = week.map { stats[it.toString()] ?: DayStats() }
    val todayStats = weekStats.last()
    val allTime = stats.values.fold(DayStats()) { a, b -> DayStats(a.stories + b.stories, a.seconds + b.seconds) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Today", todayStats, Modifier.weight(1f))
            StatTile("Last 7 days", weekStats.fold(DayStats()) { a, b -> DayStats(a.stories + b.stories, a.seconds + b.seconds) }, Modifier.weight(1f))
        }
        StatTile("All time", allTime, Modifier.fillMaxWidth())

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Minutes reading, last 7 days", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(16.dp))
                MinutesBarChart(week, weekStats)
                Spacer(Modifier.height(16.dp))
                // The same numbers as a table, so nothing depends on reading bar heights.
                week.zip(weekStats).reversed().forEach { (day, s) ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            if (day == today) "Today" else day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${s.stories} stories  ·  ${minutes(s.seconds)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, stats: DayStats, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text("${stats.stories} stories", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(minutes(stats.seconds), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One thin bar per day, anchored to a baseline; only today's value is labelled. */
@Composable
private fun MinutesBarChart(days: List<LocalDate>, stats: List<DayStats>) {
    val values = stats.map { it.seconds / 60f }
    val max = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
    val barColor = MaterialTheme.colorScheme.primary
    Column {
        Row(
            Modifier.fillMaxWidth().height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            values.forEachIndexed { i, value ->
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    if (i == values.lastIndex) {
                        Text(
                            "${value.toInt()}m",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    Box(
                        Modifier
                            .fillMaxWidth(0.55f)
                            .fillMaxHeight((value / max * 0.85f).coerceAtLeast(0f))
                            .background(barColor, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEach { day ->
                Text(
                    day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

private fun minutes(seconds: Int): String {
    val m = seconds / 60
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}
