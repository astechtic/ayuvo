import Foundation

/// Static registry table. Order = Apple Health category order, then the shared slug
/// order from `docs/health-data.md`. `Q`/`C` build raw HealthKit identifier strings.
extension HealthMetricRegistry {
    private static func Q(_ name: String) -> String { quantityPrefix + name }
    private static func C(_ name: String) -> String { categoryPrefix + name }

    private static func quantity(
        _ id: String, _ category: HealthCategory, _ identifiers: [String], _ unit: String,
        _ kind: HealthMetricKind, _ aggregation: HealthAggregation, _ name: String, exported: Bool = true,
        dayAttribution: HealthDayAttribution = .start, hkUnit: String? = nil
    ) -> HealthMetricType {
        HealthMetricType(
            id: id, category: category, kind: kind, aggregation: aggregation, unit: unit, hkUnit: hkUnit,
            dayAttribution: dayAttribution, hkIdentifiers: identifiers, objectKind: .quantity, exported: exported, englishName: name
        )
    }

    /// `identifier` nil = slug reserved in the contract without a verified HealthKit identifier.
    private static func category(
        _ id: String, _ category: HealthCategory, _ identifier: String?, _ name: String,
        aggregation: HealthAggregation = .count, unit: String = "count", dayAttribution: HealthDayAttribution = .start
    ) -> HealthMetricType {
        HealthMetricType(
            id: id, category: category, kind: .category, aggregation: aggregation, unit: unit,
            dayAttribution: dayAttribution, hkIdentifiers: identifier.map { [$0] } ?? [], objectKind: .category, englishName: name
        )
    }

    /// Phase 5 summary types (ECG, heartbeat series, audiogram, scored assessments): in the
    /// registry for parity, not yet read or synced (`typesVersion` 11 bump later).
    private static func reserved(
        _ id: String, _ category: HealthCategory, _ identifiers: [String], _ unit: String,
        _ kind: HealthMetricKind, _ aggregation: HealthAggregation, _ name: String
    ) -> HealthMetricType {
        HealthMetricType(
            id: id, category: category, kind: kind, aggregation: aggregation, unit: unit,
            hkIdentifiers: identifiers, objectKind: .virtual, englishName: name
        )
    }

    private static func androidOnly(
        _ id: String, _ category: HealthCategory, _ unit: String, _ kind: HealthMetricKind,
        _ aggregation: HealthAggregation, _ name: String, exported: Bool = true
    ) -> HealthMetricType {
        HealthMetricType(
            id: id, category: category, kind: kind, aggregation: aggregation, unit: unit,
            hkIdentifiers: [], objectKind: .virtual, exported: exported, englishName: name
        )
    }

    private static func dietary(_ suffix: String, _ hkName: String, _ unit: String, _ name: String) -> HealthMetricType {
        quantity("dietary_\(suffix)", .nutrition, [Q("Dietary\(hkName)")], unit, .cumulative, .sum, name, exported: false)
    }

    private static func symptom(_ slug: String, _ hkName: String, _ name: String) -> HealthMetricType {
        category("symptom_\(slug)", .symptoms, C(hkName), name)
    }

    static let entries: [HealthMetricType] = activity + body + heart + sleep + vitals + respiratory + nutrition
        + cycleTracking + mentalWellbeing + mobility + hearing + symptoms + other

