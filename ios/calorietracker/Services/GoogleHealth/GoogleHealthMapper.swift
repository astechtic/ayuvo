import Foundation
import HealthKit

/// Pure Google Health API data point → `HealthSampleRow` mapping, driven by
/// `google_health_map.json` (`row_rules`, `time_kinds`, `converters`). Shared vectors:
/// `shared/health/test-vectors/google_health/mapping.json` (`GoogleHealthMapTests`).
nonisolated enum GoogleHealthMapper {
    static let idPrefix = "gh:"
    static let clientRecordPrefix = "ayuvo_gh_"
    static let sourcePrefix = "google_health:"
    /// `dataSource.platform` of points recorded on this OS (echo guard).
    static let runningPlatform = "IOS"

    // MARK: - Rows

    /// Rows for one data point: one row, or a session row plus stage rows for sleep.
    /// Returns [] for a point without a name, union body, registry type or readable time.
    static func rows(point: RJ, type: GoogleHealthMap.DataType, calendar: Calendar, nowMs: Int64) -> [HealthSampleRow] {
        guard let pointID = pointID(point), let registry = HealthMetricRegistry.type(id: type.typeID) else { return [] }
        let body = point[type.union]
        guard body.object != nil, let span = timeSpan(body, kind: type.time, calendar: calendar) else { return [] }
        let dataSource = point["dataSource"]

        var row = HealthSampleRow(
            id: idPrefix + pointID,
            typeID: type.typeID,
            startMs: span.startMs,
            endMs: span.endMs,
            startOffsetS: span.startOffsetS,
            endOffsetS: span.endOffsetS,
            localDay: HealthRollupMath.localDay(
                startMs: span.startMs, endMs: span.endMs, startOffsetS: span.startOffsetS, endOffsetS: span.endOffsetS,
                attribution: registry.dayAttribution, calendar: calendar
            ),
            unit: registry.unit,
            sourceID: sourceID(dataSource),
            device: device(dataSource),
            updatedMs: nowMs
        )
        row.recordingMethod = recordingMethod(dataSource)
        row.clientRecordID = clientRecordPrefix + pointID
        row.origin = HealthRowOrigin.googleHealth.rawValue
        let baseExtra: [String: RJ] = ["gh": .obj(["type": .str(type.ghType), "platform": .string(dataSource["platform"].string)])]

        switch type.converter {
        case "sleep":
            return sleepRows(session: row, body: body, type: type, span: span, registry: registry, extra: baseExtra, calendar: calendar)
        case "exercise":
            fillExercise(&row, body: body, extra: baseExtra)
        case "nutrition_log":
            fillNutrition(&row, body: body, type: type, extra: baseExtra)
        default:
            row.value = type.value.flatMap { value(body, $0) }
            row.value2 = type.value2.flatMap { value(body, $0) }
            row.value3 = type.value3.flatMap { value(body, $0) }
            if let category = type.category {
                let raw = category.path.flatMap { lookup(body, $0).string }
                row.categoryValue = raw.flatMap { category.map[$0] } ?? category.defaultValue
            }
            var extra = baseExtra
            for (key, path) in type.extra ?? [:] {
                let field = lookup(body, path)
                if !field.isNull { extra[key] = field }
            }
            row.extraJSON = RJ.obj(extra).jsonText
        }
        return [row]
    }

    /// Last path segment of `point.name` (`users/me/dataTypes/steps/dataPoints/<id>`).
    static func pointID(_ point: RJ) -> String? {
        guard let name = point["name"].string, let last = name.split(separator: "/").last, !last.isEmpty else { return nil }
        return String(last)
    }

    static func sourceID(_ dataSource: RJ) -> String {
        sourcePrefix + (dataSource["application"]["packageName"].string ?? dataSource["dataSourceFamily"].string ?? "unknown")
    }

    static func device(_ dataSource: RJ) -> String? {
        let parts = [dataSource["device"]["manufacturer"].string, dataSource["device"]["model"].string]
            .compactMap { $0?.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        return parts.isEmpty ? nil : parts.joined(separator: " ")
    }

    static func recordingMethod(_ dataSource: RJ) -> Int {
        switch dataSource["recordingMethod"].string {
        case "ACTIVELY_RECORDED": return 1
        case "PASSIVELY_RECORDED": return 2
        default: return 0
        }
    }

    /// `health_sources` row for Browse › Sources.
    static func source(_ point: RJ, endMs: Int64) -> HealthSourceRow {
        let dataSource = point["dataSource"]
        let id = sourceID(dataSource)
        let name = dataSource["name"].string ?? device(dataSource) ?? "Google Health"
        return HealthSourceRow(id: id, name: name, deviceModel: dataSource["device"]["model"].string, deviceType: nil, lastSeenMs: endMs)
    }

    // MARK: - Echo guard (source half; the duplicate-window half needs the database)

    /// True when the point came from Health Connect / Apple Health or from this OS, so writing
    /// it back would echo it (`echo_guard`).
    static func isEcho(dataSource: RJ, guard echo: GoogleHealthMap.EchoGuard, runningPlatform: String = runningPlatform) -> Bool {
        if let package = dataSource["application"]["packageName"].string, echo.skipMirrorWhenPackageIn.contains(package) {
            return true
        }
        if echo.skipMirrorWhenPlatformAndSameOS, dataSource["platform"].string?.uppercased() == runningPlatform {
            return true
        }
        return false
    }

    // MARK: - Time

    struct TimeSpan: Equatable {
        var startMs: Int64
        var endMs: Int64
        var startOffsetS: Int?
        var endOffsetS: Int?
    }

    static func timeSpan(_ body: RJ, kind: GoogleHealthMap.TimeKind, calendar: Calendar) -> TimeSpan? {
        switch kind {
        case .sample:
            let sample = body["sampleTime"]
            guard let time = sample["physicalTime"].string.flatMap(ISO8601Fast.parse) else { return nil }
            let offset = durationSeconds(sample["utcOffset"]).map { Int($0) }
            return TimeSpan(startMs: time.ms, endMs: time.ms, startOffsetS: offset, endOffsetS: offset)
        case .interval:
            let interval = body["interval"]
            guard let start = interval["startTime"].string.flatMap(ISO8601Fast.parse),
                  let end = interval["endTime"].string.flatMap(ISO8601Fast.parse) else { return nil }
            return TimeSpan(
                startMs: start.ms, endMs: end.ms,
                startOffsetS: durationSeconds(interval["startUtcOffset"]).map { Int($0) },
                endOffsetS: durationSeconds(interval["endUtcOffset"]).map { Int($0) }
            )
        case .date:
            let date = body["date"]
            guard let year = numeric(date["year"]), let month = numeric(date["month"]), let day = numeric(date["day"]),
                  let start = calendar.date(from: DateComponents(year: Int(year), month: Int(month), day: Int(day))),
                  let next = calendar.date(byAdding: .day, value: 1, to: start) else { return nil }
            return TimeSpan(startMs: HealthSampleMapper.ms(start), endMs: HealthSampleMapper.ms(next) - 1, startOffsetS: nil, endOffsetS: nil)
        case .session:
            let interval = body["interval"]
            guard let start = (body["sessionStartTime"].string ?? interval["startTime"].string).flatMap(ISO8601Fast.parse),
                  let end = (body["sessionEndTime"].string ?? interval["endTime"].string).flatMap(ISO8601Fast.parse) else { return nil }
            return TimeSpan(
                startMs: start.ms, endMs: end.ms,
                startOffsetS: durationSeconds(interval["startUtcOffset"]).map { Int($0) },
                endOffsetS: durationSeconds(interval["endUtcOffset"]).map { Int($0) }
            )
        }
    }

    // MARK: - Values

    /// A number, or an int64 the API encodes as a JSON string (`"count": "412"`).
    static func numeric(_ field: RJ) -> Double? {
        if let number = field.double { return number }
        if let text = field.string { return Double(text) }
        return nil
    }

    /// protobuf `Duration` JSON (`"123.5s"`) → seconds.
    static func durationSeconds(_ field: RJ) -> Double? {
        guard let text = field.string else { return numeric(field) }
        return Double(text.hasSuffix("s") ? String(text.dropLast()) : text)
    }

    static func lookup(_ body: RJ, _ path: String) -> RJ {
        path.split(separator: ".").reduce(body) { $0[String($1)] }
    }

    static func value(_ body: RJ, _ spec: GoogleHealthMap.ValueSpec) -> Double? {
        let field = lookup(body, spec.path)
        let raw: Double?
        switch spec.converter {
        case "duration_seconds": raw = durationSeconds(field)
        case "weight_quantity_kg": raw = weightKg(field)
        case "length_quantity_m": raw = lengthM(field)
        case "volume_quantity_ml": raw = volumeML(field)
        default: raw = numeric(field)
        }
        guard let raw, raw.isFinite else { return nil }
        return raw * (spec.scale ?? 1)
    }

    static func weightKg(_ quantity: RJ) -> Double? {
        guard let value = numeric(quantity["value"]) else { return nil }
        switch quantity["unit"].string {
        case "POUND": return value * 0.45359237
        default: return value
        }
    }

    static func lengthM(_ quantity: RJ) -> Double? {
        guard let value = numeric(quantity["value"]) else { return nil }
        switch quantity["unit"].string {
        case "CENTIMETER": return value / 100
        case "INCH": return value * 0.0254
        case "FOOT": return value * 0.3048
        default: return value
        }
    }

    static func volumeML(_ quantity: RJ) -> Double? {
        guard let value = numeric(quantity["value"]) else { return nil }
        switch quantity["unit"].string {
        case "LITER": return value * 1000
        case "US_FLUID_OUNCE": return value * 29.5735295625
        case "US_CUP": return value * 236.5882365
        default: return value
        }
    }

    static func energyKcal(_ quantity: RJ) -> Double? {
        guard let value = numeric(quantity["value"]) else { return nil }
        return quantity["unit"].string == "KILOJOULE" ? value / 4.184 : value
    }

    // MARK: - Sleep (Android session + stage layout, docs/health-data.md §5)

    private static func sleepRows(
        session: HealthSampleRow,
        body: RJ,
        type: GoogleHealthMap.DataType,
        span: TimeSpan,
        registry: HealthMetricType,
        extra: [String: RJ],
        calendar: Calendar
    ) -> [HealthSampleRow] {
        var sessionRow = session
        sessionRow.categoryValue = 0
        sessionRow.value = sessionRow.durationSeconds
        sessionRow.title = body["sleepType"].string
        sessionRow.extraJSON = RJ.obj(extra).jsonText

        var segments: [(start: Int64, end: Int64, code: Int)] = []
        let stageMap = type.stageMap ?? [:]
        for stage in body["sleepStages"].array ?? [] {
            guard let start = stage["startTime"].string.flatMap(ISO8601Fast.parse),
                  let end = stage["endTime"].string.flatMap(ISO8601Fast.parse) else { continue }
            let code = stage["stage"].string.flatMap { stageMap[$0] } ?? HealthSleepStage.asleepUnspecified.rawValue
            segments.append((start.ms, end.ms, code))
        }
        if segments.isEmpty {
            segments.append((span.startMs, span.endMs, HealthSleepStage.asleepUnspecified.rawValue))
        }
        for segment in body["outOfBedSegments"].array ?? [] {
            guard let start = segment["startTime"].string.flatMap(ISO8601Fast.parse),
                  let end = segment["endTime"].string.flatMap(ISO8601Fast.parse) else { continue }
            segments.append((start.ms, end.ms, HealthSleepStage.outOfBed.rawValue))
        }

        var stageExtra = extra
        stageExtra["session_id"] = .str(session.id)
        let stageExtraJSON = RJ.obj(stageExtra).jsonText
        var rows = [sessionRow]
        for (index, segment) in segments.enumerated() {
            var stage = session
            stage.id = "\(session.id):\(index)"
            stage.startMs = segment.start
            stage.endMs = segment.end
            stage.localDay = HealthRollupMath.localDay(
                startMs: segment.start, endMs: segment.end, startOffsetS: span.startOffsetS, endOffsetS: span.endOffsetS,
                attribution: registry.dayAttribution, calendar: calendar
            )
            stage.categoryValue = segment.code
            stage.value = stage.durationSeconds
            stage.title = nil
            stage.clientRecordID = nil
            stage.extraJSON = stageExtraJSON
            rows.append(stage)
        }
        return rows
    }

    // MARK: - Exercise

    private static func fillExercise(_ row: inout HealthSampleRow, body: RJ, extra base: [String: RJ]) {
        let exerciseType = body["exerciseType"].string
        let metadata = body["exerciseMetadata"]
        row.value = row.durationSeconds
        row.title = exerciseType
        row.categoryValue = 0
        var extra = base
        extra["activity_type"] = .string(exerciseType)
        extra["energy_kcal"] = .number(numeric(metadata["totalCaloriesKcal"]))
        extra["distance_m"] = .number(numeric(metadata["totalDistanceMeters"]))
        extra["avg_hr_bpm"] = .number(numeric(metadata["averageHeartRateBeatsPerMinute"]))
        extra["max_hr_bpm"] = .number(numeric(metadata["maxHeartRateBeatsPerMinute"]))
        extra["elevation_gain_m"] = .number(numeric(metadata["elevationGainMeters"]))
        extra["active_time_s"] = .number(durationSeconds(body["activeDuration"]) ?? durationSeconds(metadata["activeDuration"]))
        extra["segments"] = .null
        extra["laps"] = .int(body["splitSummaries"].array?.count ?? 0)
        extra["has_route"] = .bool(false)
        extra["platform"] = .str("google_health")
        row.extraJSON = RJ.obj(extra).jsonText
    }

    // MARK: - Nutrition log

    /// Google nutrient names → `dietary_*` slug (registry v1). Aliases cover the API's
    /// "total"/plural spellings.
    static let nutrientSlugs: [String: String] = {
        var map: [String: String] = [:]
        for type in HealthMetricRegistry.all where type.id.hasPrefix("dietary_") {
            map[type.id.dropFirst("dietary_".count).uppercased()] = type.id
        }
        let aliases: [String: String] = [
            "ENERGY": "dietary_energy", "CALORIES": "dietary_energy",
            "CARBOHYDRATE": "dietary_carbohydrates", "TOTAL_CARBOHYDRATE": "dietary_carbohydrates", "TOTAL_CARBOHYDRATES": "dietary_carbohydrates",
            "FAT": "dietary_fat_total", "TOTAL_FAT": "dietary_fat_total",
            "SATURATED_FAT": "dietary_fat_saturated", "MONOUNSATURATED_FAT": "dietary_fat_monounsaturated",
            "POLYUNSATURATED_FAT": "dietary_fat_polyunsaturated", "DIETARY_FIBER": "dietary_fiber",
            "SUGARS": "dietary_sugar", "TOTAL_SUGARS": "dietary_sugar", "FOLIC_ACID": "dietary_folate",
            "PANTOTHENIC_ACID": "dietary_pantothenic_acid", "VITAMIN_B1": "dietary_thiamin", "VITAMIN_B2": "dietary_riboflavin",
            "VITAMIN_B3": "dietary_niacin", "VITAMIN_B5": "dietary_pantothenic_acid", "VITAMIN_B7": "dietary_biotin",
        ]
        map.merge(aliases) { current, _ in current }
        return map
    }()

    /// Amount in the slug's canonical unit (g / mg / mcg / kcal).
    static func nutrientAmount(_ quantity: RJ, slugUnit: String) -> Double? {
        guard let value = numeric(quantity["value"]) else { return nil }
        let grams: Double
        switch quantity["unit"].string {
        case "KILOCALORIE", "CALORIE": return value
        case "KILOJOULE": return value / 4.184
        case "MILLIGRAM": grams = value / 1000
        case "MICROGRAM": grams = value / 1_000_000
        case "GRAM": grams = value
        default:
            return value
        }
        switch slugUnit {
        case "mg": return grams * 1000
        case "mcg": return grams * 1_000_000
        default: return grams
        }
    }

    private static func fillNutrition(_ row: inout HealthSampleRow, body: RJ, type: GoogleHealthMap.DataType, extra base: [String: RJ]) {
        row.value = energyKcal(body["totalEnergyQuantity"])
        row.categoryValue = body["mealType"].string.flatMap { type.mealMap?[$0] } ?? 0
        let foods = body["foodConsumed"]
        let firstFood = foods.array?.first ?? foods
        row.title = firstFood["name"].string ?? firstFood["displayName"].string

        var extra = base
        for nutrient in body["nutrients"].array ?? [] {
            let name = (nutrient["nutrient"].string ?? nutrient["type"].string ?? nutrient["name"].string)?
                .uppercased().replacingOccurrences(of: " ", with: "_")
            guard let name, let slug = nutrientSlugs[name], let unit = HealthMetricRegistry.type(id: slug)?.unit else { continue }
            let quantity = nutrient["quantity"].isNull ? nutrient["amount"] : nutrient["quantity"]
            guard let amount = nutrientAmount(quantity, slugUnit: unit) else { continue }
            extra[slug] = .number(amount)
        }
        row.value2 = extra["dietary_protein"]?.double
        row.value3 = extra["dietary_carbohydrates"]?.double
        if row.value == nil { row.value = extra["dietary_energy"]?.double }
        row.extraJSON = RJ.obj(extra).jsonText
    }
}

/// Google `exerciseType` names → `HKWorkoutActivityType`, by name (docs/google-health.md §4).
nonisolated enum GoogleHealthWorkoutTypes {
    static func activityType(forExerciseType name: String?) -> HKWorkoutActivityType? {
        guard let name else { return nil }
        let key = name.uppercased().replacingOccurrences(of: " ", with: "_").replacingOccurrences(of: "-", with: "_")
        return table[key]
    }

    private static let table: [String: HKWorkoutActivityType] = [
        "RUNNING": .running, "RUN": .running, "TREADMILL": .running, "TREADMILL_RUNNING": .running, "TRAIL_RUNNING": .running,
        "WALKING": .walking, "WALK": .walking, "TREADMILL_WALKING": .walking,
        "HIKING": .hiking,
        "BIKING": .cycling, "CYCLING": .cycling, "OUTDOOR_BIKE": .cycling, "INDOOR_BIKE": .cycling, "SPINNING": .cycling,
        "MOUNTAIN_BIKING": .cycling, "STATIONARY_BIKING": .cycling,
        "SWIMMING": .swimming, "SWIM": .swimming, "POOL_SWIMMING": .swimming, "OPEN_WATER_SWIMMING": .swimming,
        "ELLIPTICAL": .elliptical,
        "ROWING": .rowing, "ROWING_MACHINE": .rowing,
        "YOGA": .yoga,
        "PILATES": .pilates,
        "WEIGHTLIFTING": .traditionalStrengthTraining, "WEIGHTS": .traditionalStrengthTraining,
        "STRENGTH_TRAINING": .traditionalStrengthTraining, "WEIGHT_TRAINING": .traditionalStrengthTraining,
        "CIRCUIT_TRAINING": .functionalStrengthTraining, "CROSSFIT": .crossTraining, "CROSS_TRAINING": .crossTraining,
        "HIIT": .highIntensityIntervalTraining, "HIGH_INTENSITY_INTERVAL_TRAINING": .highIntensityIntervalTraining, "INTERVAL_WORKOUT": .highIntensityIntervalTraining,
        "STAIR_CLIMBING": .stairClimbing, "STAIRS": .stairs, "STAIR_CLIMBING_MACHINE": .stairClimbing,
        "DANCING": .socialDance, "DANCE": .socialDance, "AEROBICS": .cardioDance,
        "MARTIAL_ARTS": .martialArts, "BOXING": .boxing, "KICKBOXING": .kickboxing,
        "TENNIS": .tennis, "TABLE_TENNIS": .tableTennis, "BADMINTON": .badminton, "SQUASH": .squash, "RACQUETBALL": .racquetball,
        "BASKETBALL": .basketball, "SOCCER": .soccer, "FOOTBALL": .americanFootball, "AMERICAN_FOOTBALL": .americanFootball,
        "BASEBALL": .baseball, "SOFTBALL": .softball, "VOLLEYBALL": .volleyball, "CRICKET": .cricket, "RUGBY": .rugby,
        "HOCKEY": .hockey, "ICE_HOCKEY": .hockey, "GOLF": .golf, "HANDBALL": .handball,
        "SKIING": .downhillSkiing, "DOWNHILL_SKIING": .downhillSkiing, "CROSS_COUNTRY_SKIING": .crossCountrySkiing,
        "SNOWBOARDING": .snowboarding, "SKATING": .skatingSports, "ICE_SKATING": .skatingSports,
        "SURFING": .surfingSports, "PADDLING": .paddleSports, "KAYAKING": .paddleSports, "SAILING": .sailing,
        "CLIMBING": .climbing, "ROCK_CLIMBING": .climbing, "BOULDERING": .climbing,
        "MEDITATION": .mindAndBody, "BREATHING": .mindAndBody, "STRETCHING": .flexibility,
        "CORE_TRAINING": .coreTraining, "FUNCTIONAL_TRAINING": .functionalStrengthTraining,
        "WHEELCHAIR": .wheelchairWalkPace, "OTHER": .other, "WORKOUT": .other, "SPORT": .other,
    ]
}
