package com.nbawatchability.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.nbawatchability.app.ui.theme.SurfaceCardElevated
import com.nbawatchability.app.ui.theme.TextMuted
import com.nbawatchability.app.ui.theme.TextPrimary
import com.nbawatchability.app.ui.theme.TierWorthYourTime
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

// Sunday-first, matching the day-tab labels' own convention elsewhere on
// this screen (e.g. "Sat Jul 18").
private val WEEKDAY_HEADER_ORDER = listOf(
    DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY
)

/**
 * A bespoke month-grid date picker - not Compose Material3's stock
 * DatePicker, whose day cells can't be decorated with a per-day dot at this
 * app's Compose BOM version. Built specifically so days with a real
 * scheduled game can be marked, and days outside the season can be
 * disabled rather than just left clickable but empty.
 */
@Composable
fun SeasonCalendarDialog(
    seasonStart: LocalDate,
    seasonEnd: LocalDate,
    datesWithGames: Set<LocalDate>,
    initialMonth: YearMonth,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    val startMonth = YearMonth.from(seasonStart)
    val endMonth = YearMonth.from(seasonEnd)
    var visibleMonth by remember { mutableStateOf(initialMonth.coerceIn(startMonth, endMonth)) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = SurfaceCardElevated, shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { visibleMonth = visibleMonth.minusMonths(1) },
                        enabled = visibleMonth > startMonth
                    ) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month", tint = TextPrimary)
                    }
                    Text(
                        text = "${visibleMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${visibleMonth.year}",
                        color = TextPrimary,
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(
                        onClick = { visibleMonth = visibleMonth.plusMonths(1) },
                        enabled = visibleMonth < endMonth
                    ) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "Next month", tint = TextPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    for (dow in WEEKDAY_HEADER_ORDER) {
                        Text(
                            text = dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                            color = TextMuted,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                val firstOfMonth = visibleMonth.atDay(1)
                // ISO DayOfWeek.value is 1=Monday..7=Sunday; %7 remaps to a
                // Sunday-first offset (Sunday->0, Monday->1, ..., Saturday->6).
                val leadingBlanks = firstOfMonth.dayOfWeek.value % 7
                val daysInMonth = visibleMonth.lengthOfMonth()
                val totalCells = leadingBlanks + daysInMonth
                val rows = (totalCells + 6) / 7

                for (row in 0 until rows) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val dayNum = row * 7 + col - leadingBlanks + 1
                            Box(modifier = Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                                if (dayNum in 1..daysInMonth) {
                                    CalendarDayCell(
                                        date = visibleMonth.atDay(dayNum),
                                        seasonStart = seasonStart,
                                        seasonEnd = seasonEnd,
                                        hasGame = visibleMonth.atDay(dayNum) in datesWithGames,
                                        onDateSelected = onDateSelected
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    date: LocalDate,
    seasonStart: LocalDate,
    seasonEnd: LocalDate,
    hasGame: Boolean,
    onDateSelected: (LocalDate) -> Unit
) {
    val inSeason = !date.isBefore(seasonStart) && !date.isAfter(seasonEnd)
    val isToday = date == LocalDate.now()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (isToday) TierWorthYourTime.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(enabled = inSeason) { onDateSelected(date) }
    ) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = date.dayOfMonth.toString(),
            color = if (inSeason) TextPrimary else TextMuted.copy(alpha = 0.4f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .size(4.dp)
                .background(if (hasGame) TierWorthYourTime else Color.Transparent, CircleShape)
        )
    }
}