    private static let activity: [HealthMetricType] = [
        quantity("steps", .activity, [Q("StepCount")], "count", .cumulative, .sum, "Steps"),
        quantity("distance", .activity, [Q("DistanceWalkingRunning")], "m", .cumulative, .sum, "Walking + Running Distance"),
        quantity("distance_cycling", .activity, [Q("DistanceCycling")], "m", .cumulative, .sum, "Cycling Distance"),
        quantity("distance_swimming", .activity, [Q("DistanceSwimming")], "m", .cumulative, .sum, "Swimming Distance"),
        quantity("distance_downhill_snow_sports", .activity, [Q("DistanceDownhillSnowSports")], "m", .cumulative, .sum, "Downhill Snow Sports Distance"),
        quantity("distance_rowing", .activity, [Q("DistanceRowing")], "m", .cumulative, .sum, "Rowing Distance"),
        quantity("distance_paddle_sports", .activity, [Q("DistancePaddleSports")], "m", .cumulative, .sum, "Paddle Sports Distance"),
        quantity("distance_cross_country_skiing", .activity, [Q("DistanceCrossCountrySkiing")], "m", .cumulative, .sum, "Cross-Country Skiing Distance"),
        quantity("distance_skating_sports", .activity, [Q("DistanceSkatingSports")], "m", .cumulative, .sum, "Skating Sports Distance"),
        quantity("swimming_stroke_count", .activity, [Q("SwimmingStrokeCount")], "count", .cumulative, .sum, "Swimming Strokes"),
        quantity("wheelchair_pushes", .activity, [Q("PushCount")], "count", .cumulative, .sum, "Pushes"),
        quantity("distance_wheelchair", .activity, [Q("DistanceWheelchair")], "m", .cumulative, .sum, "Wheelchair Distance"),
        quantity("floors_climbed", .activity, [Q("FlightsClimbed")], "count", .cumulative, .sum, "Flights Climbed"),
        androidOnly("elevation_gained", .activity, "m", .cumulative, .sum, "Elevation Gained"),
        quantity("active_energy", .activity, [Q("ActiveEnergyBurned")], "kcal", .cumulative, .sum, "Active Energy"),
        androidOnly("total_energy", .activity, "kcal", .cumulative, .sum, "Total Energy"),
        quantity("resting_energy", .activity, [Q("BasalEnergyBurned")], "kcal", .cumulative, .sum, "Resting Energy"),
        quantity("exercise_minutes", .activity, [Q("AppleExerciseTime")], "s", .duration, .duration, "Exercise Minutes"),
        quantity("stand_minutes", .activity, [Q("AppleStandTime")], "s", .duration, .duration, "Stand Minutes"),
        quantity("move_minutes", .activity, [Q("AppleMoveTime")], "s", .duration, .duration, "Move Minutes"),
        category("stand_hours", .activity, C("AppleStandHour"), "Stand Hours"),
        category("low_cardio_fitness_event", .activity, C("LowCardioFitnessEvent"), "Low Cardio Fitness Notifications"),
        HealthMetricType(
            id: "workout", category: .activity, kind: .session, aggregation: .duration, unit: "s",
            hkIdentifiers: [workoutIdentifier], objectKind: .workout, englishName: "Workouts"
        ),
        androidOnly("planned_workout", .activity, "s", .session, .count, "Planned Workouts"),
        androidOnly("activity_intensity", .activity, "s", .duration, .duration, "Activity Intensity"),
        quantity("speed", .activity, [Q("RunningSpeed"), Q("CyclingSpeed")], "m/s", .series, .average, "Speed"),
        quantity("power", .activity, [Q("CyclingPower"), Q("RunningPower")], "W", .series, .average, "Power"),
        quantity("cycling_cadence", .activity, [Q("CyclingCadence")], "count/min", .series, .average, "Cycling Cadence"),
        androidOnly("step_cadence", .activity, "count/min", .series, .average, "Step Cadence"),
        quantity("running_stride_length", .activity, [Q("RunningStrideLength")], "m", .discrete, .average, "Running Stride Length"),
        quantity("running_ground_contact_time", .activity, [Q("RunningGroundContactTime")], "ms", .discrete, .average, "Ground Contact Time"),
        quantity("running_vertical_oscillation", .activity, [Q("RunningVerticalOscillation")], "m", .discrete, .average, "Vertical Oscillation"),
        quantity("cycling_ftp", .activity, [Q("CyclingFunctionalThresholdPower")], "W", .discrete, .latest, "Cycling Functional Threshold Power"),
        quantity("physical_effort", .activity, [Q("PhysicalEffort")], "kcal/hr·kg", .discrete, .average, "Physical Effort"),
        HealthMetricType(
            id: "workout_effort_score", category: .activity, kind: .discrete, aggregation: .average, unit: "count", hkUnit: "appleEffortScore",
            hkIdentifiers: [Q("WorkoutEffortScore")], objectKind: .quantity, englishName: "Workout Effort"
        ),
        HealthMetricType(
            id: "estimated_workout_effort_score", category: .activity, kind: .discrete, aggregation: .average, unit: "count", hkUnit: "appleEffortScore",
            hkIdentifiers: [Q("EstimatedWorkoutEffortScore")], objectKind: .quantity, englishName: "Estimated Workout Effort"
        ),
    ]

