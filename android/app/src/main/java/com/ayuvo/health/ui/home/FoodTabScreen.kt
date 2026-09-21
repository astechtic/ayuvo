package com.ayuvo.health.ui.home

import com.ayuvo.health.ui.navigation.BottomNavFabPadding
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.ayuvo.health.models.FoodSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyListScope
import androidx.lifecycle.ViewModelStoreOwner
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ayuvo.health.AppContainer
import com.ayuvo.health.R
import com.ayuvo.health.models.FastingSession
import com.ayuvo.health.models.FoodEntry
import com.ayuvo.health.models.FoodLogMethod
import com.ayuvo.health.models.FoodLogMethodDefaultGroupIcon
import com.ayuvo.health.models.QuickAction
import com.ayuvo.health.models.QuickActionRequest
import com.ayuvo.health.models.WaterUnit
import com.ayuvo.health.models.displayName
import com.ayuvo.health.services.FoodImageDecoder
import com.ayuvo.health.services.MealShareText
import com.ayuvo.health.ui.components.GlassDialog
import com.ayuvo.health.ui.components.GlassDialogActions
import com.ayuvo.health.ui.components.InAppCameraCaptureDialog
import com.ayuvo.health.ui.components.MacroCard
import com.ayuvo.health.ui.components.WeekEnergyStrip
import com.ayuvo.health.ui.navigation.BottomNavDockedControlPadding
import com.ayuvo.health.ui.navigation.BottomNavScrollPadding
import com.ayuvo.health.ui.theme.AppColors
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID



/** A one-shot request to open a food log method (or the + menu when [method] is null). */
data class FoodLogRequest(val method: FoodLogMethod?, val id: Long = System.nanoTime())

