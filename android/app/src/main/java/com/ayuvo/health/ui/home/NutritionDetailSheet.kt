package com.ayuvo.health.ui.home

import com.ayuvo.health.ui.design.AyuvoColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ayuvo.health.R
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.HomeTopNutrient
import com.ayuvo.health.models.MacroValueFormatter
import com.ayuvo.health.models.OptionalNutrientGoals
import com.ayuvo.health.models.SupplementalNutrient
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.models.WaterUnit
import com.ayuvo.health.data.metrics.AppMetricId
import com.ayuvo.health.data.metrics.MetricKey
import com.ayuvo.health.nutrients.NutrientAmount
import com.ayuvo.health.nutrients.NutrientFields
import com.ayuvo.health.nutrients.NutrientFormat
import com.ayuvo.health.nutrients.NutrientTotals
import java.time.LocalDate
import kotlin.math.roundToInt
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.components.GlassSurface
import com.ayuvo.health.ui.theme.AppColors

/**
 * Verbatim port of struct NutritionDetailView in
 * ios/calorietracker/ContentView.swift (line ~720).
 *
 * Two sections:
 *   Macros: Calories / Protein / Carbs / Fat — each row shows icon +
 *     label + value + unit + '/ goal'.
 *   Detailed Nutrition: Sugar / Added Sugar / Fiber / Saturated Fat /
 *     Mono Unsat. Fat / Poly Unsat. Fat / Cholesterol / Sodium /
 *     Potassium — same icon+label+value+unit pattern, no goal column.
 *
 * Totals come from [NutrientTotals] (food + taken supplement doses, docs/nutrients.md §6); a
 * nutrient with supplements shows "incl. X from supplements". Every row opens its chart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionDetailSheet(
    entries: List<FoodEntry>,
    profile: UserProfile?,
    homeTopNutrients: List<HomeTopNutrient>,
    optionalGoals: OptionalNutrientGoals,
    waterTrackingEnabled: Boolean,
    waterCurrentMl: Int,
    waterGoalMl: Int,
    waterUnit: WaterUnit,
    onHomeTopNutrientsChange: (List<HomeTopNutrient>) -> Unit,
    onDismiss: () -> Unit,
    /** Food + supplement totals of the shown day (docs/nutrients.md §6). */
    totals: NutrientTotals = NutrientTotals(entries),
    day: LocalDate = LocalDate.now(),
    /** Opens a nutrient's chart; the sheet dismisses first. */
    onOpenMetric: ((MetricKey) -> Unit)? = null
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showHomeCardsPicker by remember { mutableStateOf(false) }
    val dayTotals = remember(totals, day) { totals.day(day) }
    fun amount(key: String): NutrientAmount = dayTotals[key] ?: NutrientAmount.NONE
    // Calories and macros are food only; supplements never add calories.
    val calories = amount(NutrientFields.CALORIES).food?.roundToInt() ?: 0
    val protein = amount(NutrientFields.PROTEIN).food ?: 0.0
    val carbs = amount(NutrientFields.CARBS).food ?: 0.0
    val fat = amount(NutrientFields.FAT).food ?: 0.0
    val nutrientProfile = NutrientFields.profile(profile)
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val sheetSurface = AyuvoColors.sheetBackground()
    val open: (MetricKey) -> Unit = { key -> onDismiss(); onOpenMetric?.invoke(key) }

    fun fmt(v: Double?): String = if (v == null) "—" else String.format("%.1f", v)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = sheetSurface
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.nutrition_details_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done), color = AppColors.Calorie) }
                }
            }

            item { SectionHeader(stringResource(R.string.nutrition_section_home_cards)) }
            item {
                Card {
                    HomeCardsRow(
                        selected = homeTopNutrients,
                        waterTrackingEnabled = waterTrackingEnabled,
                        onClick = { showHomeCardsPicker = true }
                    )
                }
            }

            if (waterTrackingEnabled) {
                item { SectionHeader(stringResource(R.string.nutrition_section_water)) }
                item {
                    Card {
                        DetailRow(
                            Icons.Filled.WaterDrop,
                            stringResource(R.string.water),
                            waterUnit.displayValue(waterCurrentMl),
                            waterUnit.symbol,
                            goal = waterUnit.displayValue(waterGoalMl),
                            onClick = onOpenMetric?.let { { open(MetricKey.App(AppMetricId.WATER)) } }
                        )
                    }
                }
                item {
                    Text(
                        stringResource(R.string.home_nutrients_water_fixed),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.nutrition_section_macros)) }
            item {
                Card {
                    DetailRow(Icons.Filled.LocalFireDepartment, stringResource(R.string.nutrition_label_calories), "$calories", stringResource(R.string.unit_kcal), goal = "${profile?.effectiveCalories ?: 2000}", onClick = onOpenMetric?.let { { open(MetricKey.App(AppMetricId.CALORIES)) } })
                    Hairline()
                    DetailRow(null, stringResource(R.string.nutrition_label_protein), MacroValueFormatter.string(protein), stringResource(R.string.unit_g), goal = "${profile?.effectiveProtein ?: 150}", labelGlyph = "P", onClick = onOpenMetric?.let { { open(MetricKey.App(AppMetricId.PROTEIN)) } })
                    Hairline()
                    DetailRow(null, stringResource(R.string.nutrition_label_carbs), MacroValueFormatter.string(carbs), stringResource(R.string.unit_g), goal = "${profile?.effectiveCarbs ?: 220}", labelGlyph = "C", onClick = onOpenMetric?.let { { open(MetricKey.App(AppMetricId.CARBS)) } })
                    Hairline()
                    DetailRow(null, stringResource(R.string.nutrition_label_fat), MacroValueFormatter.string(fat), stringResource(R.string.unit_g), goal = "${profile?.effectiveFat ?: 70}", labelGlyph = "F", onClick = onOpenMetric?.let { { open(MetricKey.App(AppMetricId.FAT)) } })
                }
            }

            item { SectionHeader(stringResource(R.string.nutrition_section_detailed)) }
            item {
                Card {
                    DETAILED_ROWS.forEachIndexed { index, row ->
                        if (index > 0) Hairline()
                        val a = amount(row.key)
                        val goal = NutrientFields.optionalNutrient(row.key)?.let { optionalGoals.effectiveGoal(it, nutrientProfile) }
                        val unitRes = when (NutrientFields.unit(row.key)) {
                            "mg" -> R.string.unit_mg
                            "mcg" -> R.string.unit_mcg
                            else -> R.string.unit_g
                        }
                        val unitText = stringResource(unitRes)
                        DetailRow(
                            row.icon,
                            stringResource(NutrientFields.nameRes(row.key)),
                            fmt(a.total),
                            unitText,
                            goal = goal?.toString(),
                            labelGlyph = row.glyph,
                            subline = a.supplements?.takeIf { it > 0 }?.let {
                                stringResource(R.string.nutrients_incl_supplements, "${NutrientFormat.amount(it)} $unitText")
                            },
                            onClick = onOpenMetric?.let { { open(MetricKey.Nutrient(row.key)) } }
                        )
                    }
                }
            }
        }
    }

    if (showHomeCardsPicker) {
        HomeTopNutrientPickerDialog(
            selected = homeTopNutrients,
            waterTrackingEnabled = waterTrackingEnabled,
            onSave = onHomeTopNutrientsChange,
            onDismiss = { showHomeCardsPicker = false }
        )
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        padding = 0.dp
    ) {
        Column { content() }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        letterSpacing = 0.sp,
        modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 4.dp)
    )
}