    private static let body: [HealthMetricType] = [
        quantity("weight", .body, [Q("BodyMass")], "kg", .discrete, .latest, "Weight"),
        quantity("height", .body, [Q("Height")], "m", .discrete, .latest, "Height"),
        quantity("body_fat", .body, [Q("BodyFatPercentage")], "%", .discrete, .latest, "Body Fat Percentage"),
        quantity("lean_body_mass", .body, [Q("LeanBodyMass")], "kg", .discrete, .latest, "Lean Body Mass"),
        androidOnly("bone_mass", .body, "kg", .discrete, .latest, "Bone Mass"),
        androidOnly("body_water_mass", .body, "kg", .discrete, .latest, "Body Water Mass"),
        androidOnly("basal_metabolic_rate", .body, "kcal/d", .discrete, .latest, "Basal Metabolic Rate"),
        quantity("bmi", .body, [Q("BodyMassIndex")], "count", .discrete, .latest, "Body Mass Index"),
        quantity("waist_circumference", .body, [Q("WaistCircumference")], "m", .discrete, .latest, "Waist Circumference"),
    ]

    private static let heart: [HealthMetricType] = [
        quantity("heart_rate", .heart, [Q("HeartRate")], "count/min", .series, .minMax, "Heart Rate"),
        quantity("resting_heart_rate", .heart, [Q("RestingHeartRate")], "count/min", .discrete, .average, "Resting Heart Rate"),
        quantity("hrv_rmssd", .heart, [Q("HeartRateVariabilityRMSSD")], "ms", .discrete, .average, "Heart Rate Variability (RMSSD)"),
        quantity("hrv_sdnn", .heart, [Q("HeartRateVariabilitySDNN")], "ms", .discrete, .average, "Heart Rate Variability"),
        HealthMetricType(
            id: "blood_pressure", category: .heart, kind: .discrete, aggregation: .minMax, unit: "mmHg",
            hkIdentifiers: [bloodPressureCorrelationIdentifier], objectKind: .correlation, englishName: "Blood Pressure"
        ),
        quantity("vo2_max", .heart, [Q("VO2Max")], "mL/min·kg", .discrete, .latest, "Cardio Fitness"),
        quantity("walking_heart_rate_average", .heart, [Q("WalkingHeartRateAverage")], "count/min", .discrete, .average, "Walking Heart Rate Average"),
        quantity("heart_rate_recovery_one_minute", .heart, [Q("HeartRateRecoveryOneMinute")], "count/min", .discrete, .average, "Cardio Recovery"),
        quantity("afib_burden", .heart, [Q("AtrialFibrillationBurden")], "%", .discrete, .average, "AFib History"),
        category("high_heart_rate_event", .heart, C("HighHeartRateEvent"), "High Heart Rate Notifications"),
        category("low_heart_rate_event", .heart, C("LowHeartRateEvent"), "Low Heart Rate Notifications"),
        category("irregular_heart_rhythm_event", .heart, C("IrregularHeartRhythmEvent"), "Irregular Rhythm Notifications"),
        // Identifier unverified in the shared contract (iOS 26 hypertension notifications): reserved until confirmed.
        category("hypertension_event", .heart, nil, "Hypertension Notifications"),
    ]

    private static let sleep: [HealthMetricType] = [
        HealthMetricType(
            id: "sleep", category: .sleep, kind: .session, aggregation: .duration, unit: "s", dayAttribution: .end,
            hkIdentifiers: [C("SleepAnalysis")], objectKind: .category, englishName: "Sleep"
        ),
        quantity("sleeping_wrist_temperature", .sleep, [Q("AppleSleepingWristTemperature")], "degC", .discrete, .average, "Wrist Temperature", dayAttribution: .end),
        quantity("sleeping_breathing_disturbances", .sleep, [Q("AppleSleepingBreathingDisturbances")], "count", .discrete, .average, "Breathing Disturbances", dayAttribution: .end),
    ]