/**
 * Complete food diary, hosted by Browse › Nutrition: calorie/macro goals, water tracker, log
 * methods, camera/scan, meal combine and fasting sessions.
 *
 * [viewModelOwner] scopes the [HomeViewModel] (Nutrition passes the Activity so leaving the
 * screen never cancels an AI analysis in flight). [footer] adds rows after the diary.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoodTabScreen(
    container: AppContainer,
    quickActionRequest: QuickActionRequest? = null,
    onQuickActionHandled: (Long) -> Unit = {},
    logRequest: FoodLogRequest? = null,
    onLogRequestHandled: (Long) -> Unit = {},
    viewModelOwner: ViewModelStoreOwner? = null,
    topBar: @Composable () -> Unit = {},
    footer: LazyListScope.() -> Unit = {}
) {
    val vm: HomeViewModel = if (viewModelOwner != null) {
        viewModel(viewModelStoreOwner = viewModelOwner, factory = HomeViewModel.Factory(container))
    } else {
        viewModel(factory = HomeViewModel.Factory(container))
    }
    val ui by vm.ui.collectAsState()
    // An analysis keeps running if the user leaves, but the overlay owns the screen until it ends.
    BackHandler(enabled = ui.analyzing) {}
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshDailySteps()
                vm.bumpBurnRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val shareScope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val weekStartsOnMonday by container.prefs.weekStartsOnMonday.collectAsState(initial = true)

    var showText by rememberSaveable { mutableStateOf(false) }
    var showVoice by rememberSaveable { mutableStateOf(false) }
    var showManual by rememberSaveable { mutableStateOf(false) }
    var savedMealsTab by remember { mutableStateOf<SavedTab?>(null) }
    var showBarcodeScanner by rememberSaveable { mutableStateOf(false) }
    var showCopyFromDay by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var addMenuDestination by remember { mutableStateOf<AddMenuDestination?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<FoodEntry?>(null) }
    var selectedFoodIds by remember { mutableStateOf<Set<UUID>>(emptySet()) }
    val selectionMode = selectedFoodIds.isNotEmpty()
    var showNutritionDetail by remember { mutableStateOf(false) }
    var showCustomWaterLog by remember { mutableStateOf(false) }
    var showFastingStart by remember { mutableStateOf(false) }
    var editingFast by remember { mutableStateOf<FastingSession?>(null) }
    var pendingDiaryDeletion by remember { mutableStateOf<HomeDiaryItem?>(null) }
    var showFastingQuickActionDisabled by remember { mutableStateOf(false) }

    var showCameraCapture by rememberSaveable { mutableStateOf(false) }
    var showMultiPhotoCapture by rememberSaveable { mutableStateOf(false) }
    val captureDraft: PhotoCaptureDraftViewModel = viewModel(factory = PhotoCaptureDraftViewModel.factory(ctx))
    val pendingCaptureImageBytes by captureDraft.images.collectAsState()
    val captureDraftBusy by captureDraft.busy.collectAsState()
    val captureDraftError by captureDraft.error.collectAsState()
    var captureNote by rememberSaveable { mutableStateOf("") }
    var captureProgressiveMeal by rememberSaveable { mutableStateOf(false) }

    fun clearCaptureDraft() {
        captureDraft.clear()
        captureNote = ""
        captureProgressiveMeal = false
    }
    var isImportingPhotos by rememberSaveable { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        val remaining = 10 - pendingCaptureImageBytes.size
        val imported = uris.take(remaining).mapNotNull { uri ->
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
        if (imported.isNotEmpty()) {
            captureDraft.append(imported)
        }
        if (imported.isNotEmpty() || pendingCaptureImageBytes.isNotEmpty()) showMultiPhotoCapture = true
    }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            clearCaptureDraft()
            showCameraCapture = true
        }
    }

    fun openCamera() {
        isImportingPhotos = false
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            clearCaptureDraft()
            showCameraCapture = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    val barcodePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showBarcodeScanner = true
    }

    fun openBarcodeScanner() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            showBarcodeScanner = true
        } else {
            barcodePermission.launch(Manifest.permission.CAMERA)
        }
    }

    fun performFoodLogMethod(method: FoodLogMethod) {
        showAddMenu = false
        addMenuDestination = null
        when (method) {
            FoodLogMethod.CAMERA -> openCamera()
            FoodLogMethod.PHOTOS -> {
                isImportingPhotos = true
                clearCaptureDraft()
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            FoodLogMethod.BARCODE -> openBarcodeScanner()
            FoodLogMethod.VOICE -> showVoice = true
            FoodLogMethod.TEXT -> showText = true
            FoodLogMethod.MANUAL -> showManual = true
            FoodLogMethod.FAVORITES -> savedMealsTab = SavedTab.FAVORITES
            FoodLogMethod.FREQUENT -> savedMealsTab = SavedTab.FREQUENT
            FoodLogMethod.RECENT -> savedMealsTab = SavedTab.RECENTS
            FoodLogMethod.COPY_FROM_DAY -> showCopyFromDay = true
        }
    }

    LaunchedEffect(
        quickActionRequest?.id,
        ui.analyzing,
        ui.pendingAnalysis,
        ui.error
    ) {
        val request = quickActionRequest ?: return@LaunchedEffect
        if (ui.analyzing || ui.pendingAnalysis != null || ui.error != null) return@LaunchedEffect

        showText = false
        showVoice = false
        showManual = false
        savedMealsTab = null
        showBarcodeScanner = false
        showCopyFromDay = false
        showAddMenu = false
        addMenuDestination = null
        editingEntry = null
        showNutritionDetail = false
        showCustomWaterLog = false
        showFastingStart = false
        editingFast = null
        showCameraCapture = false
        showMultiPhotoCapture = false
        vm.setSelectedDate(LocalDate.now())

        if (request.action != QuickAction.FASTING && ui.activeFast != null) {
            vm.reportFoodBlockedByFast()
            onQuickActionHandled(request.id)
            return@LaunchedEffect
        }

        when (request.action) {
            QuickAction.CAMERA -> openCamera()
            QuickAction.PHOTOS -> {
                isImportingPhotos = true
                clearCaptureDraft()
                photoPicker.launch(
                    PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            }
            QuickAction.VOICE -> showVoice = true
            QuickAction.TEXT -> showText = true
            QuickAction.BARCODE -> openBarcodeScanner()
            QuickAction.FAVORITES -> savedMealsTab = SavedTab.FAVORITES
            QuickAction.FREQUENT -> savedMealsTab = SavedTab.FREQUENT
            QuickAction.RECENT -> savedMealsTab = SavedTab.RECENTS
            QuickAction.MANUAL -> showManual = true
            QuickAction.FASTING -> {
                when {
                    !ui.fastingTrackingEnabled -> showFastingQuickActionDisabled = true
                    ui.activeFast != null -> editingFast = ui.activeFast
                    else -> showFastingStart = true
                }
            }
        }
        onQuickActionHandled(request.id)
    }

    LaunchedEffect(logRequest?.id, ui.analyzing, ui.pendingAnalysis, ui.error) {
        val request = logRequest ?: return@LaunchedEffect
        if (ui.analyzing || ui.pendingAnalysis != null || ui.error != null) return@LaunchedEffect
        vm.setSelectedDate(LocalDate.now())
        val method = request.method
        when {
            method == null -> {
                addMenuDestination = null
                showAddMenu = true
            }
            ui.activeFast != null -> vm.reportFoodBlockedByFast()
            else -> performFoodLogMethod(method)
        }
        onLogRequestHandled(request.id)
    }

    BackHandler(enabled = selectionMode) {
        selectedFoodIds = emptySet()
    }

    val today = LocalDate.now()
    val selectedDate = ui.date
    val isToday = selectedDate == today
    val completedFasts = remember(ui.fastingSessions, selectedDate) {
        ui.fastingSessions.filter { session ->
            session.endedAt?.atZone(ZoneId.systemDefault())?.toLocalDate() == selectedDate
        }.sortedByDescending { it.endedAt }
    }
    val diaryFasts = completedFasts
    val diaryMealGroups = remember(
        ui.todayEntries,
        ui.waterEntriesToday,
        diaryFasts,
        ui.foodLogSortOrder
    ) {
        homeDiaryMealGroups(
            foodEntries = ui.todayEntries,
            waterEntries = ui.waterEntriesToday,
            fastingSessions = diaryFasts,
            sortOrder = ui.foodLogSortOrder
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = topBar,
        bottomBar = {
            if (selectionMode) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .navigationBarsPadding()
                        .padding(bottom = BottomNavDockedControlPadding)
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(onClick = { selectedFoodIds = emptySet() }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.action_cancel),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            stringResource(R.string.combine_selected_count, selectedFoodIds.size),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Button(
                            onClick = {
                                val ids = selectedFoodIds
                                vm.combineIntoMeal(ids) { combined ->
                                    selectedFoodIds = emptySet()
                                    if (combined != null) editingEntry = combined
                                }
                            },
                            enabled = selectedFoodIds.size >= 2,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.Calorie.copy(alpha = 0.12f),
                                contentColor = AppColors.Calorie,
                                disabledContainerColor = AppColors.Calorie.copy(alpha = 0.12f),
                                disabledContentColor = AppColors.Calorie.copy(alpha = 0.45f)
                            )
                        ) {
                            Text(stringResource(R.string.combine_action), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = if (selectionMode) 8.dp else BottomNavScrollPadding + 72.dp)
            ) {
                item {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        WeekEnergyStrip(
                            selectedDate = selectedDate,
                            onSelect = { vm.setSelectedDate(it) },
                            weekStartsOnMonday = weekStartsOnMonday
                        )
                    }
                }

                val pinnedFast = if (isToday) ui.activeFast else null
                if (pinnedFast != null) {
                    item(key = "active-fast") {
                        Spacer(Modifier.height(8.dp))
                        SectionCardWrapper(isFirst = true, isLast = true, transparent = true) {
                            ActiveFastingRow(
                                session = pinnedFast,
                                rowShape = sectionCardShape(isFirst = true, isLast = true),
                                onClick = { editingFast = pinnedFast }
                            )
                        }
                    }
                }

                item {
                    Column(
                        modifier = Modifier.pointerInput(selectedDate) {
                            var accum = 0f
                            val threshold = 80.dp.toPx()
                            detectHorizontalDragGestures(
                                onDragStart = { accum = 0f },
                                onDragCancel = { accum = 0f },
                                onHorizontalDrag = { change, amount -> accum += amount; change.consume() },
                                onDragEnd = {
                                    if (accum > threshold) {
                                        vm.setSelectedDate(selectedDate.minusDays(1))
                                    } else if (accum < -threshold) {
                                        val next = selectedDate.plusDays(1)
                                        if (!next.isAfter(today)) vm.setSelectedDate(next)
                                    }
                                    accum = 0f
                                }
                            )
                        }
                    ) {
                        Spacer(Modifier.height(16.dp))
                        CalorieHero(
                            current = ui.caloriesToday,
                            goal = ui.profile?.effectiveCalories ?: 2000,
                            burnSummary = ui.homeBurnSummary
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ui.homeTopNutrients.take(if (ui.waterTrackingEnabled) 3 else 4).forEach { nutrient ->
                                MacroCard(
                                    label = stringResource(nutrient.displayNameRes),
                                    current = nutrient.current(ui.todayEntries),
                                    goal = nutrient.goal(ui.profile, ui.optionalNutrientGoals).toDouble(),
                                    unit = nutrient.unit,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (ui.waterTrackingEnabled) {
                                MacroCard(
                                    label = stringResource(R.string.water),
                                    current = ui.waterUnit.displayAmount(ui.waterTodayMl),
                                    goal = ui.waterUnit.displayAmount(ui.waterDailyGoalMl),
                                    unit = if (ui.waterUnit == WaterUnit.FLUID_OUNCES) " fl oz" else "ml",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.clickable { showNutritionDetail = true }) {
                                ViewMoreButton()
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(8.dp)) }
                if (diaryMealGroups.isEmpty()) {
                    item { HomeSectionHeader(if (isToday) stringResource(R.string.home_todays_diary) else stringResource(R.string.home_diary)) }
                    item {
                        SectionCardWrapper(isFirst = true, isLast = true) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
                                Text(
                                    stringResource(R.string.home_no_diary_entries),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            }
                        }
                    }
                } else {
                    for ((groupIndex, group) in diaryMealGroups.withIndex()) {
                        item(key = "header-${group.id}") {
                            val foodEntries = group.foodEntries
                            MealSectionHeader(
                                meal = group.meal,
                                totalCalories = group.totalCalories.takeIf { foodEntries.isNotEmpty() },
                                totalProtein = group.totalProtein,
                                totalCarbs = group.totalCarbs,
                                totalFat = group.totalFat,
                                onShare = if (foodEntries.isEmpty()) null else {
                                    { shareScope.launch { MealShareText.share(ctx, foodEntries) } }
                                },
                                showSortMenu = groupIndex == 0,
                                sortOrder = ui.foodLogSortOrder,
                                sortMenuExpanded = showSortMenu,
                                onSortClick = { showSortMenu = true },
                                onSortDismiss = { showSortMenu = false },
                                onSortOrderSelected = { order ->
                                    showSortMenu = false
                                    vm.setFoodLogSortOrder(order)
                                }
                            )
                        }
                        items(group.items, key = { it.stableId }) { item ->
                            val index = group.items.indexOf(item)
                            val isFirst = index == 0
                            val isLast = index == group.items.lastIndex
                            val rowShape = sectionCardShape(isFirst, isLast)
                            SectionCardWrapper(isFirst = isFirst, isLast = isLast, transparent = true) {
                                when (item) {
                                    is HomeDiaryItem.Food -> {
                                        val entry = item.entry
                                        val isFav = ui.isFavorite(entry)
                                        val isSelected = entry.id in selectedFoodIds
                                        SwipeableFoodRow(
                                            entry = entry,
                                            isFavorite = isFav,
                                            rowShape = rowShape,
                                            selectionMode = selectionMode,
                                            selected = isSelected,
                                            onTap = {
                                                if (selectionMode) {
                                                    selectedFoodIds = if (isSelected) {
                                                        selectedFoodIds - entry.id
                                                    } else {
                                                        selectedFoodIds + entry.id
                                                    }
                                                } else {
                                                    editingEntry = entry
                                                }
                                            },
                                            onLongPress = {
                                                selectedFoodIds = selectedFoodIds + entry.id
                                            },
                                            onDelete = { pendingDiaryDeletion = item },
                                            onToggleFavorite = { vm.toggleFavorite(entry) }
                                        )
                                    }
                                    is HomeDiaryItem.Water -> {
                                        SwipeableWaterRow(
                                            entry = item.entry,
                                            unit = ui.waterUnit,
                                            rowShape = rowShape,
                                            onDelete = { pendingDiaryDeletion = item }
                                        )
                                    }
                                    is HomeDiaryItem.Fasting -> {
                                        SwipeableFastingRow(
                                            session = item.session,
                                            rowShape = rowShape,
                                            onTap = { editingFast = item.session },
                                            onDelete = { pendingDiaryDeletion = item }
                                        )
                                    }
                                }
                                if (!isLast) Divider()
                            }
                        }
                    }
                }
                footer()
            }

            if (!selectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(end = 24.dp, bottom = BottomNavFabPadding)
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(AppColors.Calorie)
                            .testTag("home.add")
                            .clickable {
                                addMenuDestination = null
                                showAddMenu = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.cd_add_food),
                            tint = Color.White,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    SheetGlassDropdownMenu(
                        expanded = showAddMenu,
                        onDismissRequest = {
                            showAddMenu = false
                            addMenuDestination = null
                        },
                        menuWidth = 238.dp
                    ) {
                        when (val destination = addMenuDestination) {
                            null -> {
                                var insertedFoodBlock = false
                                @Composable
                                fun maybeFoodBoundaryHairline() {
                                    if (insertedFoodBlock) {
                                        SheetHairline()
                                        insertedFoodBlock = false
                                    }
                                }
                                if (ui.activeFast == null) {
                                    val addMenuConfig = ui.addMenuConfig
                                    if (addMenuConfig.usesFlatLayout) {
                                        val methods = addMenuConfig.resolvedFlatMethods()
                                        methods.forEach { method ->
                                            SheetGlassDropdownMenuItem(
                                                label = stringResource(method.titleRes),
                                                leadingIcon = method.icon
                                            ) { performFoodLogMethod(method) }
                                        }
                                        insertedFoodBlock = methods.isNotEmpty()
                                    } else {
                                        val groups = addMenuConfig.resolvedGroups()
                                        groups.forEachIndexed { index, group ->
                                            if (index > 0) SheetHairline()
                                            SheetGlassDropdownMenuItem(
                                                label = group.displayName(),
                                                leadingIcon = group.methods.firstOrNull()?.icon ?: FoodLogMethodDefaultGroupIcon,
                                                trailingIcon = Icons.Filled.ChevronRight
                                            ) { addMenuDestination = AddMenuDestination.FoodGroup(index) }
                                        }
                                        insertedFoodBlock = groups.isNotEmpty()
                                    }
                                }
                                if (ui.waterTrackingEnabled) {
                                    maybeFoodBoundaryHairline()
                                    SheetGlassDropdownMenuItem(
                                        label = stringResource(R.string.water),
                                        leadingIcon = Icons.Filled.WaterDrop,
                                        trailingIcon = Icons.Filled.ChevronRight
                                    ) { addMenuDestination = AddMenuDestination.Water }
                                }
                                if (ui.fastingTrackingEnabled) {
                                    maybeFoodBoundaryHairline()
                                    if (ui.activeFast == null) {
                                        SheetGlassDropdownMenuItem(label = stringResource(R.string.fasting_start), leadingIcon = Icons.Filled.Timer) {
                                            showAddMenu = false
                                            showFastingStart = true
                                        }
                                    } else {
                                        SheetGlassDropdownMenuItem(
                                            label = stringResource(R.string.fasting),
                                            leadingIcon = Icons.Filled.Timer,
                                            trailingIcon = Icons.Filled.ChevronRight
                                        ) { addMenuDestination = AddMenuDestination.Fasting }
                                    }
                                }
                            }

                            is AddMenuDestination.FoodGroup -> {
                                val group = ui.addMenuConfig.resolvedGroups().getOrNull(destination.index)
                                group?.methods?.forEach { method ->
                                    SheetGlassDropdownMenuItem(
                                        label = stringResource(method.titleRes),
                                        leadingIcon = method.icon
                                    ) { performFoodLogMethod(method) }
                                }
                                SheetGlassDropdownMenuItem(
                                    label = stringResource(R.string.back),
                                    leadingIcon = Icons.Filled.ChevronLeft
                                ) { addMenuDestination = null }
                            }

                            AddMenuDestination.Water -> {
                                SheetGlassDropdownMenuItem(label = stringResource(R.string.water_one_glass_dynamic, ui.waterUnit.format(250)), leadingIcon = Icons.Filled.WaterDrop) { showAddMenu = false; addMenuDestination = null; vm.addWater(250) }
                                SheetGlassDropdownMenuItem(label = stringResource(R.string.water_two_glasses_dynamic, ui.waterUnit.format(500)), leadingIcon = Icons.Filled.WaterDrop) { showAddMenu = false; addMenuDestination = null; vm.addWater(500) }
                                SheetGlassDropdownMenuItem(label = stringResource(R.string.water_three_glasses_dynamic, ui.waterUnit.format(750)), leadingIcon = Icons.Filled.WaterDrop) { showAddMenu = false; addMenuDestination = null; vm.addWater(750) }
                                SheetGlassDropdownMenuItem(label = stringResource(R.string.water_custom_amount), leadingIcon = Icons.Filled.DriveFileRenameOutline) { showAddMenu = false; addMenuDestination = null; showCustomWaterLog = true }
                                SheetGlassDropdownMenuItem(label = stringResource(R.string.back), leadingIcon = Icons.Filled.ChevronLeft) { addMenuDestination = null }
                            }

                            AddMenuDestination.Fasting -> {
                                SheetGlassDropdownMenuItem(label = stringResource(R.string.fasting_end), leadingIcon = Icons.Filled.Stop) {
                                    showAddMenu = false
                                    addMenuDestination = null
                                    vm.endFast()
                                }
                                SheetGlassDropdownMenuItem(label = stringResource(R.string.fasting_cancel), leadingIcon = Icons.Filled.Delete) {
                                    showAddMenu = false
                                    addMenuDestination = null
                                    vm.cancelFast()
                                }
                                SheetGlassDropdownMenuItem(label = stringResource(R.string.back), leadingIcon = Icons.Filled.ChevronLeft) { addMenuDestination = null }
                            }
                        }
                    }
                }
            }

            if (showText) {
                TextInputDialog(
                    onDismiss = { showText = false },
                    onSubmit = { showText = false; vm.analyzeText(it) }
                )
            }

            if (showCustomWaterLog) {
                WaterCustomAmountSheet(
                    unit = ui.waterUnit,
                    onDismiss = { showCustomWaterLog = false },
                    onAdd = vm::addWater
                )
            }

            if (showFastingStart) {
                FastingGoalDialog(
                    title = stringResource(R.string.fasting_start),
                    initialMinutes = ui.fastingDefaultGoalMinutes,
                    confirmLabel = stringResource(R.string.fasting_start),
                    onConfirm = {
                        showFastingStart = false
                        vm.startFast(it)
                    },
                    onDismiss = { showFastingStart = false }
                )
            }

            savedMealsTab?.let { tab ->
                SavedMealsSheet(
                    container = container,
                    tab = tab,
                    onDismiss = { savedMealsTab = null },
                    onRelogEntry = { vm.reviewSavedMeal(it) }
                )
            }

            if (showCopyFromDay) {
                CopyFromDaySheet(
                    targetDate = selectedDate,
                    allEntries = container.foodRepository.entries.collectAsState(initial = emptyList()).value,
                    onCopy = { entries ->
                        vm.copyEntriesToSelectedDay(entries)
                        showCopyFromDay = false
                    },
                    onDismiss = { showCopyFromDay = false }
                )
            }

            if (showBarcodeScanner) {
                BarcodeScannerSheet(
                    onBarcode = { barcode ->
                        showBarcodeScanner = false
                        vm.lookupBarcode(barcode)
                    },
                    onDismiss = { showBarcodeScanner = false }
                )
            }

            if (showCameraCapture) {
                InAppCameraCaptureDialog(
                    onCapture = { bytes ->
                        showCameraCapture = false
                        captureDraft.append(listOf(bytes))
                        showMultiPhotoCapture = true
                    },
                    onDismiss = {
                        showCameraCapture = false
                        if (pendingCaptureImageBytes.isNotEmpty()) {
                            showMultiPhotoCapture = true
                        }
                    }
                )
            }

            if (showMultiPhotoCapture && captureDraftBusy && pendingCaptureImageBytes.isEmpty()) {
                AlertDialog(
                    onDismissRequest = { showMultiPhotoCapture = false; clearCaptureDraft() },
                    text = { CircularProgressIndicator() },
                    confirmButton = {}
                )
            }
            if (captureDraftError != null) {
                AlertDialog(
                    onDismissRequest = { captureDraft.dismissError() },
                    text = { Text(captureDraftError.orEmpty()) },
                    confirmButton = { TextButton(onClick = { captureDraft.dismissError() }) { Text(stringResource(R.string.action_ok)) } }
                )
            }
            if (showMultiPhotoCapture && pendingCaptureImageBytes.isNotEmpty()) {
                MultiPhotoCaptureSheet(
                    imageBytesList = pendingCaptureImageBytes,
                    addsFromLibrary = isImportingPhotos,
                    isBusy = captureDraftBusy,
                    note = captureNote,
                    onNoteChange = { captureNote = it },
                    progressiveMeal = captureProgressiveMeal,
                    onProgressiveMealChange = { captureProgressiveMeal = it },
                    onAddPhoto = {
                        if (pendingCaptureImageBytes.size < 10) {
                            if (isImportingPhotos) {
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            } else {
                                showMultiPhotoCapture = false
                                showCameraCapture = true
                            }
                        }
                    },
                    onRemove = { index ->
                        captureDraft.remove(index)
                        if (pendingCaptureImageBytes.size == 1) showMultiPhotoCapture = false
                    },
                    onAnalyze = { note, progressiveMeal ->
                        if (!captureDraft.busy.value) {
                            val images = captureDraft.images.value
                            clearCaptureDraft()
                            showMultiPhotoCapture = false
                            vm.analyzePhotos(images, note, progressiveMeal)
                        }
                    },
                    onDismiss = {
                        showMultiPhotoCapture = false
                        clearCaptureDraft()
                    }
                )
            }

            editingEntry?.let { entry ->
                EditFoodEntrySheet(
                    entry = entry,
                    preferGramsByDefault = ui.preferGramsByDefault,
                    profile = ui.profile,
                    isFavorite = ui.isFavorite(entry),
                    container = container,
                    analyzeIngredientText = vm::analyzeIngredientText,
                    lookupIngredientBarcode = vm::lookupIngredientBarcode,
                    analyzeIngredientImage = vm::analyzeIngredientImage,
                    onReprocess = { updatedEntry, updatedNote ->
                        vm.reprocessFoodEntry(updatedEntry, updatedNote)
                    },
                    onSave = { updated ->
                        vm.updateEntry(updated)
                        editingEntry = null
                    },
                    onToggleFavorite = { vm.toggleFavorite(entry) },
                    onDelete = {
                        vm.deleteEntry(entry.id)
                        editingEntry = null
                    },
                    onDismiss = { editingEntry = null }
                )
            }

            if (showNutritionDetail) {
                NutritionDetailSheet(
                    entries = ui.todayEntries,
                    profile = ui.profile,
                    homeTopNutrients = ui.homeTopNutrients,
                    optionalGoals = ui.optionalNutrientGoals,
                    waterTrackingEnabled = ui.waterTrackingEnabled,
                    waterCurrentMl = ui.waterTodayMl,
                    waterGoalMl = ui.waterDailyGoalMl,
                    waterUnit = ui.waterUnit,
                    onHomeTopNutrientsChange = vm::setHomeTopNutrients,
                    onDismiss = { showNutritionDetail = false }
                )
            }

            if (ui.analyzing) AnalyzingOverlay(imageBytes = ui.pendingImageBytes)
            ui.pendingAnalysis?.let { analysis ->
                val initialTimestamp = remember(analysis) { vm.timestampForSelectedDay() }
                FoodResultSheet(
                    analysis = analysis,
                    imageBytesList = ui.pendingImageBytesList,
                    preferGramsByDefault = ui.preferGramsByDefault,
                    profile = ui.profile,
                    dayEntries = ui.todayEntries,
                    allEntries = container.foodRepository.entries.collectAsState(initial = emptyList()).value,
                    isSubmitting = ui.foodSaveInProgress,
                    container = container,
                    analyzeIngredientText = vm::analyzeIngredientText,
                    lookupIngredientBarcode = vm::lookupIngredientBarcode,
                    analyzeIngredientImage = vm::analyzeIngredientImage,
                    source = ui.pendingReviewSource?.source
                        ?: ui.pendingFoodSource
                        ?: if (ui.pendingImageBytes != null) FoodSource.SNAP_FOOD else FoodSource.TEXT_INPUT,
                    initialTimestamp = initialTimestamp,
                    onWhatIfSuggestion = vm::suggestMealWhatIf,
                    onSave = { name, grams, servingSizeIsKnown, scale, mealType, selectedServingUnit, selectedServingQuantity, editedAnalysis, timestamp ->
                        vm.saveAnalysis(
                            name = name,
                            servingGrams = grams,
                            servingSizeIsKnown = servingSizeIsKnown,
                            scale = scale,
                            mealType = mealType,
                            selectedServingUnit = selectedServingUnit,
                            selectedServingQuantity = selectedServingQuantity,
                            editedAnalysis = editedAnalysis,
                            timestamp = timestamp
                        )
                    },
                    onDismiss = { vm.dismissPending() }
                )
            }

            pendingDiaryDeletion?.let { target ->
                val title = when (target) {
                    is HomeDiaryItem.Food -> "Delete Food Log?"
                    is HomeDiaryItem.Water -> "Delete Water Log?"
                    is HomeDiaryItem.Fasting -> "Delete Fasting Log?"
                }
                val message = when (target) {
                    is HomeDiaryItem.Food -> "This removes the food from your diary. Saved favorites are kept."
                    is HomeDiaryItem.Water -> "This removes the water entry from your diary."
                    is HomeDiaryItem.Fasting -> "This removes the completed fast from your diary."
                }
                GlassDialog(onDismissRequest = { pendingDiaryDeletion = null }) {
                    Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                        fontSize = 15.sp,
                        lineHeight = 21.sp
                    )
                    GlassDialogActions(
                        primaryText = stringResource(R.string.action_delete),
                        onPrimary = {
                            pendingDiaryDeletion = null
                            when (target) {
                                is HomeDiaryItem.Food -> vm.deleteEntry(target.entry.id)
                                is HomeDiaryItem.Water -> vm.deleteWater(target.entry.id)
                                is HomeDiaryItem.Fasting -> vm.deleteFast(target.session.id)
                            }
                        },
                        dismissText = stringResource(R.string.action_cancel),
                        onDismiss = { pendingDiaryDeletion = null },
                        destructive = true
                    )
                }
            }

            editingFast?.let { session ->
                FastingSessionDialog(
                    session = session,
                    onSave = {
                        vm.updateFast(it)
                        editingFast = null
                    },
                    onEndNow = {
                        vm.endFast(it)
                        editingFast = null
                    },
                    onDelete = {
                        vm.deleteFast(session.id)
                        editingFast = null
                    },
                    onDismiss = { editingFast = null }
                )
            }

            if (showManual) {
                ManualEntryDialog(
                    onDismiss = { showManual = false },
                    onSave = { name, kcal, p, c, f, fiber, meal ->
                        showManual = false
                        vm.saveManualEntry(name, kcal, p, c, f, fiber, meal)
                    }
                )
            }

            ui.error?.let { err ->
                GlassDialog(onDismissRequest = { vm.dismissPending() }) {
                    Text(
                        stringResource(
                            if (ui.errorOffersScanLabel) R.string.error_barcode_title else R.string.error_title
                        ),
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(err, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f))
                    GlassDialogActions(
                        primaryText = stringResource(
                            if (ui.errorOffersScanLabel) R.string.action_scan_label else R.string.action_retry
                        ),
                        onPrimary = {
                            if (ui.errorOffersScanLabel) {
                                vm.dismissPending()
                                openCamera()
                            } else {
                                vm.retryPendingAnalysis()
                            }
                        },
                        dismissText = stringResource(R.string.action_cancel),
                        onDismiss = { vm.dismissPending() }
                    )
                }
            }
            if (ui.foodLoggingBlocked) {
                GlassDialog(onDismissRequest = vm::dismissFoodBlocked) {
                    Text(stringResource(R.string.food_logging_paused), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.food_blocked_by_active_fast),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                    )
                    GlassDialogActions(
                        primaryText = stringResource(R.string.action_ok),
                        onPrimary = vm::dismissFoodBlocked,
                        onDismiss = vm::dismissFoodBlocked
                    )
                }
            }
            if (showFastingQuickActionDisabled) {
                GlassDialog(onDismissRequest = { showFastingQuickActionDisabled = false }) {
                    Text(
                        stringResource(R.string.fasting_quick_action_disabled_title),
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.fasting_quick_action_disabled_message),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                    )
                    GlassDialogActions(
                        primaryText = stringResource(R.string.action_ok),
                        onPrimary = { showFastingQuickActionDisabled = false },
                        onDismiss = { showFastingQuickActionDisabled = false }
                    )
                }
            }
            if (ui.fastingOverlap) {
                GlassDialog(onDismissRequest = vm::dismissFastingOverlap) {
                    Text(stringResource(R.string.fasting_overlap_title), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.fasting_overlap_message),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                    )
                    GlassDialogActions(
                        primaryText = stringResource(R.string.action_ok),
                        onPrimary = vm::dismissFastingOverlap,
                        onDismiss = vm::dismissFastingOverlap
                    )
                }
            }
        }
    }
}