@Composable
private fun HomeCardsRow(
    selected: List<HomeTopNutrient>,
    waterTrackingEnabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Filled.Spa, null, tint = AppColors.Calorie, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.home_nutrient_cards), fontSize = 17.sp)
            val nutrientNames = selected
                .take(if (waterTrackingEnabled) 3 else 4)
                .map { stringResource(it.displayNameRes) }
            val displayedNames = if (waterTrackingEnabled) {
                nutrientNames + stringResource(R.string.water)
            } else {
                nutrientNames
            }
            Text(
                displayedNames.joinToString(", "),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun HomeTopNutrientPickerDialog(
    selected: List<HomeTopNutrient>,
    waterTrackingEnabled: Boolean,
    onSave: (List<HomeTopNutrient>) -> Unit,
    onDismiss: () -> Unit
) {
    val normalizedSelection = remember(selected) { HomeTopNutrient.normalized(selected) }
    val selectionLimit = if (waterTrackingEnabled) 3 else 4
    var draft by remember(selected, waterTrackingEnabled) {
        mutableStateOf(normalizedSelection.take(selectionLimit))
    }
    val hiddenFourthNutrient = remember(selected, waterTrackingEnabled) {
        if (waterTrackingEnabled) normalizedSelection.getOrNull(3) else null
    }

    fun toggle(nutrient: HomeTopNutrient) {
        draft = if (nutrient in draft) {
            if (draft.size <= 1) draft else draft - nutrient
        } else {
            // iOS swaps out the last when full (removeLast + append) rather than ignoring.
            if (draft.size >= selectionLimit) draft.dropLast(1) + nutrient else draft + nutrient
        }
    }

    GlassDialog(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.home_nutrients), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            stringResource(
                if (waterTrackingEnabled) R.string.home_nutrients_pick_three_water
                else R.string.home_nutrients_pick_four
            ),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
        )
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 430.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (waterTrackingEnabled) {
                item(key = "fixed-water") {
                    val shape = RoundedCornerShape(16.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(AppColors.Calorie.copy(alpha = 0.11f))
                            .border(0.7.dp, AppColors.Calorie.copy(alpha = 0.22f), shape)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.WaterDrop,
                            contentDescription = null,
                            tint = AppColors.Calorie,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.water), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Text(
                                stringResource(R.string.home_nutrients_water_fixed_short),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                            )
                        }
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            items(HomeTopNutrient.values().toList()) { nutrient ->
                val checked = nutrient in draft
                val shape = RoundedCornerShape(16.dp)
                val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(
                            if (checked) AppColors.Calorie.copy(alpha = 0.11f)
                            else if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f)
                            else Color(0xFFEDE3DD).copy(alpha = 0.76f)
                        )
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = if (isDark) 0.08f else 0.18f),
                                    Color.White.copy(alpha = if (isDark) 0.02f else 0.04f),
                                    AppColors.Calorie.copy(alpha = if (checked) 0.065f else if (isDark) 0.025f else 0.050f)
                                )
                            )
                        )
                        .border(
                            0.7.dp,
                            Brush.linearGradient(
                                listOf(
                                    Color.White.copy(alpha = if (isDark) 0.16f else 0.46f),
                                    AppColors.Calorie.copy(alpha = if (checked) 0.22f else if (isDark) 0.08f else 0.16f)
                                )
                            ),
                            shape
                        )
                        .clickable { toggle(nutrient) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (checked) Brush.linearGradient(listOf(AppColors.CalorieStart, AppColors.CalorieEnd))
                                else Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
                                    )
                                )
                            )
                            .border(
                                1.dp,
                                if (checked) AppColors.Calorie.copy(alpha = 0.40f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f),
                                RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (checked) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(nutrient.displayNameRes), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(
                            stringResource(nutrient.unitRes),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                }
            }
        }
        GlassDialogActions(
            primaryText = stringResource(R.string.action_done),
            onPrimary = {
                // Keep an existing hidden choice only when all three visible slots are used.
                val savedSelection = if (waterTrackingEnabled && draft.size == 3 &&
                    hiddenFourthNutrient != null && hiddenFourthNutrient !in draft
                ) draft + hiddenFourthNutrient else draft
                onSave(HomeTopNutrient.normalized(savedSelection))
                onDismiss()
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = onDismiss,
            primaryEnabled = draft.size in 1..selectionLimit
        )
    }
}