    private static let vitals: [HealthMetricType] = [
        quantity("blood_glucose", .vitals, [Q("BloodGlucose")], "mmol/L", .discrete, .average, "Blood Glucose"),
        quantity("body_temperature", .vitals, [Q("BodyTemperature")], "degC", .discrete, .average, "Body Temperature"),
        androidOnly("skin_temperature", .vitals, "degC", .series, .average, "Skin Temperature"),
        quantity("electrodermal_activity", .vitals, [Q("ElectrodermalActivity")], "mcS", .discrete, .average, "Electrodermal Activity"),
        quantity("peripheral_perfusion_index", .vitals, [Q("PeripheralPerfusionIndex")], "%", .discrete, .average, "Peripheral Perfusion Index"),
        quantity("insulin_delivery", .vitals, [Q("InsulinDelivery")], "IU", .cumulative, .sum, "Insulin Delivery"),
    ]

    private static let respiratory: [HealthMetricType] = [
        quantity("respiratory_rate", .respiratory, [Q("RespiratoryRate")], "count/min", .discrete, .average, "Respiratory Rate"),
        quantity("blood_oxygen", .respiratory, [Q("OxygenSaturation")], "%", .discrete, .average, "Blood Oxygen"),
        quantity("fev1", .respiratory, [Q("ForcedExpiratoryVolume1")], "L", .discrete, .average, "Forced Expiratory Volume, 1 sec"),
        quantity("fvc", .respiratory, [Q("ForcedVitalCapacity")], "L", .discrete, .average, "Forced Vital Capacity"),
        quantity("peak_expiratory_flow_rate", .respiratory, [Q("PeakExpiratoryFlowRate")], "L/min", .discrete, .average, "Peak Expiratory Flow Rate"),
        quantity("inhaler_usage", .respiratory, [Q("InhalerUsage")], "count", .cumulative, .sum, "Inhaler Usage"),
    ]

    private static let nutrition: [HealthMetricType] = [
        quantity("hydration", .nutrition, [Q("DietaryWater")], "mL", .cumulative, .sum, "Water"),
        androidOnly("nutrition", .nutrition, "kcal", .cumulative, .sum, "Nutrition", exported: false),
        dietary("energy", "EnergyConsumed", "kcal", "Dietary Energy"),
        dietary("protein", "Protein", "g", "Protein"),
        dietary("carbohydrates", "Carbohydrates", "g", "Carbohydrates"),
        dietary("fat_total", "FatTotal", "g", "Total Fat"),
        dietary("fat_saturated", "FatSaturated", "g", "Saturated Fat"),
        dietary("fat_monounsaturated", "FatMonounsaturated", "g", "Monounsaturated Fat"),
        dietary("fat_polyunsaturated", "FatPolyunsaturated", "g", "Polyunsaturated Fat"),
        dietary("cholesterol", "Cholesterol", "mg", "Dietary Cholesterol"),
        dietary("sodium", "Sodium", "mg", "Sodium"),
        dietary("potassium", "Potassium", "mg", "Potassium"),
        dietary("fiber", "Fiber", "g", "Fiber"),
        dietary("sugar", "Sugar", "g", "Dietary Sugar"),
        dietary("caffeine", "Caffeine", "mg", "Caffeine"),
        dietary("calcium", "Calcium", "mg", "Calcium"),
        dietary("iron", "Iron", "mg", "Iron"),
        dietary("magnesium", "Magnesium", "mg", "Magnesium"),
        dietary("zinc", "Zinc", "mg", "Zinc"),
        dietary("vitamin_a", "VitaminA", "mcg", "Vitamin A"),
        dietary("vitamin_c", "VitaminC", "mg", "Vitamin C"),
        dietary("vitamin_d", "VitaminD", "mcg", "Vitamin D"),
        dietary("vitamin_b12", "VitaminB12", "mcg", "Vitamin B12"),
        dietary("vitamin_e", "VitaminE", "mg", "Vitamin E"),
        dietary("vitamin_k", "VitaminK", "mcg", "Vitamin K"),
        dietary("folate", "Folate", "mcg", "Folate"),
        dietary("biotin", "Biotin", "mcg", "Biotin"),
        dietary("chloride", "Chloride", "mg", "Chloride"),
        dietary("chromium", "Chromium", "mcg", "Chromium"),
        dietary("copper", "Copper", "mg", "Copper"),
        dietary("iodine", "Iodine", "mcg", "Iodine"),
        dietary("manganese", "Manganese", "mg", "Manganese"),
        dietary("molybdenum", "Molybdenum", "mcg", "Molybdenum"),
        dietary("niacin", "Niacin", "mg", "Niacin"),
        dietary("pantothenic_acid", "PantothenicAcid", "mg", "Pantothenic Acid"),
        dietary("phosphorus", "Phosphorus", "mg", "Phosphorus"),
        dietary("riboflavin", "Riboflavin", "mg", "Riboflavin"),
        dietary("selenium", "Selenium", "mcg", "Selenium"),
        dietary("thiamin", "Thiamin", "mg", "Thiamin"),
        dietary("vitamin_b6", "VitaminB6", "mg", "Vitamin B6"),
    ]

