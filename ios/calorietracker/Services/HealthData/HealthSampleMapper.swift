import Foundation
import HealthKit

/// Pure `HKSample` → `HealthSampleRow` mapping. Runs inside the reader's
/// `autoreleasepool` on the sync task; no HealthKit object leaves this file.
nonisolated enum HealthSampleMapper {
    static let maxExtraJSONBytes = 4096
    static let workoutDistanceIdentifiers: [HKQuantityTypeIdentifier] = [
        .distanceWalkingRunning, .distanceCycling, .distanceSwimming, .distanceWheelchair, .distanceDownhillSnowSports,
    ]

    static func rows(from samples: [HKSample], type: HealthMetricType, calendar: Calendar, nowMs: Int64) -> [HealthSampleRow] {
        samples.compactMap { row(from: $0, type: type, calendar: calendar, nowMs: nowMs) }
    }

    static func deletedIDs(_ deleted: [HKDeletedObject]) -> [String] {
        deleted.map { $0.uuid.uuidString.lowercased() }
    }

    static func sources(from samples: [HKSample], nowMs: Int64) -> [HealthSourceRow] {
        var byID: [String: HealthSourceRow] = [:]
        for sample in samples {
            let source = sample.sourceRevision.source
            let id = source.bundleIdentifier
            var row = byID[id] ?? HealthSourceRow(id: id, name: source.name, deviceModel: nil, deviceType: nil, lastSeenMs: nil)
            if row.deviceModel == nil {
                row.deviceModel = sample.device?.model ?? sample.device?.name
            }
            let endMs = ms(sample.endDate)
            row.lastSeenMs = max(row.lastSeenMs ?? 0, endMs)
            byID[id] = row
        }
        return byID.values.sorted { $0.id < $1.id }
    }

    // MARK: - One sample

    static func row(from sample: HKSample, type: HealthMetricType, calendar: Calendar, nowMs: Int64) -> HealthSampleRow? {
        let startMs = ms(sample.startDate)
        let endMs = ms(sample.endDate)
        let zone = (sample.metadata?[HKMetadataKeyTimeZone] as? String).flatMap(TimeZone.init(identifier:)) ?? calendar.timeZone
        let startOffset = zone.secondsFromGMT(for: sample.startDate)
        let endOffset = zone.secondsFromGMT(for: sample.endDate)
        var row = HealthSampleRow(
            id: sample.uuid.uuidString.lowercased(),
            typeID: type.id,
            startMs: startMs,
            endMs: endMs,
            startOffsetS: startOffset,
            endOffsetS: endOffset,
            localDay: HealthRollupMath.localDay(
                startMs: startMs, endMs: endMs, startOffsetS: startOffset, endOffsetS: endOffset,
                attribution: type.dayAttribution, calendar: calendar
            ),
            unit: type.unit,
            sourceID: sample.sourceRevision.source.bundleIdentifier,
            device: sample.device?.name ?? sample.device?.model,
            updatedMs: nowMs
        )
        if let userEntered = sample.metadata?[HKMetadataKeyWasUserEntered] as? Bool, userEntered {
            row.recordingMethod = 3
        }
        row.clientRecordID = (sample.metadata?[HKMetadataKeySyncIdentifier] as? String)
            ?? (sample.metadata?[HKMetadataKeyExternalUUID] as? String)
        row.extraJSON = sanitizedMetadataJSON(sample.metadata)

        switch sample {
        case let quantity as HKQuantitySample:
            fill(&row, quantity: quantity, type: type)
        case let category as HKCategorySample:
            fill(&row, category: category, type: type)
        case let workout as HKWorkout:
            fill(&row, workout: workout)
        case let correlation as HKCorrelation:
            guard type.isBloodPressure else { return nil }
            fill(&row, bloodPressure: correlation)
        default:
            if #available(iOS 18, *), let mood = sample as? HKStateOfMind {
                fill(&row, stateOfMind: mood)
            } else {
                return nil
            }
        }
        return row
    }

    // MARK: Quantity

    private static func fill(_ row: inout HealthSampleRow, quantity sample: HKQuantitySample, type: HealthMetricType) {
        guard let unit = HealthKitUnits.unit(for: type.hkUnit), sample.quantity.is(compatibleWith: unit) else {
            row.valueText = sample.quantity.description
            return
        }
        let scale = type.isPercent ? 100.0 : 1.0
        if let discrete = sample as? HKDiscreteQuantitySample, discrete.count > 1 {
            row.value = discrete.averageQuantity.doubleValue(for: unit) * scale
            row.value2 = discrete.minimumQuantity.doubleValue(for: unit) * scale
            row.value3 = discrete.maximumQuantity.doubleValue(for: unit) * scale
            row.count = discrete.count
        } else if let cumulative = sample as? HKCumulativeQuantitySample {
            row.value = cumulative.sumQuantity.doubleValue(for: unit) * scale
            row.count = max(1, cumulative.count)
        } else {
            row.value = sample.quantity.doubleValue(for: unit) * scale
            row.count = max(1, sample.count)
        }
        if type.id == "blood_glucose", let meal = sample.metadata?[HKMetadataKeyBloodGlucoseMealTime] as? Int {
            row.categoryValue = meal
        }
    }

    // MARK: Category

    static func sleepStageCode(fromHKValue value: Int) -> Int {
        switch value {
        case HKCategoryValueSleepAnalysis.inBed.rawValue: return HealthSleepStage.inBed.rawValue
        case HKCategoryValueSleepAnalysis.awake.rawValue: return HealthSleepStage.awake.rawValue
        case 3: return HealthSleepStage.light.rawValue      // asleepCore
        case 4: return HealthSleepStage.deep.rawValue       // asleepDeep
        case 5: return HealthSleepStage.rem.rawValue        // asleepREM
        default: return HealthSleepStage.asleepUnspecified.rawValue // asleepUnspecified / legacy asleep (1)
        }
    }

    static func severityLabel(_ value: Int) -> String {
        switch value {
        case 1: return "Not Present"
        case 2: return "Mild"
        case 3: return "Moderate"
        case 4: return "Severe"
        default: return "Present"
        }
    }

    static func menstrualFlowLabel(_ value: Int) -> String {
        switch value {
        case 2: return "Light"
        case 3: return "Medium"
        case 4: return "Heavy"
        case 5: return "None"
        default: return "Unspecified"
        }
    }

    private static func fill(_ row: inout HealthSampleRow, category sample: HKCategorySample, type: HealthMetricType) {
        switch type.id {
        case "sleep":
            let code = sleepStageCode(fromHKValue: sample.value)
            row.categoryValue = code
            row.value = row.durationSeconds
            row.valueText = HealthSleepStage(rawValue: code)?.englishName
        case "stand_hours":
            row.categoryValue = sample.value
            row.value = sample.value == 0 ? 1 : 0 // 0 = stood, 1 = idle
        case "menstrual_flow":
            row.categoryValue = sample.value
            row.value = Double(sample.value)
            row.valueText = menstrualFlowLabel(sample.value)
        case "mindfulness_session":
            row.categoryValue = sample.value
            row.value = row.durationSeconds
        default:
            row.categoryValue = sample.value
            row.value = type.id.hasPrefix("symptom_") ? Double(sample.value) : 1
            if type.id.hasPrefix("symptom_") {
                row.valueText = severityLabel(sample.value)
            }
            if let threshold = sample.metadata?[HKMetadataKeyHeartRateEventThreshold] as? HKQuantity {
                row.title = threshold.description
            }
        }
    }

    // MARK: Workout

    private static func fill(_ row: inout HealthSampleRow, workout: HKWorkout) {
        row.value = workout.duration
        row.count = 1
        row.categoryValue = Int(workout.workoutActivityType.rawValue)
        row.title = ImportedHealthWorkoutFormatting.activityTitle(for: workout.workoutActivityType)

        var extra = row.extra
        extra["activity_type"] = Int(workout.workoutActivityType.rawValue)
        extra["activity_name"] = row.title
        if let energy = workout.statistics(for: HKQuantityType(.activeEnergyBurned))?.sumQuantity()?.doubleValue(for: .kilocalorie()), energy.isFinite {
            extra["energy_kcal"] = energy
        }
        for identifier in workoutDistanceIdentifiers {
            if let distance = workout.statistics(for: HKQuantityType(identifier))?.sumQuantity()?.doubleValue(for: .meter()), distance > 0 {
                extra["distance_m"] = distance
                break
            }
        }
        extra["segments"] = workout.workoutActivities.count
        extra["laps"] = workout.workoutEvents?.filter { $0.type == .lap }.count ?? 0
        extra["has_route"] = false
        row.extraJSON = json(extra)
    }

    // MARK: Blood pressure

    private static func fill(_ row: inout HealthSampleRow, bloodPressure correlation: HKCorrelation) {
        let systolicType = HKQuantityType(.bloodPressureSystolic)
        let diastolicType = HKQuantityType(.bloodPressureDiastolic)
        let unit = HKUnit.millimeterOfMercury()
        row.value = (correlation.objects(for: systolicType).first as? HKQuantitySample)?.quantity.doubleValue(for: unit)
        row.value2 = (correlation.objects(for: diastolicType).first as? HKQuantitySample)?.quantity.doubleValue(for: unit)
        row.count = 1
    }

    // MARK: State of mind

    @available(iOS 18, *)
    private static func fill(_ row: inout HealthSampleRow, stateOfMind: HKStateOfMind) {
        row.value = stateOfMind.valence
        row.categoryValue = stateOfMind.kind.rawValue
        row.count = 1
        var extra = row.extra
        extra["valence"] = stateOfMind.valence
        extra["kind"] = stateOfMind.kind.rawValue
        extra["labels"] = stateOfMind.labels.map(\.rawValue)
        extra["associations"] = stateOfMind.associations.map(\.rawValue)
        row.extraJSON = json(extra)
    }

    // MARK: - Metadata

    /// JSON-safe copy of the sample metadata. `ayuvo_*` keys are always kept (the
    /// `own_sum` rule matches on `ayuvo_workout_session_id`); the blob is capped at 4 KB.
    static func sanitizedMetadataJSON(_ metadata: [String: Any]?) -> String? {
        guard let metadata, !metadata.isEmpty else { return nil }
        var object: [String: Any] = [:]
        for (key, value) in metadata {
            switch value {
            case let string as String: object[key] = string
            case let number as NSNumber: object[key] = number
            case let date as Date: object[key] = ISO8601DateFormatter().string(from: date)
            case let quantity as HKQuantity: object[key] = quantity.description
            default: continue
            }
        }
        guard !object.isEmpty else { return nil }
        if let encoded = json(object), encoded.utf8.count <= maxExtraJSONBytes {
            return encoded
        }
        let trimmed = object.filter { $0.key.hasPrefix("ayuvo_") }
        return trimmed.isEmpty ? nil : json(trimmed)
    }

    static func json(_ object: [String: Any]) -> String? {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func ms(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1000).rounded())
    }
}
