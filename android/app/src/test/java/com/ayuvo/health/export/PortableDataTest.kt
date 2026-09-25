package com.ayuvo.health.export

import com.ayuvo.health.models.ActivityLevel
import com.ayuvo.health.models.AutoBalanceMacro
import com.ayuvo.health.models.BodyFatEntry
import com.ayuvo.health.models.BodyMeasurement
import com.ayuvo.health.models.CompletedExercise
import com.ayuvo.health.models.CompletedSet
import com.ayuvo.health.models.FastingSession
import com.ayuvo.health.models.Gender
import com.ayuvo.health.models.MealSchedule
import com.ayuvo.health.models.OptionalNutrient
import com.ayuvo.health.models.OptionalNutrientGoals
import com.ayuvo.health.models.PlannedExercise
import com.ayuvo.health.models.PlannedSet
import com.ayuvo.health.models.UserProfile
import com.ayuvo.health.models.WeightEntry
import com.ayuvo.health.models.WeightGoal
import com.ayuvo.health.models.WorkoutDayPlan
import com.ayuvo.health.models.WorkoutIntensity
import com.ayuvo.health.models.WorkoutIssue
import com.ayuvo.health.models.WorkoutPersistedState
import com.ayuvo.health.models.WorkoutPreferences
import com.ayuvo.health.models.WorkoutRpeScale
import com.ayuvo.health.models.WorkoutSession
import com.ayuvo.health.models.WorkoutSplit
import com.ayuvo.health.models.WorkoutStrengthNumbers
import com.ayuvo.health.models.WorkoutWeightUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * docs/portable-data.md "Test obligations": the shared fixture imports to the expected native values,
 * an own export imports back to itself, merging never duplicates or deletes, a bad file changes nothing.
 */
class PortableDataTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private fun fixtureText(): String {
        val file = listOf(
            "../../shared/portable/fixtures/portable-sample.json",
            "../shared/portable/fixtures/portable-sample.json",
            "shared/portable/fixtures/portable-sample.json"
        ).map(::File).firstOrNull { it.exists() }
        assertNotNull("shared/portable/fixtures/portable-sample.json not found", file)
        return file!!.readText()
    }

    private fun importInto(store: FakePortableStore, text: String): PortableImportResult =
        runBlocking { PortableDataImporter(store, zone).import(text) }

    private fun uuid(suffix: String, prefix: String = "0b1c2d3e") = UUID.fromString("$prefix-0000-4000-8000-$suffix")
    private fun at(iso: String) = Instant.parse(iso)

    // -- 1. The shared fixture into an empty store ----------------------------------------------------------

    @Test
    fun theSampleFixtureImportsProfileUnitsAndPreferences() {
        val store = FakePortableStore()
        val result = importInto(store, fixtureText())
        assertTrue(result.appliedProfile)

        val p = store.profile!!
        assertEquals("Sample User", p.name)
        assertEquals(Gender.FEMALE, p.gender)
        // The local calendar day of the file, start of day in this reader's zone.
        assertEquals(LocalDate.of(1994, 3, 17), p.birthday.atZone(zone).toLocalDate())
        assertEquals(LocalDate.of(1994, 3, 17).atStartOfDay(zone).toInstant(), p.birthday)
        assertEquals(168.5, p.heightCm, 0.0)
        assertEquals(64.2, p.weightKg, 0.0)
        assertEquals(ActivityLevel.VERY_ACTIVE, p.activityLevel)
        assertEquals(WeightGoal.LOSE, p.goal)
        assertEquals(0.27, p.bodyFatPercentage!!, 0.0)
        assertEquals(0.22, p.goalBodyFatPercentage!!, 0.0)
        assertEquals(0.5, p.weeklyChangeKg!!, 0.0)
        assertEquals(58.0, p.goalWeightKg!!, 0.0)
        assertEquals(listOf(1850, 130, 60, 190), listOf(p.customCalories, p.customProtein, p.customFat, p.customCarbs))
        assertEquals(AutoBalanceMacro.CARBS, p.autoBalanceMacro)
        assertEquals(listOf("peanut", "shellfish"), p.allergenSensitivities)
        assertTrue(p.caloriesLocked)
        assertEquals(setOf(AutoBalanceMacro.PROTEIN), p.lockedMacros)
        assertNull("legacy useBodyFatInBMR is not carried", p.useBodyFatInBMR)

        val s = store.settings!!
        assertEquals("cm", s.heightUnit)
        assertEquals("kg", s.weightUnit)
        assertEquals("floz", s.waterUnit)
        assertEquals("mmol/L", s.glucoseUnit)
        assertEquals(false, s.weekStartsOnMonday)
        assertEquals(8500, s.dailyStepGoal)
        assertEquals("dark", s.appearanceMode)
        assertEquals("teal", s.appThemeColor)
        assertEquals(false, s.adaptiveGoalsEnabled)
        assertEquals(true, s.preferGramsByDefault)
        assertEquals(MealSchedule(360, 780, 1140, 1320), s.mealSchedule)
        assertEquals(listOf("app:calories", "app:water", "steps"), s.summaryFavourites)
        assertEquals(true, s.waterTrackingEnabled)
        assertEquals(2500, s.waterDailyGoalMl)
        assertEquals(true, s.fastingTrackingEnabled)
        assertEquals(960, s.fastingDefaultGoalMinutes)
        assertEquals(false, s.fastingGoalNotificationEnabled)

        // The flat object is split back into Android's own fields and its supplement map.
        val goals = store.goals
        assertEquals(35, goals.fiber)
        assertEquals(2000, goals.sodium)
        assertEquals(OptionalNutrientGoals.Default.sugar, goals.sugar)
        assertEquals(mapOf("creatine" to 5), goals.supplementalNutrients)
        assertEquals(5, goals.valueFor(OptionalNutrient.CREATINE))
    }

    @Test
    fun theSampleFixtureImportsTheLogs() {
        val store = FakePortableStore()
        val result = importInto(store, fixtureText())

        assertEquals(
            listOf(65.4, 64.9, 64.2),
            store.weights.map { it.weightKg }
        )
        assertEquals(uuid("000000000001", "11111111"), store.weights.first().id)
        assertEquals(at("2026-09-15T07:31:10.000Z"), store.weights[1].date)
        assertEquals(listOf(0.275, 0.27), store.bodyFat.map { it.bodyFatFraction })
        val m = store.measurements.single()
        assertEquals(74.5, m.waistCm!!, 0.0)
        assertEquals(98.0, m.hipsCm!!, 0.0)
        assertEquals(31.5, m.neckCm!!, 0.0)
        assertNull(m.chestCm)
        assertNull(m.wristCm)

        assertEquals(2, store.fasting.size)
        val done = store.fasting[0]
        assertEquals(at("2026-09-20T18:00:00.000Z"), done.startedAt)
        assertEquals(at("2026-09-21T10:15:00.000Z"), done.endedAt)
        assertEquals(960, done.goalMinutes)
        val active = store.fasting[1]
        assertTrue(active.isActive)
        assertEquals(840, active.goalMinutes)

        assertEquals(3L, result.counts["weights"])
        assertEquals(2L, result.counts["body_fat"])
        assertEquals(1L, result.counts["body_measurements"])
        assertEquals(2L, result.counts["fasting_sessions"])
        assertEquals(2L, result.counts["workout_sessions"])
        assertEquals(1L, result.counts["user_exercises"])
    }

    @Test
    fun theSampleFixtureImportsWorkoutsWithAndroidEnumsAndStringValues() {
        val store = FakePortableStore(
            workout = WorkoutPersistedState(
                healthDeletionTombstones = mapOf("deadbeef" to "2026-01-01"),
                pendingHealthDeleteIds = setOf("x"),
                pendingHealthUpsertIds = setOf("y")
            )
        )
        importInto(store, fixtureText())
        val w = store.workout

        // The store keeps its own version (a different one would be discarded on read) and the Health bookkeeping.
        assertEquals(WorkoutPersistedState.CurrentVersion, w.version)
        assertEquals(mapOf("deadbeef" to "2026-01-01"), w.healthDeletionTombstones)
        assertEquals(setOf("x"), w.pendingHealthDeleteIds)
        assertEquals(setOf("y"), w.pendingHealthUpsertIds)

        // lower snake case in the file, Android's constant names natively
        val prefs = w.preferences
        assertEquals(setOf("chest", "back"), prefs.targetMuscles)
        assertEquals(setOf(WorkoutIssue.LOWER_BACK, WorkoutIssue.KNEE), prefs.issues)
        assertEquals(4, prefs.frequencyDays)
        assertEquals(75, prefs.durationMinutes)
        assertEquals(WorkoutSplit.UPPER_LOWER, prefs.split)
        assertEquals(setOf("barbell", "dumbbell"), prefs.equipment)
        assertEquals(WorkoutRpeScale.CR10, prefs.rpeScale)
        assertEquals(WorkoutStrengthNumbers(60.0, 85.5, 105.0, 40.0), prefs.strength)
        // The app's own sanitizer drops additional_issues unless "other" is selected; the fixture has it without.
        assertEquals("", prefs.additionalIssues)

        assertEquals(setOf("0001", "user_exercise_44444444-0000-4000-8000-000000000001"), w.savedExerciseIds)
        val user = w.userExercises.single()
        assertEquals("user_exercise_44444444-0000-4000-8000-000000000001", user.itemId)
        assertEquals("Sandbag carry", user.name)
        assertEquals(listOf("core"), user.primaryMuscles)
        assertEquals(listOf("Hug the bag", "Walk 20 m"), user.instructions)
        assertTrue(user.sets.isEmpty())
        assertEquals("Padel", w.customActivities.single().name)

        val planned = w.dayPlans.getValue("2026-09-26").exercises.single()
        assertEquals("0001", planned.itemId)
        assertEquals("https://example.com/0001.gif", planned.gifUrl)
        val first = planned.sets[0]
        // weight, reps and rpe stay strings exactly as written
        assertEquals("0", first.weight)
        assertEquals(WorkoutWeightUnit.KG, first.weightUnit)
        assertEquals("15", first.reps)
        assertEquals("6", first.rpe)
        assertEquals(WorkoutRpeScale.CR10, first.rpeScale)
        val second = planned.sets[1]
        assertEquals("", second.weight)
        assertNull(second.weightUnit)
        assertEquals("7.5", second.rpe)
        assertNull(second.rpeScale)

        val done = w.completedSessions.first { it.caloriesBurned == null }
        assertEquals("2026-09-24", done.diaryDateKey)
        assertEquals(3150, done.durationSeconds)
        assertNull("imported sessions carry no Health sync version", done.healthSyncVersion)
        val exercise = done.exercises.single()
        assertEquals(240.5, exercise.durationSeconds!!, 0.0)
        assertEquals(WorkoutIntensity.VIGOROUS, exercise.intensity)
        assertEquals(listOf(1, 2), exercise.sets.map { it.setNumber })
        assertEquals(listOf("20", "22.5"), exercise.sets.map { it.weight })
        assertEquals(listOf(WorkoutWeightUnit.LBS, WorkoutWeightUnit.LBS), exercise.sets.map { it.weightUnit })
        assertEquals(listOf(WorkoutRpeScale.BORG, null), exercise.sets.map { it.rpeScale })
        val burn = w.completedSessions.first { it.caloriesBurned != null }
        assertEquals(420, burn.caloriesBurned)
        assertEquals("2026-09-25", burn.diaryDateKey)
        assertNull(burn.healthSyncVersion)
    }

    @Test
    fun theBirthdayIsALocalDayInAnyReadersZone() {
        val text = fixtureText()
        for (id in listOf("Pacific/Kiritimati", "America/Adak", "UTC")) {
            val z = ZoneId.of(id)
            val store = FakePortableStore()
            runBlocking { PortableDataImporter(store, z).import(text) }
            assertEquals(id, LocalDate.of(1994, 3, 17), store.profile!!.birthday.atZone(z).toLocalDate())
        }
        // ...and written out as that day, whatever the zone the instant fell in.
        for (id in listOf("Pacific/Kiritimati", "America/Adak")) {
            val z = ZoneId.of(id)
            val birthday = LocalDate.of(1994, 3, 17).atStartOfDay(z).toInstant()
            val json = PortableFormat.toJson(document(profile = profile(birthday)), z)
            assertEquals(id, "1994-03-17", (json["profile"] as JsonObject)["birthday"].let { (it as JsonPrimitive).content })
        }
    }

    // -- Enum mapping ---------------------------------------------------------------------------------------

    @Test
    fun workoutEnumsMapBetweenAndroidUpperCaseAndPortableLowerSnakeCase() {
        val splits = mapOf(
            WorkoutSplit.PUSH_PULL_LEGS to "push_pull_legs", WorkoutSplit.UPPER_LOWER to "upper_lower",
            WorkoutSplit.BODY_PART to "body_part", WorkoutSplit.ARNOLD to "arnold", WorkoutSplit.PUSH_PULL to "push_pull",
            WorkoutSplit.ANTAGONIST to "antagonist", WorkoutSplit.HYBRID to "hybrid", WorkoutSplit.FULL_BODY to "full_body"
        )
        for ((android, portable) in splits) {
            assertEquals(portable, PortableFormat.splitWire(android))
            assertEquals(android, PortableFormat.splitFromWire(portable))
            assertEquals(android, PortableFormat.splitFromWire(android.name)) // upper case is read too
        }
        // Android has no custom split: the file's `custom` reads as full body.
        assertEquals(WorkoutSplit.FULL_BODY, PortableFormat.splitFromWire("custom"))
        assertNull(PortableFormat.splitFromWire("pushPullLegs")) // iOS spelling never appears in the file

        val issues = mapOf(
            WorkoutIssue.SHOULDER to "shoulder", WorkoutIssue.ELBOW to "elbow", WorkoutIssue.WRIST to "wrist",
            WorkoutIssue.LOWER_BACK to "lower_back", WorkoutIssue.HIP to "hip", WorkoutIssue.KNEE to "knee",
            WorkoutIssue.ANKLE to "ankle", WorkoutIssue.OTHER to "other"
        )
        for ((android, portable) in issues) {
            assertEquals(portable, PortableFormat.issueWire(android))
            assertEquals(android, PortableFormat.issueFromWire(portable))
        }
        assertEquals(setOf("strength", "cr10", "borg"), WorkoutRpeScale.entries.map(PortableFormat::rpeScaleWire).toSet())
        assertEquals(WorkoutRpeScale.CR10, PortableFormat.rpeScaleFromWire("cr10"))
        assertEquals("kg", PortableFormat.weightUnitWire(WorkoutWeightUnit.KG))
        assertEquals(WorkoutWeightUnit.LBS, PortableFormat.weightUnitFromWire("lbs"))
        assertEquals(setOf("light", "moderate", "vigorous"), WorkoutIntensity.entries.map(PortableFormat::intensityWire).toSet())
        assertNull(PortableFormat.issueFromWire("neck"))
    }

    @Test
    fun theWrittenFileUsesPortableSpellingsNotAndroidConstantNames() {
        val text = PortableFormat.encode(document(workouts = workouts()), zone)
        val w = ((Json.parseToJsonElement(text) as JsonObject)["workouts"]) as JsonObject
        val prefs = w["preferences"] as JsonObject
        assertEquals("push_pull_legs", (prefs["split"] as JsonPrimitive).content)
        assertEquals("borg", (prefs["rpe_scale"] as JsonPrimitive).content)
        assertEquals(listOf("lower_back", "other"), (prefs["issues"] as JsonArray).map { (it as JsonPrimitive).content })
        assertFalse(text.contains("PUSH_PULL_LEGS"))
        assertFalse(text.contains("LOWER_BACK"))
        assertFalse(text.contains("\"KG\"") || text.contains("\"LBS\"") || text.contains("VIGOROUS"))
    }

    // -- 2. Round trip -----------------------------------------------------------------------------------------

    private fun profile(birthday: Instant = LocalDate.of(1990, 5, 20).atStartOfDay(zone).toInstant()) = UserProfile(
        name = "Round Trip", gender = Gender.OTHER, birthday = birthday, heightCm = 180.0, weightKg = 80.5,
        activityLevel = ActivityLevel.EXTRA_ACTIVE, goal = WeightGoal.GAIN, bodyFatPercentage = 0.18,
        goalBodyFatPercentage = 0.15, weeklyChangeKg = 0.25, goalWeightKg = 85.0, customCalories = 2800,
        customProtein = 180, customFat = 80, customCarbs = 350, autoBalanceMacro = AutoBalanceMacro.FAT,
        allergenSensitivities = listOf("gluten", "soy"), caloriesLocked = true,
        lockedMacros = setOf(AutoBalanceMacro.PROTEIN, AutoBalanceMacro.CARBS)
    )

    private fun plannedExercise(id: String, itemId: String = "0001") = PlannedExercise(
        id = uuid(id, "55555555"), itemId = itemId, name = "Squat", bodyPart = "upper legs", equipment = "barbell",
        primaryMuscles = listOf("quads"), secondaryMuscles = listOf("glutes"), instructions = listOf("Stand tall"),
        imageUrl = "https://example.com/a.png", gifUrl = null,
        sets = listOf(
            PlannedSet(uuid("000000000001", "77777777"), "100", WorkoutWeightUnit.KG, "5", "8", WorkoutRpeScale.STRENGTH),
            PlannedSet(uuid("000000000002", "77777777"), "", null, "", "", null)
        )
    )

    private fun workouts() = PortableWorkouts(
        preferences = WorkoutPreferences(
            targetMuscles = setOf("Chest", "Back"), issues = setOf(WorkoutIssue.OTHER, WorkoutIssue.LOWER_BACK),
            additionalIssues = "torn cuff", frequencyDays = 5, durationMinutes = 45, split = WorkoutSplit.PUSH_PULL_LEGS,
            equipment = setOf("barbell"), rpeScale = WorkoutRpeScale.BORG,
            strength = WorkoutStrengthNumbers(80.0, null, 120.5, 50.0)
        ),
        savedExerciseIds = setOf("0001", "Walking_Outdoor"),
        userExercises = listOf(plannedExercise("000000000001", "user_exercise_1").copy(sets = emptyList())),
        customActivities = listOf(plannedExercise("000000000002", "custom_activity_1").copy(bodyPart = "cardio")),
        dayPlans = mapOf("2026-09-26" to listOf(plannedExercise("000000000003"))),
        sessions = listOf(
            WorkoutSession(
                id = uuid("000000000001", "88888888"), diaryDateKey = "2026-09-24",
                startedAt = at("2026-09-24T17:00:00.250Z"), completedAt = at("2026-09-24T17:52:30.000Z"), durationSeconds = 3150,
                exercises = listOf(
                    CompletedExercise(
                        id = uuid("000000000001", "99999999"), itemId = "0001", name = "Squat", targetMuscles = listOf("quads"),
                        equipment = "barbell", durationSeconds = 61.5, intensity = WorkoutIntensity.LIGHT,
                        sets = listOf(CompletedSet(uuid("000000000001", "aaaaaaaa"), 1, "102.5", WorkoutWeightUnit.KG, "5", "8.5", WorkoutRpeScale.CR10))
                    )
                )
            ),
            WorkoutSession(
                id = uuid("000000000002", "88888888"), diaryDateKey = "2026-09-25", startedAt = at("2026-09-25T06:00:00.000Z"),
                completedAt = at("2026-09-25T06:00:00.000Z"), durationSeconds = 0, exercises = emptyList(), caloriesBurned = 420
            )
        )
    )

    private fun document(
        profile: UserProfile? = null,
        settings: PortableSettings = PortableSettings(),
        workouts: PortableWorkouts? = null,
        weights: List<WeightEntry> = emptyList()
    ) = PortableDocument(
        createdAt = at("2026-09-25T10:30:00.000Z"), platform = "android", appVersion = "1.0",
        profile = profile, settings = settings, weights = weights, workouts = workouts
    )

    private fun fullDocument() = PortableDocument(
        createdAt = at("2026-09-25T10:30:00.123Z"),
        platform = "android",
        appVersion = "1.2.3",
        profile = profile(),
        settings = PortableSettings(
            heightUnit = "ftin", weightUnit = "lbs", waterUnit = "floz", glucoseUnit = "mg/dL", weekStartsOnMonday = false,
            dailyStepGoal = 12_000, appearanceMode = "light", appThemeColor = "roseGold", adaptiveGoalsEnabled = false,
            preferGramsByDefault = true, mealSchedule = MealSchedule(300, 700, 1100, 1300),
            summaryFavourites = listOf("app:calories", "steps"),
            optionalNutrientGoals = mapOf(OptionalNutrient.FIBER to 40, OptionalNutrient.SODIUM to 1500, OptionalNutrient.HMB to 3),
            waterTrackingEnabled = true, waterDailyGoalMl = 3000, fastingTrackingEnabled = true,
            fastingDefaultGoalMinutes = 1200, fastingGoalNotificationEnabled = false
        ),
        weights = listOf(
            WeightEntry(uuid("000000000001", "11111111"), at("2026-09-01T07:30:00.000Z"), 81.0),
            WeightEntry(uuid("000000000002", "11111111"), at("2026-09-25T07:30:00.500Z"), 80.5)
        ),
        bodyFat = listOf(BodyFatEntry(uuid("000000000001", "22222222"), at("2026-09-10T08:00:00.000Z"), 0.185)),
        bodyMeasurements = listOf(
            BodyMeasurement(uuid("000000000001", "33333333"), at("2026-09-12T09:00:00.000Z"), neckCm = 38.0, waistCm = 84.5, wristCm = 17.0)
        ),
        fastingSessions = listOf(
            FastingSession(uuid("000000000001"), at("2026-09-20T18:00:00.000Z"), at("2026-09-21T10:15:00.000Z"), 960),
            FastingSession(uuid("000000000002"), at("2026-09-24T19:00:00.000Z"), null, 840)
        ),
        workouts = workouts()
    )

    @Test
    fun anOwnExportImportsBackToTheSameNativeData() {
        val doc = fullDocument()
        val export = PortableDataExporter.build(doc, zone)!!
        val parsed = PortableFormat.parse(export.json, zone) as PortableParse.Ok
        assertEquals(doc, parsed.document)

        val store = FakePortableStore()
        importInto(store, export.json)
        assertEquals(doc.profile, store.profile)
        assertEquals(doc.settings, store.settings)
        assertEquals(doc.weights, store.weights)
        assertEquals(doc.bodyFat, store.bodyFat)
        assertEquals(doc.bodyMeasurements, store.measurements)
        assertEquals(doc.fastingSessions, store.fasting)
        val w = store.workout
        val src = doc.workouts!!
        assertEquals(src.preferences, w.preferences)
        assertEquals(src.savedExerciseIds, w.savedExerciseIds)
        assertEquals(src.userExercises, w.userExercises)
        assertEquals(src.customActivities, w.customActivities)
        assertEquals(src.dayPlans.mapValues { it.value }, w.dayPlans.mapValues { it.value.exercises })
        assertEquals(src.sessions, w.completedSessions)
        assertEquals(40, store.goals.fiber)
        assertEquals(3, store.goals.valueFor(OptionalNutrient.HMB))
    }

    @Test
    fun theExportCarriesCountsAndOmitsWhatIsEmpty() {
        val export = PortableDataExporter.build(fullDocument(), zone)!!
        assertEquals(
            mapOf(
                "weights" to 2L, "body_fat" to 1L, "body_measurements" to 1L, "fasting_sessions" to 2L,
                "workout_sessions" to 2L, "user_exercises" to 1L, "settings" to 18L
            ),
            export.counts
        )
        val onlyWeight = PortableDataExporter.build(
            document(weights = listOf(WeightEntry(uuid("000000000001", "11111111"), at("2026-09-01T07:30:00.000Z"), 70.0))), zone
        )!!
        assertEquals(mapOf("weights" to 1L), onlyWeight.counts)
    }

    @Test
    fun nothingToCarryIsNull() {
        assertNull(PortableDataExporter.build(document(), zone))
        // Untouched defaults are not "something": a fresh install has nothing to carry.
        assertNull(PortableDataExporter.build(document(settings = PortableSettings.Defaults), zone))
        assertNotNull(PortableDataExporter.build(document(settings = PortableSettings.Defaults.copy(dailyStepGoal = 9000)), zone))
        assertNotNull(PortableDataExporter.build(document(profile = profile()), zone))
    }

    @Test
    fun theFileIsWrittenInTheContractsEncoding() {
        val json = Json.parseToJsonElement(PortableDataExporter.build(fullDocument(), zone)!!.json) as JsonObject
        assertEquals("ayuvo-portable-data", (json["format"] as JsonPrimitive).content)
        assertEquals("1", (json["format_version"] as JsonPrimitive).content)
        assertEquals("android", (json["platform"] as JsonPrimitive).content)
        assertEquals("2026-09-25T10:30:00.123Z", (json["created_at"] as JsonPrimitive).content)

        val profile = json["profile"] as JsonObject
        assertEquals("1990-05-20", (profile["birthday"] as JsonPrimitive).content)
        assertEquals("extraActive", (profile["activity_level"] as JsonPrimitive).content)
        assertEquals("other", (profile["gender"] as JsonPrimitive).content)
        // ints stay ints, not 2800.0
        assertEquals("2800", (profile["custom_calories"] as JsonPrimitive).content)
        assertEquals(listOf("carbs", "protein"), (profile["locked_macros"] as JsonArray).map { (it as JsonPrimitive).content }.sorted())

        val goals = (json["preferences"] as JsonObject)["optional_nutrient_goals"] as JsonObject
        assertEquals("40", (goals["fiber"] as JsonPrimitive).content)
        assertEquals("3", (goals["hmb"] as JsonPrimitive).content) // one flat object: the supplement sits beside fiber
        assertFalse(goals.containsKey("supplementalNutrients"))

        val weight = ((json["weights"] as JsonArray)[1] as JsonObject)
        assertEquals("2026-09-25T07:30:00.500Z", (weight["date"] as JsonPrimitive).content)
        assertEquals("81.0", (((json["weights"] as JsonArray)[0] as JsonObject)["weight_kg"] as JsonPrimitive).content)

        val session = ((json["fasting"] as JsonObject)["sessions"] as JsonArray)[1] as JsonObject
        assertFalse("an active fast has no ended_at, not a null", session.containsKey("ended_at"))

        val set = ((((json["workouts"] as JsonObject)["day_plans"] as JsonObject)["2026-09-26"] as JsonArray)[0] as JsonObject)
            .let { (it["sets"] as JsonArray)[0] as JsonObject }
        assertTrue("weights, reps and rpe are strings", (set["weight"] as JsonPrimitive).isString)
        assertTrue((set["reps"] as JsonPrimitive).isString)
        assertTrue((set["rpe"] as JsonPrimitive).isString)

        val text = PortableDataExporter.build(fullDocument(), zone)!!.json
        assertFalse(text.contains("null"))
        listOf("healthSyncVersion", "tombstone", "pendingHealth", "image_paths", "imagePaths", "timer").forEach {
            assertFalse(it, text.contains(it))
        }
    }

    @Test
    fun workoutsOfDropsHealthBookkeepingPhotosAndTimers() {
        val exercise = plannedExercise("000000000003").copy(imagePaths = listOf("photo.jpg"), timer = com.ayuvo.health.models.ExerciseTimer(accumulatedSeconds = 12.0))
        val state = WorkoutPersistedState(
            dayPlans = mapOf("2026-09-26" to WorkoutDayPlan("2026-09-26", listOf(exercise)), "2026-09-27" to WorkoutDayPlan("2026-09-27")),
            healthDeletionTombstones = mapOf("a" to "2026-01-01"),
            pendingHealthDeleteIds = setOf("a"),
            pendingHealthUpsertIds = setOf("b")
        )
        val carried = PortableDataExporter.workoutsOf(state)!!
        assertEquals(setOf("2026-09-26"), carried.dayPlans.keys)
        val text = PortableFormat.encode(document(workouts = carried), zone)
        assertFalse(text.contains("photo.jpg"))
        assertFalse(text.contains("accumulatedSeconds"))
        assertNull("an untouched diary is not carried", PortableDataExporter.workoutsOf(WorkoutPersistedState()))
    }

    // -- 3. Merge -----------------------------------------------------------------------------------------------

    @Test
    fun importingTwiceAddsNothing() {
        val store = FakePortableStore()
        val text = fixtureText()
        importInto(store, text)
        val before = store.snapshot()
        val second = importInto(store, text)
        assertEquals(before, store.snapshot())
        assertEquals(0, second.weights + second.bodyFat + second.bodyMeasurements + second.fastingSessions)
        assertEquals(0, second.workoutSessions + second.userExercises + second.dayPlans)
        assertTrue(second.counts.keys.all { it == "settings" })
    }

    @Test
    fun aSameIdOrSameSecondSameValueWeightIsNotDuplicated() {
        val healthConnectRow = WeightEntry(UUID.randomUUID(), at("2026-09-01T07:30:00.400Z"), 65.4) // same second and value, other id
        val sameIdOtherValue = WeightEntry(uuid("000000000002", "11111111"), at("2026-09-15T07:31:10.000Z"), 60.0)
        val sameSecondOtherValue = WeightEntry(UUID.randomUUID(), at("2026-09-25T07:29:00.000Z"), 70.0)
        val store = FakePortableStore(weights = listOf(healthConnectRow, sameIdOtherValue, sameSecondOtherValue))
        val result = importInto(store, fixtureText())

        // only the third file row (same second, other value) is new; the first two are already here
        assertEquals(1, result.weights)
        assertEquals(4, store.weights.size)
        assertEquals(1, store.weights.count { it.weightKg == 64.2 })
        assertEquals("the local value of a same-id row is kept", 60.0, store.weights.first { it.id == sameIdOtherValue.id }.weightKg, 0.0)
        assertEquals(store.weights.sortedBy { it.date }, store.weights)
    }

    @Test
    fun idsAreComparedCaseInsensitively() {
        val existing = WeightEntry(UUID.fromString("aaaaaaaa-0000-4000-8000-00000000000a"), at("2026-09-01T00:00:00.000Z"), 50.0)
        val store = FakePortableStore(weights = listOf(existing))
        val text = """{"format":"ayuvo-portable-data","format_version":1,
            "weights":[{"id":"AAAAAAAA-0000-4000-8000-00000000000A","date":"2026-09-02T00:00:00Z","weight_kg":51.0}]}"""
        val result = importInto(store, text)
        assertEquals(0, result.weights)
        assertEquals(listOf(existing), store.weights)
    }

    @Test
    fun aSecondActiveFastAndAnOverlapAreDropped() {
        val active = FastingSession(UUID.randomUUID(), at("2026-09-26T08:00:00.000Z"), null, 960)
        val store = FakePortableStore(fasting = listOf(active))
        val result = importInto(store, fixtureText())
        // the file's active fast (started 09-24) would overlap the local active one; its finished fast fits before it
        assertEquals(1, result.fastingSessions)
        assertEquals(2, store.fasting.size)
        assertEquals(1, store.fasting.count { it.isActive })
        assertEquals(active.id, store.fasting.single { it.isActive }.id)

        val overlapping = FastingSession(UUID.randomUUID(), at("2026-09-20T20:00:00.000Z"), at("2026-09-21T02:00:00.000Z"), 240)
        val store2 = FakePortableStore(fasting = listOf(overlapping))
        val result2 = importInto(store2, fixtureText())
        // the finished 09-20 18:00 -> 09-21 10:15 fast overlaps the local one and is dropped; the active one fits
        assertEquals(1, result2.fastingSessions)
        assertEquals(listOf(overlapping.id), store2.fasting.filter { !it.isActive }.map { it.id })
    }

    @Test
    fun aSameDayCalorieSnapshotIsNotDoubled() {
        val localSnapshot = WorkoutSession(
            id = UUID.randomUUID(), diaryDateKey = "2026-09-25", startedAt = at("2026-09-25T12:00:00Z"),
            completedAt = at("2026-09-25T12:00:00Z"), durationSeconds = 0, exercises = emptyList(), caloriesBurned = 300, healthSyncVersion = 4
        )
        val store = FakePortableStore(workout = WorkoutPersistedState(completedSessions = listOf(localSnapshot)))
        val result = importInto(store, fixtureText())
        assertEquals(1, result.workoutSessions) // the 09-24 session; the 09-25 snapshot is skipped
        assertEquals(1, store.workout.completedSessions.count { it.caloriesBurned != null })
        assertEquals(300, store.workout.completedSessions.first { it.caloriesBurned != null }.caloriesBurned)
        assertEquals(4, store.workout.completedSessions.first { it.caloriesBurned != null }.healthSyncVersion)
    }

    @Test
    fun aDeletedBurnStaysDeletedAndTwoSnapshotsInOneFileKeepTheFirst() {
        val burnId = uuid("000000000002", "88888888").toString()
        val store = FakePortableStore(workout = WorkoutPersistedState(healthDeletionTombstones = mapOf(burnId to "2026-09-25")))
        assertEquals(1, importInto(store, fixtureText()).workoutSessions)
        assertTrue(store.workout.completedSessions.none { it.caloriesBurned != null })

        val twin = """{"format":"ayuvo-portable-data","format_version":1,"workouts":{"sessions":[
            {"id":"88888888-0000-4000-8000-0000000000a1","diary_date_key":"2026-09-01","started_at":"2026-09-01T10:00:00Z","completed_at":"2026-09-01T10:00:00Z","calories_burned":100},
            {"id":"88888888-0000-4000-8000-0000000000a2","diary_date_key":"2026-09-01","started_at":"2026-09-01T11:00:00Z","completed_at":"2026-09-01T11:00:00Z","calories_burned":200}]}}"""
        val fresh = FakePortableStore()
        assertEquals(1, importInto(fresh, twin).workoutSessions)
        assertEquals(100, fresh.workout.completedSessions.single().caloriesBurned)
    }

    @Test
    fun aDateAlreadyPlannedIsKeptAndSavedIdsAreAUnion() {
        val local = plannedExercise("0000000000ff", "local")
        val store = FakePortableStore(
            workout = WorkoutPersistedState(
                dayPlans = mapOf("2026-09-26" to WorkoutDayPlan("2026-09-26", listOf(local))),
                savedExerciseIds = setOf("keep-me"),
                preferences = WorkoutPreferences(frequencyDays = 6)
            )
        )
        val result = importInto(store, fixtureText())
        assertEquals(0, result.dayPlans)
        assertEquals(listOf(local), store.workout.dayPlans.getValue("2026-09-26").exercises)
        assertEquals(setOf("keep-me", "0001", "user_exercise_44444444-0000-4000-8000-000000000001"), store.workout.savedExerciseIds)
        assertEquals("preferences are replaced by the file's", 4, store.workout.preferences.frequencyDays)
    }

    @Test
    fun anImportWritesTheProfileLastAndNeverWhenTheFileHasNone() {
        val store = FakePortableStore()
        importInto(store, fixtureText())
        assertEquals("profile", store.calls.last())
        assertFalse("a file with a profile does not sync it to the logs", store.calls.contains("sync"))

        val logsOnly = """{"format":"ayuvo-portable-data","format_version":1,
            "weights":[{"id":"11111111-0000-4000-8000-000000000009","date":"2026-09-30T07:00:00Z","weight_kg":63.0}]}"""
        val other = FakePortableStore()
        val result = importInto(other, logsOnly)
        assertFalse(result.appliedProfile)
        assertEquals(listOf("weights", "sync:weights"), other.calls)
        assertNull(other.profile)
    }

    // -- 4. Refusals --------------------------------------------------------------------------------------------

    private fun assertRefused(text: String, reason: PortableRefusal) {
        val store = FakePortableStore(weights = listOf(WeightEntry(UUID.randomUUID(), at("2026-01-01T00:00:00Z"), 70.0)))
        val before = store.snapshot()
        try {
            importInto(store, text)
            fail("expected a refusal: $text")
        } catch (e: PortableRefusedException) {
            assertEquals(reason, e.reason)
        }
        assertEquals("no store may be touched", before, store.snapshot())
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun aWrongFormatANewerVersionOrNonJsonLeavesEverythingUntouched() {
        assertRefused(fixtureText().replace("\"ayuvo-portable-data\"", "\"ayuvo-food-diary\""), PortableRefusal.WRONG_FORMAT)
        assertRefused(fixtureText().replace("\"format_version\": 1", "\"format_version\": 2"), PortableRefusal.NEWER_VERSION)
        assertRefused(fixtureText().replace("\"format_version\": 1,", ""), PortableRefusal.WRONG_FORMAT)
        assertRefused(fixtureText().replace("\"format_version\": 1", "\"format_version\": 0"), PortableRefusal.WRONG_FORMAT)
        assertRefused("this is not json", PortableRefusal.NOT_JSON)
        assertRefused("", PortableRefusal.NOT_JSON)
        assertRefused("[1,2,3]", PortableRefusal.NOT_JSON)
        assertRefused("{\"weights\":[", PortableRefusal.NOT_JSON)
        assertRefused("{\"weights\":[]}", PortableRefusal.WRONG_FORMAT)
        assertEquals("This file was made by a newer version of Ayuvo", PortableRefusedException(PortableRefusal.NEWER_VERSION).message)
    }

    // -- Leniency: one bad value never costs the file ---------------------------------------------------------

    @Test
    fun outOfRangeAndUnknownValuesAreIgnoredOneByOne() {
        val text = """{"format":"ayuvo-portable-data","format_version":1,
          "units":{"height_unit":"furlongs","weight_unit":"lbs"},
          "preferences":{"daily_step_goal":5,"appearance_mode":"sepia","app_theme_color":"nope","week_starts_on_monday":true,
            "meal_start_minutes":{"breakfast":600,"lunch":600,"dinner":1000,"snack":1200},
            "summary_favourites":["a","b,c","","a","d"],
            "optional_nutrient_goals":{"fiber":45,"sodium":-1,"iron":1000000,"unknownNutrient":3,"taurine":2}},
          "water":{"settings":{"daily_goal_ml":5,"tracking_enabled":true}},
          "fasting":{"settings":{"default_goal_minutes":30,"goal_notification_enabled":false},"sessions":[
             {"id":"not-a-uuid","started_at":"2026-09-20T18:00:00Z"},
             {"id":"0b1c2d3e-0000-4000-8000-0000000000aa","started_at":"2026-09-20T18:00:00+05:30","ended_at":"2026-09-21T10:15:00Z","goal_minutes":5},
             {"id":"0b1c2d3e-0000-4000-8000-0000000000ab","started_at":"2026-10-20T18:00:00Z","ended_at":"garbage"}]},
          "body_fat":[{"id":"22222222-0000-4000-8000-0000000000aa","date":"2026-09-10T08:00:00Z","fraction":27}],
          "weights":[{"id":"11111111-0000-4000-8000-0000000000aa","date":"2026-09-10T08:00:00Z","weight_kg":"71.5"},
                     {"id":"11111111-0000-4000-8000-0000000000ab","date":"not a date","weight_kg":70}]}"""
        val store = FakePortableStore()
        importInto(store, text)
        val s = store.settings!!
        assertNull(s.heightUnit)
        assertEquals("lbs", s.weightUnit)
        assertNull(s.dailyStepGoal)
        assertNull(s.appearanceMode)
        assertNull(s.appThemeColor)
        assertEquals(true, s.weekStartsOnMonday)
        assertNull("not strictly increasing: the whole group is ignored", s.mealSchedule)
        assertEquals(listOf("a", "d"), s.summaryFavourites)
        assertEquals(mapOf(OptionalNutrient.FIBER to 45, OptionalNutrient.TAURINE to 2), s.optionalNutrientGoals)
        assertNull(s.waterDailyGoalMl)
        assertEquals(true, s.waterTrackingEnabled)
        assertNull(s.fastingDefaultGoalMinutes)
        assertEquals(false, s.fastingGoalNotificationEnabled)
        // the row with an unreadable ended_at is dropped rather than turned into an active fast; 5 min goal is clamped to 60
        assertEquals(listOf(60), store.fasting.map { it.goalMinutes })
        assertEquals(at("2026-09-20T12:30:00.000Z"), store.fasting.single().startedAt)
        assertTrue("a percentage is not a fraction", store.bodyFat.isEmpty())
        assertEquals(listOf(71.5), store.weights.map { it.weightKg })
    }

    @Test
    fun anIncompleteProfileIsIgnoredNotGuessed() {
        val text = """{"format":"ayuvo-portable-data","format_version":1,"profile":{"name":"X","gender":"female","height_cm":170,"weight_kg":60}}"""
        val store = FakePortableStore()
        val result = importInto(store, text)
        assertFalse(result.appliedProfile)
        assertNull(store.profile)
    }

    @Test
    fun timestampsAcceptAnOffsetAndNoFraction() {
        assertEquals(at("2026-09-25T05:00:00Z"), PortableFormat.parseInstant("2026-09-25T10:30:00+05:30"))
        assertEquals(at("2026-09-25T10:30:00Z"), PortableFormat.parseInstant("2026-09-25T10:30:00Z"))
        assertEquals(at("2026-09-25T10:30:00.007Z"), PortableFormat.parseInstant("2026-09-25T10:30:00.007Z"))
        assertNull(PortableFormat.parseInstant("1758796200"))
        assertNull(PortableFormat.parseInstant(null))
        assertEquals("2026-09-25T10:30:00.000Z", PortableFormat.formatInstant(at("2026-09-25T10:30:00Z")))
    }

    @Test
    fun numbersWhereTextBelongsAreReadAsTheirText() {
        val text = """{"format":"ayuvo-portable-data","format_version":1,"workouts":{"day_plans":{"2026-09-26":[
            {"id":"55555555-0000-4000-8000-0000000000aa","item_id":"0001","name":"Sit-up","sets":[{"weight":7.5,"reps":12,"rpe":"7.5"}]}]}}}"""
        val store = FakePortableStore()
        importInto(store, text)
        val set = store.workout.dayPlans.getValue("2026-09-26").exercises.single().sets.single()
        assertEquals("7.5", set.weight)
        assertEquals("12", set.reps)
        assertEquals("7.5", set.rpe)
    }

    @Test
    fun theSplitGoalsAndSupplementsMergeOntoTheGoalsAlreadyHere() {
        val current = OptionalNutrientGoals.Default.withValue(OptionalNutrient.SUGAR, 40)
        val merged = PreferencesPortableStore.mergedGoals(current, mapOf(OptionalNutrient.FIBER to 35, OptionalNutrient.CREATINE to 5))
        assertEquals(40, merged.sugar)
        assertEquals(35, merged.fiber)
        assertEquals(mapOf("creatine" to 5), merged.supplementalNutrients)
        val flat = PortableDataExporter.goalsToMap(merged)
        assertEquals(35, flat[OptionalNutrient.FIBER])
        assertEquals(5, flat[OptionalNutrient.CREATINE])
        assertFalse("a supplement nobody set is not exported", flat.containsKey(OptionalNutrient.HMB))
        assertTrue(PortableDataExporter.goalsToMap(OptionalNutrientGoals.Default).isEmpty())
    }
}

