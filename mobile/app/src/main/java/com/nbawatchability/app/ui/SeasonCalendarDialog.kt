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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
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

// Free navigation in both directions rather than being bounded to "the
// current season" - a generous multi-year window (not literally unbounded,
// which would let a stray tap scroll into the 1900s for no reason) that
// comfortably covers "browse last year's games" and "check next year's
// schedule once it's out" alike. Months outside any real ESPN data just come
// back with an empty count map and render as plain blank-cell months, same
// as an in-range month with a genuine off-season gap - there's nothing
// special to bound against.
private val EARLIEST_NAVIGABLE_MONTH = YearMonth.now().minusYears(3)
private val LATEST_NAVIGABLE_MONTH = YearMonth.now().plusYears(2)

/**
 * A bespoke month-grid date picker - not Compose Material3's stock
 * DatePicker, whose day cells can't be decorated with a per-day count at
 * this app's Compose BOM version. Scrolls freely across years (not bounded
 * to whichever league's "current season" happens to be loaded) since a game
 * count is meaningful for any month regardless of season boundaries; days
 * with zero real games are simply left blank rather than grayed out as
 * "outside the season."
 *
 * [gameCounts] only ever holds counts for [gameCountsMonth] - the caller
 * fetches lazily per visible month (not the whole navigable range up front)
 * via [onMonthChanged], which fires on the initial month too so the caller
 * doesn't need a separate "load the starting month" call of its own.
 */
@Composable
fun SeasonCalendarDialog(
    initialMonth: YearMonth,
    gameCounts: Map<LocalDate, Int>,
    gameCountsMonth: YearMonth?,
    isLoadingCounts: Boolean,
    onMonthChanged: (YearMonth) -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    var visibleMonth by remember { mutableStateOf(initialMonth.coerceIn(EARLIEST_NAVIGABLE_MONTH, LATEST_NAVIGABLE_MONTH)) }

    LaunchedEffect(visibleMonth) { onMonthChanged(visibleMonth) }

    // Only ever show counts for the month currently on screen - a still-
    // loading or previously-fetched month's data would otherwise flash
    // against the wrong grid for one frame while the new month's request is
    // in flight.
    val countsForVisibleMonth = if (gameCountsMonth == visibleMonth) gameCounts else emptyMap()

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
                        enabled = visibleMonth > EARLIEST_NAVIGABLE_MONTH
                    ) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month", tint = TextPrimary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${visibleMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${visibleMonth.year}",
                            color = TextPrimary,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (isLoadingCounts && gameCountsMonth != visibleMonth) {
                            Spacer(modifier = Modifier.size(8.dp))
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        }
                    }
                    IconButton(
                        onClick = { visibleMonth = visibleMonth.plusMonths(1) },
                        enabled = visibleMonth < LATEST_NAVIGABLE_MONTH
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
                                    val date = visibleMonth.atDay(dayNum)
                                    CalendarDayCell(
                                        date = date,
                                        gameCount = countsForVisibleMonth[date],
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
    gameCount: Int?,
    onDateSelected: (LocalDate) -> Unit
) {
    val isToday = date == LocalDate.now()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (isToday) TierWorthYourTime.copy(alpha = 0.25f) else Color.Transparent)
            .clickable { onDateSelected(date) }
    ) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = date.dayOfMonth.toString(),
            color = TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        // Blank (not "0") on a day with no real games - a day nobody's
        // ESPN data has anything scheduled for reads the same as any other
        // empty day in this app, never a distinct "zero" state.
        Text(
            text = gameCount?.toString().orEmpty(),
            color = TierWorthYourTime,
            fontSize = 10.sp,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