    private static let cycleTracking: [HealthMetricType] = [
        category("menstrual_flow", .cycleTracking, C("MenstrualFlow"), "Menstruation"),
        HealthMetricType(
            id: "menstruation_period", category: .cycleTracking, kind: .duration, aggregation: .duration, unit: "days",
            dayAttribution: .span, hkIdentifiers: [], objectKind: .virtual, englishName: "Menstrual Period"
        ),
        category("intermenstrual_bleeding", .cycleTracking, C("IntermenstrualBleeding"), "Spotting"),
        category("ovulation_test", .cycleTracking, C("OvulationTestResult"), "Ovulation Test Result"),
        category("cervical_mucus", .cycleTracking, C("CervicalMucusQuality"), "Cervical Mucus Quality"),
        category("sexual_activity", .cycleTracking, C("SexualActivity"), "Sexual Activity"),
        quantity("basal_body_temperature", .cycleTracking, [Q("BasalBodyTemperature")], "degC", .discrete, .average, "Basal Body Temperature"),
        category("contraceptive", .cycleTracking, C("Contraceptive"), "Contraceptives", dayAttribution: .span),
        category("pregnancy", .cycleTracking, C("Pregnancy"), "Pregnancy", dayAttribution: .span),
        category("pregnancy_test_result", .cycleTracking, C("PregnancyTestResult"), "Pregnancy Test Result"),
        category("progesterone_test_result", .cycleTracking, C("ProgesteroneTestResult"), "Progesterone Test Result"),
        category("lactation", .cycleTracking, C("Lactation"), "Lactation", dayAttribution: .span),
        // The next two carry no verified HealthKit identifier in the shared contract yet.
        category("menopausal_state", .cycleTracking, nil, "Menopausal State", dayAttribution: .span),
        category("bleeding_after_menopause", .cycleTracking, nil, "Bleeding After Menopause"),
        category("infrequent_menstrual_cycles", .cycleTracking, C("InfrequentMenstrualCycles"), "Infrequent Periods", dayAttribution: .span),
        category("irregular_menstrual_cycles", .cycleTracking, C("IrregularMenstrualCycles"), "Irregular Cycles", dayAttribution: .span),
        category("persistent_intermenstrual_bleeding", .cycleTracking, C("PersistentIntermenstrualBleeding"), "Persistent Spotting", dayAttribution: .span),
        category("prolonged_menstrual_periods", .cycleTracking, C("ProlongedMenstrualPeriods"), "Prolonged Periods", dayAttribution: .span),
    ]

