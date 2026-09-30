package com.ayuvo.health.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.ui.theme.AppColors
import com.ayuvo.health.ui.util.clockTimePattern
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure eaten-at resolution for the food edit sheet (docs/intake-metrics.md §3). */
object EatenAtEditing {
    /**
     * The eaten-at instant to store. Null [eatenTime] means "at the log time" and stores null. An untouched value keeps
     * the original instant. Otherwise the time is placed on the logged day or the day before, whichever is nearer the
     * log time (a late dinner logged after midnight belongs to the previous evening).
     */
    fun resolve(
        original: Instant?,
        initialEatenTime: LocalTime?,
        eatenTime: LocalTime?,
        loggedDate: LocalDate,
        loggedTime: LocalTime,
        initialLoggedAt: ZonedDateTime,
        zone: ZoneId
    ): Instant? {
        if (eatenTime == null) return null
        val logUnchanged = loggedDate == initialLoggedAt.toLocalDate() &&
            loggedTime == initialLoggedAt.toLocalTime().withSecond(0).withNano(0)
        if (original != null && eatenTime == initialEatenTime && logUnchanged) return original
        val logged = loggedDate.atTime(loggedTime).atZone(zone).toInstant()
        val sameDay = loggedDate.atTime(eatenTime).atZone(zone).toInstant()
        val dayBefore = loggedDate.minusDays(1).atTime(eatenTime).atZone(zone).toInstant()
        val pick = if (Duration.between(dayBefore, logged).abs() < Duration.between(sameDay, logged).abs()) dayBefore else sameDay
        return pick.takeUnless { it == logged }
    }
}

/** "Eaten at" row under the log date and time; defaults to the log time. */
@Composable
internal fun EatenAtRow(
    eatenTime: LocalTime,
    followsLogTime: Boolean,
    onEdit: () -> Unit,
    onReset: () -> Unit
) {
    val context = LocalContext.current
    val formatter = remember(context) { DateTimeFormatter.ofPattern(clockTimePattern(context), Locale.getDefault()) }
    SheetPillCard {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.intake_eaten_at), fontSize = 17.sp)
                Text(
                    stringResource(if (followsLogTime) R.string.intake_eaten_at_same_as_log else R.string.intake_eaten_at_hint),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            Text(eatenTime.format(formatter), fontSize = 17.sp, color = AppColors.Calorie, fontWeight = FontWeight.Medium)
        }
        if (!followsLogTime) {
            SheetHairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onReset)
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Text(stringResource(R.string.intake_eaten_at_reset), fontSize = 15.sp, color = AppColors.Calorie)
            }
        }
    }
}