/**
 * Row layout: icon (24dp pink, optional) + label (17sp) + value (17sp pink semibold)
 * + unit (13sp secondary) + optional '/ goal' (12sp tertiary).
 *
 * iOS uses LinearGradient on the SF Symbol; Compose uses a flat tint
 * since Material icons aren't text-paintable.
 */
@Composable
private fun DetailRow(
    icon: ImageVector?,
    label: String,
    value: String,
    unit: String,
    goal: String? = null,
    labelGlyph: String? = null,
    /** Small line under the label ("incl. 1,500 mcg from supplements"). */
    subline: String? = null,
    /** Opens the nutrient's chart; adds a chevron. */
    onClick: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = label, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) {
            Icon(icon, null, tint = AppColors.Calorie, modifier = Modifier.size(20.dp))
        } else if (labelGlyph != null) {
            Box(
                Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppColors.Calorie),
                contentAlignment = Alignment.Center
            ) {
                Text(labelGlyph, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White)
            }
        } else {
            Spacer(Modifier.width(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 17.sp)
            subline?.let {
                Text(it, fontSize = 12.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Calorie)
            Text(unit, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        goal?.let {
            Text(
                "/ $it",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier.padding(start = 6.dp)
            )
        }
        if (onClick != null) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** One Detailed Nutrition row: the nutrient key and its icon or letter glyph. */
private data class DetailedRow(val key: String, val icon: ImageVector? = null, val glyph: String? = null)

/** Detailed Nutrition in display order: every reference nutrient, mono/poly fat and the 8 sports keys. */
private val DETAILED_ROWS: List<DetailedRow> = listOf(
    DetailedRow("sugar", glyph = "S"),
    DetailedRow("added_sugar", glyph = "+"),
    DetailedRow("fiber", Icons.Filled.Spa),
    DetailedRow("saturated_fat", Icons.Filled.WaterDrop),
    DetailedRow("monounsaturated_fat", Icons.Filled.WaterDrop),
    DetailedRow("polyunsaturated_fat", Icons.Filled.WaterDrop),
    DetailedRow("cholesterol", Icons.Filled.Favorite),
    DetailedRow("caffeine", Icons.Filled.Bolt),
    DetailedRow("sodium", Icons.Filled.Bolt),
    DetailedRow("potassium", Icons.Filled.Bolt),
    DetailedRow("trans_fat", Icons.Filled.WaterDrop),
    DetailedRow("calcium", Icons.Filled.Bolt),
    DetailedRow("iron", Icons.Filled.Bolt),
    DetailedRow("magnesium", Icons.Filled.Bolt),
    DetailedRow("zinc", Icons.Filled.Bolt),
    DetailedRow("vitamin_a", glyph = "A"),
    DetailedRow("vitamin_c", glyph = "C"),
    DetailedRow("vitamin_d", glyph = "D"),
    DetailedRow("vitamin_b12", glyph = "B"),
    DetailedRow("vitamin_e", glyph = "E"),
    DetailedRow("vitamin_k", glyph = "K"),
    DetailedRow("folate", Icons.Filled.Spa),
    DetailedRow("omega_3", Icons.Filled.WaterDrop)
) + SupplementalNutrient.entries.map { DetailedRow(it.apiKey, Icons.Filled.Bolt) }

@Composable
private fun Hairline() {
    Box(
        Modifier
            .padding(start = 14.dp)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    )
}