    private static let mentalWellbeing: [HealthMetricType] = [
        HealthMetricType(
            id: "mindfulness_session", category: .mentalWellbeing, kind: .duration, aggregation: .duration, unit: "s",
            hkIdentifiers: [C("MindfulSession")], objectKind: .category, englishName: "Mindful Minutes"
        ),
        HealthMetricType(
            id: "state_of_mind", category: .mentalWellbeing, kind: .category, aggregation: .count, unit: "none",
            hkIdentifiers: [stateOfMindIdentifier], objectKind: .stateOfMind, englishName: "State of Mind"
        ),
        reserved("assessment_gad7", .mentalWellbeing, ["HKScoredAssessmentTypeIdentifierGAD7"], "count", .discrete, .latest, "Anxiety Risk (GAD-7)"),
        reserved("assessment_phq9", .mentalWellbeing, ["HKScoredAssessmentTypeIdentifierPHQ9"], "count", .discrete, .latest, "Depression Risk (PHQ-9)"),
    ]

    private static let mobility: [HealthMetricType] = [
        quantity("walking_speed", .mobility, [Q("WalkingSpeed")], "m/s", .discrete, .average, "Walking Speed"),
        quantity("walking_step_length", .mobility, [Q("WalkingStepLength")], "m", .discrete, .average, "Walking Step Length"),
        quantity("walking_asymmetry", .mobility, [Q("WalkingAsymmetryPercentage")], "%", .discrete, .average, "Walking Asymmetry"),
        quantity("walking_double_support", .mobility, [Q("WalkingDoubleSupportPercentage")], "%", .discrete, .average, "Double Support Time"),
        quantity("six_minute_walk_distance", .mobility, [Q("SixMinuteWalkTestDistance")], "m", .discrete, .latest, "Six-Minute Walk"),
        quantity("stair_ascent_speed", .mobility, [Q("StairAscentSpeed")], "m/s", .discrete, .average, "Stair Speed: Up"),
        quantity("stair_descent_speed", .mobility, [Q("StairDescentSpeed")], "m/s", .discrete, .average, "Stair Speed: Down"),
        quantity("walking_steadiness", .mobility, [Q("AppleWalkingSteadiness")], "%", .discrete, .latest, "Walking Steadiness"),
        category("walking_steadiness_event", .mobility, C("AppleWalkingSteadinessEvent"), "Walking Steadiness Notifications"),
    ]

    private static let hearing: [HealthMetricType] = [
        quantity("environmental_audio_exposure", .hearing, [Q("EnvironmentalAudioExposure")], "dBASPL", .discrete, .average, "Environmental Sound Levels"),
        quantity("headphone_audio_exposure", .hearing, [Q("HeadphoneAudioExposure")], "dBASPL", .discrete, .average, "Headphone Audio Levels"),
        quantity("environmental_sound_reduction", .hearing, [Q("EnvironmentalSoundReduction")], "dBASPL", .discrete, .average, "Environmental Sound Reduction"),
        category("environmental_audio_exposure_event", .hearing, C("EnvironmentalAudioExposureEvent"), "Noise Notifications"),
        category("headphone_audio_exposure_event", .hearing, C("HeadphoneAudioExposureEvent"), "Headphone Notifications"),
    ]