/** In-memory [PortableDataStore] that records the order of the writes; the workout store sanitizes like the app's. */
internal class FakePortableStore(
    var weights: List<WeightEntry> = emptyList(),
    var bodyFat: List<BodyFatEntry> = emptyList(),
    var measurements: List<BodyMeasurement> = emptyList(),
    var fasting: List<FastingSession> = emptyList(),
    var workout: WorkoutPersistedState = WorkoutPersistedState()
) : PortableDataStore {
    var settings: PortableSettings? = null
    var goals: OptionalNutrientGoals = OptionalNutrientGoals.Default
    var profile: UserProfile? = null
    val calls = mutableListOf<String>()

    fun snapshot(): List<Any?> = listOf(weights, bodyFat, measurements, fasting, workout, settings, goals, profile)

    override suspend fun updateWeights(transform: (List<WeightEntry>) -> List<WeightEntry>) {
        calls += "weights"; weights = transform(weights)
    }

    override suspend fun updateBodyFat(transform: (List<BodyFatEntry>) -> List<BodyFatEntry>) {
        calls += "bodyFat"; bodyFat = transform(bodyFat)
    }

    override suspend fun updateBodyMeasurements(transform: (List<BodyMeasurement>) -> List<BodyMeasurement>) {
        calls += "measurements"; measurements = transform(measurements)
    }

    override suspend fun updateFastingSessions(transform: (List<FastingSession>) -> List<FastingSession>) {
        calls += "fasting"; fasting = transform(fasting)
    }

    override suspend fun updateWorkoutState(transform: (WorkoutPersistedState) -> WorkoutPersistedState) {
        calls += "workouts"; workout = transform(workout).sanitized()
    }

    override suspend fun applySettings(settings: PortableSettings) {
        calls += "settings"
        this.settings = settings
        goals = PreferencesPortableStore.mergedGoals(goals, settings.optionalNutrientGoals)
    }

    override suspend fun saveProfile(profile: UserProfile) {
        calls += "profile"; this.profile = profile
    }

    override suspend fun syncProfileToLatestLogs(weights: Boolean, bodyFat: Boolean) {
        calls += "sync:" + listOfNotNull("weights".takeIf { weights }, "bodyFat".takeIf { bodyFat }).joinToString("+")
    }
}