    private static let symptoms: [HealthMetricType] = [
        symptom("abdominal_cramps", "AbdominalCramps", "Abdominal Cramps"),
        symptom("acne", "Acne", "Acne"),
        symptom("appetite_changes", "AppetiteChanges", "Appetite Changes"),
        symptom("bladder_incontinence", "BladderIncontinence", "Bladder Incontinence"),
        symptom("bloating", "Bloating", "Bloating"),
        symptom("breast_pain", "BreastPain", "Breast Pain"),
        symptom("chest_tightness_or_pain", "ChestTightnessOrPain", "Chest Tightness or Pain"),
        symptom("chills", "Chills", "Chills"),
        symptom("constipation", "Constipation", "Constipation"),
        symptom("coughing", "Coughing", "Coughing"),
        symptom("diarrhea", "Diarrhea", "Diarrhea"),
        symptom("dizziness", "Dizziness", "Dizziness"),
        symptom("dry_skin", "DrySkin", "Dry Skin"),
        symptom("fainting", "Fainting", "Fainting"),
        symptom("fatigue", "Fatigue", "Fatigue"),
        symptom("fever", "Fever", "Fever"),
        symptom("generalized_body_ache", "GeneralizedBodyAche", "Body and Muscle Ache"),
        symptom("hair_loss", "HairLoss", "Hair Loss"),
        symptom("headache", "Headache", "Headache"),
        symptom("heartburn", "Heartburn", "Heartburn"),
        symptom("hot_flashes", "HotFlashes", "Hot Flashes"),
        symptom("loss_of_smell", "LossOfSmell", "Loss of Smell"),
        symptom("loss_of_taste", "LossOfTaste", "Loss of Taste"),
        symptom("lower_back_pain", "LowerBackPain", "Lower Back Pain"),
        symptom("memory_lapse", "MemoryLapse", "Memory Lapse"),
        symptom("mood_changes", "MoodChanges", "Mood Changes"),
        symptom("nausea", "Nausea", "Nausea"),
        symptom("night_sweats", "NightSweats", "Night Sweats"),
        symptom("pelvic_pain", "PelvicPain", "Pelvic Pain"),
        symptom("rapid_pounding_or_fluttering_heartbeat", "RapidPoundingOrFlutteringHeartbeat", "Rapid, Pounding or Fluttering Heartbeat"),
        symptom("runny_nose", "RunnyNose", "Runny Nose"),
        symptom("shortness_of_breath", "ShortnessOfBreath", "Shortness of Breath"),
        symptom("sinus_congestion", "SinusCongestion", "Sinus Congestion"),
        symptom("skipped_heartbeat", "SkippedHeartbeat", "Skipped Heartbeat"),
        symptom("sleep_changes", "SleepChanges", "Sleep Changes"),
        symptom("sore_throat", "SoreThroat", "Sore Throat"),
        symptom("vaginal_dryness", "VaginalDryness", "Vaginal Dryness"),
        symptom("vomiting", "Vomiting", "Vomiting"),
        symptom("wheezing", "Wheezing", "Wheezing"),
    ]

    private static let other: [HealthMetricType] = [
        quantity("uv_exposure", .other, [Q("UVExposure")], "count", .discrete, .average, "UV Index"),
        quantity("time_in_daylight", .other, [Q("TimeInDaylight")], "s", .duration, .duration, "Time in Daylight"),
        quantity("number_of_times_fallen", .other, [Q("NumberOfTimesFallen")], "count", .cumulative, .sum, "Number of Times Fallen"),
        quantity("alcoholic_beverages", .other, [Q("NumberOfAlcoholicBeverages")], "count", .cumulative, .sum, "Alcohol Consumption"),
        quantity("blood_alcohol_content", .other, [Q("BloodAlcoholContent")], "%", .discrete, .average, "Blood Alcohol Content"),
        category("toothbrushing_event", .other, C("ToothbrushingEvent"), "Toothbrushing"),
        category("handwashing_event", .other, C("HandwashingEvent"), "Handwashing"),
        quantity("underwater_depth", .other, [Q("UnderwaterDepth")], "m", .discrete, .minMax, "Underwater Depth"),
        quantity("water_temperature", .other, [Q("WaterTemperature")], "degC", .discrete, .average, "Water Temperature"),
        quantity("nike_fuel", .other, [Q("NikeFuel")], "count", .cumulative, .sum, "NikeFuel"),
        HealthMetricType(
            id: "activity_summary", category: .other, kind: .discrete, aggregation: .latest, unit: "kcal",
            hkIdentifiers: [activitySummaryIdentifier], objectKind: .activitySummary, englishName: "Activity Rings"
        ),
        reserved("electrocardiogram", .other, ["HKDataTypeIdentifierElectrocardiogram"], "count/min", .discrete, .count, "Electrocardiograms (ECG)"),
        reserved("heartbeat_series", .other, ["HKDataTypeIdentifierHeartbeatSeries"], "count", .discrete, .count, "Beat-to-Beat Measurements"),
        reserved("audiogram", .other, ["HKDataTypeIdentifierAudiogram"], "count", .discrete, .count, "Audiograms"),
    ]
}
