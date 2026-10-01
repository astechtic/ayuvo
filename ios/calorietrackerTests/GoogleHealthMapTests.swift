import Foundation
import HealthKit
import Testing
@testable import calorietracker

/// `shared/health/google_health_map.json` + its vectors (docs/google-health.md). Android runs
/// the same vectors in `GoogleHealthMapContractTest.kt`.
struct GoogleHealthMapTests {
    private typealias F = HealthTestFixtures

    static var sharedMapURL: URL { F.repoRootURL.appendingPathComponent("shared/health/google_health_map.json") }
    static var vectorsURL: URL { F.repoRootURL.appendingPathComponent("shared/health/test-vectors/google_health/mapping.json") }

    static func sharedMap() throws -> GoogleHealthMap {
        try GoogleHealthMap.load(from: Data(contentsOf: sharedMapURL))
    }

    static func vectors() throws -> RJ {
        let object = try JSONSerialization.jsonObject(with: Data(contentsOf: vectorsURL))
        return RJ.from(object)
    }

    static func calendar(_ zone: String) -> Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: zone)!
        return calendar
    }

    // MARK: - Contract

    @Test func bundledCopyIsByteIdenticalToTheSharedFile() throws {
        let bundled = try #require(Bundle.main.url(forResource: GoogleHealthMap.resourceName, withExtension: "json"))
        #expect(try Data(contentsOf: bundled) == Data(contentsOf: Self.sharedMapURL))
        #expect(GoogleHealthMap.bundled != nil)
    }

    @Test func everyTypeResolvesToARegistrySlugAndAWritableHealthKitTarget() throws {
        let map = try Self.sharedMap()
        #expect(map.mapVersion == 1)
        #expect(Set(map.types.map(\.ghType)).count == map.types.count)
        let groups = Set(map.scopeGroups.map(\.id))
        for type in map.types {
            #expect(HealthMetricRegistry.type(id: type.typeID) != nil, "\(type.ghType) → unknown slug \(type.typeID)")
            #expect(groups.contains(type.scopeGroup), "\(type.ghType) scope group")
            guard let hk = type.hk else { continue }
            switch hk.identifier {
            case HealthMetricRegistry.workoutIdentifier, HKCorrelationTypeIdentifier.food.rawValue:
                continue
            default:
                #expect(HealthMetricRegistry.objectType(forRawIdentifier: hk.identifier) != nil, "\(type.ghType) hk \(hk.identifier)")
            }
            if let unit = hk.unit, let quantity = HealthMetricRegistry.objectType(forRawIdentifier: hk.identifier) as? HKQuantityType {
                let hkUnit = HealthKitUnits.unit(for: unit)
                #expect(hkUnit != nil, "\(type.ghType) unit \(unit)")
                if let hkUnit { #expect(quantity.is(compatibleWith: hkUnit), "\(type.ghType) \(unit)") }
            }
        }
        #expect(!GoogleHealthMirrorWriter.shareTypes(map: map).isEmpty)
    }

    @Test func googleOnlySlugsAreNeverReadFromHealthKit() {
        let slugs = ["active_zone_minutes", "activity_level", "sedentary_period", "calories_in_hr_zone", "swim_lengths",
                     "daily_hrv", "daily_blood_oxygen", "nightly_temperature_deviation", "ecg_recording"]
        let syncable = Set(HealthMetricRegistry.syncableTypes().map(\.id))
        for slug in slugs {
            let type = HealthMetricRegistry.type(id: slug)
            #expect(type != nil, Comment(rawValue: slug))
            #expect(type?.hkIdentifiers.isEmpty == true, Comment(rawValue: slug))
            #expect(!syncable.contains(slug), Comment(rawValue: slug))
        }
        #expect(HealthSchema.registryVersion == 2)
        #expect(HealthSchema.schemaVersion == 3)
    }

    @Test func accountMetadataStaysOutOfTheCloudBackup() {
        for key in GoogleHealthSettings.allKeys {
            #expect(!CloudBackupPolicy.include(key), Comment(rawValue: key))
        }
    }

    // MARK: - Vectors

    @Test func mapperReproducesTheSharedVectors() throws {
        let map = try Self.sharedMap()
        let vectors = try Self.vectors()
        let defaultZone = vectors["zone"].string ?? "UTC"
        let cases = vectors["mapping"].array ?? []
        #expect(!cases.isEmpty)
        for vector in cases {
            let name = vector["name"].string ?? "?"
            let type = try #require(map.type(vector["gh_type"].string ?? ""), Comment(rawValue: name))
            let calendar = Self.calendar(vector["zone"].string ?? defaultZone)
            let rows = GoogleHealthMapper.rows(point: vector["point"], type: type, calendar: calendar, nowMs: 1)
            let expected = vector["expected_rows"].array ?? []
            #expect(rows.count == expected.count, "\(name): \(rows.count) rows")
            for (row, want) in zip(rows, expected) {
                Self.expectRow(row, matches: want, name: name)
            }
        }
    }

    static func expectRow(_ row: HealthSampleRow, matches want: RJ, name: String) {
        let context = "\(name) \(row.id)"
        #expect(row.id == want["id"].string, "\(context) id")
        #expect(row.typeID == want["type_id"].string, "\(context) type_id")
        #expect(Double(row.startMs) == want["start_ms"].double, "\(context) start_ms")
        #expect(Double(row.endMs) == want["end_ms"].double, "\(context) end_ms")
        #expect(row.startOffsetS.map(Double.init) == want["start_offset_s"].double, "\(context) start_offset_s")
        #expect(row.endOffsetS.map(Double.init) == want["end_offset_s"].double, "\(context) end_offset_s")
        #expect(row.localDay == want["local_day"].string, "\(context) local_day")
        for (key, value) in [("value", row.value), ("value2", row.value2), ("value3", row.value3)] {
            let expected = want[key].double
            if let expected, let value {
                #expect(abs(value - expected) < 1e-6, "\(context) \(key) \(value) != \(expected)")
            } else {
                #expect(value == nil && expected == nil, "\(context) \(key)")
            }
        }
        #expect(row.valueText == want["value_text"].string, "\(context) value_text")
        #expect(row.unit == want["unit"].string, "\(context) unit")
        #expect(row.categoryValue.map(Double.init) == want["category_value"].double, "\(context) category_value")
        #expect(row.title == want["title"].string, "\(context) title")
        let extra = row.extraJSON.flatMap(RJ.parse) ?? .null
        #expect(RJ.same(extra, want["extra_json"]), "\(context) extra_json \(extra.jsonText) != \(want["extra_json"].jsonText)")
        #expect(Double(row.count) == want["count"].double, "\(context) count")
        #expect(row.sourceID == want["source_id"].string, "\(context) source_id")
        #expect(row.device == want["device"].string, "\(context) device")
        #expect(row.recordingMethod.map(Double.init) == want["recording_method"].double, "\(context) recording_method")
        #expect(row.clientRecordID == want["client_record_id"].string, "\(context) client_record_id")
        #expect(Double(row.origin) == want["origin"].double, "\(context) origin")
        #expect(Double(row.deleted) == want["deleted"].double, "\(context) deleted")
    }

    @Test func filterStringsMatchTheSharedVectors() throws {
        let map = try Self.sharedMap()
        let cases = try Self.vectors()["filters"].array ?? []
        #expect(!cases.isEmpty)
        for vector in cases {
            let type = try #require(map.type(vector["gh_type"].string ?? ""))
            let zone = try #require(TimeZone(identifier: vector["zone"].string ?? "UTC"))
            let fromMs = Int64(vector["from_ms"].double ?? 0)
            #expect(map.filter(for: type, fromMs: fromMs, timeZone: zone) == vector["expected"].string)
        }
    }

    @Test func echoGuardMatchesTheSharedVectors() throws {
        let map = try Self.sharedMap()
        let cases = try Self.vectors()["echo_guard"].array ?? []
        #expect(!cases.isEmpty)
        for vector in cases {
            let platform = vector["platform"].string == "android" ? "ANDROID" : "IOS"
            let echo = GoogleHealthMapper.isEcho(dataSource: vector["dataSource"], guard: map.echoGuard, runningPlatform: platform)
            #expect(echo == !(vector["mirror"].bool ?? true), Comment(rawValue: vector["name"].string ?? "?"))
        }
    }

    // MARK: - Converters

    @Test func quantityConvertersFollowTheContract() {
        #expect(GoogleHealthMapper.durationSeconds(.str("123.5s")) == 123.5)
        #expect(GoogleHealthMapper.weightKg(RJ.from(["value": 2, "unit": "KILOGRAM"])) == 2)
        #expect(abs(GoogleHealthMapper.lengthM(RJ.from(["value": 70, "unit": "INCH"]))! - 1.778) < 1e-9)
        #expect(GoogleHealthMapper.lengthM(RJ.from(["value": 180, "unit": "CENTIMETER"])) == 1.8)
        #expect(GoogleHealthMapper.volumeML(RJ.from(["value": 1.5, "unit": "LITER"])) == 1500)
        #expect(abs(GoogleHealthMapper.volumeML(RJ.from(["value": 1, "unit": "US_FLUID_OUNCE"]))! - 29.5735295625) < 1e-9)
        #expect(abs(GoogleHealthMapper.energyKcal(RJ.from(["value": 418.4, "unit": "KILOJOULE"]))! - 100) < 1e-9)
    }

    @Test func sleepWithoutStagesGetsOneAsleepRowAndOutOfBedSegments() throws {
        let map = try Self.sharedMap()
        let type = try #require(map.type("sleep"))
        let point = RJ.from([
            "name": "users/me/dataTypes/sleep/dataPoints/s9",
            "dataSource": ["platform": "ANDROID"],
            "sleep": [
                "interval": ["startTime": "2026-03-06T22:00:00Z", "endTime": "2026-03-07T06:00:00Z"],
                "sleepType": "MAIN_SLEEP",
                "outOfBedSegments": [["startTime": "2026-03-07T02:00:00Z", "endTime": "2026-03-07T02:10:00Z"]],
            ],
        ])
        let rows = GoogleHealthMapper.rows(point: point, type: type, calendar: Self.calendar("UTC"), nowMs: 1)
        #expect(rows.map(\.id) == ["gh:s9", "gh:s9:0", "gh:s9:1"])
        #expect(rows.map(\.categoryValue) == [0, 1, 6])
        #expect(abs((rows[1].value ?? 0) - 28_800) < 1e-9)
        #expect(GoogleHealthMirrorWriter.sleepValue(for: rows[0]) == .inBed)
        #expect(GoogleHealthMirrorWriter.sleepValue(for: rows[1]) == .asleepUnspecified)
        #expect(GoogleHealthMirrorWriter.sleepValue(for: rows[2]) == nil)
    }

    @Test func exerciseAndNutritionConverters() throws {
        let map = try Self.sharedMap()
        let exercise = GoogleHealthMapper.rows(
            point: RJ.from([
                "name": "users/me/dataTypes/exercise/dataPoints/e1",
                "dataSource": ["platform": "ANDROID", "application": ["packageName": "com.fitbit.FitbitMobile"]],
                "exercise": [
                    "interval": ["startTime": "2026-03-04T06:00:00Z", "endTime": "2026-03-04T06:30:00Z"],
                    "exerciseType": "RUNNING",
                    "exerciseMetadata": ["totalCaloriesKcal": 300, "totalDistanceMeters": 5000],
                ],
            ]),
            type: try #require(map.type("exercise")), calendar: Self.calendar("UTC"), nowMs: 1
        )
        let workout = try #require(exercise.first)
        #expect(workout.value == 1800)
        #expect(workout.title == "RUNNING")
        #expect(workout.categoryValue == 0)
        #expect(workout.extra["energy_kcal"] as? Double == 300 || workout.extra["energy_kcal"] as? Int == 300)
        #expect(workout.sourceID == "google_health:com.fitbit.FitbitMobile")
        #expect(GoogleHealthWorkoutTypes.activityType(forExerciseType: workout.title) == .running)

        let nutrition = GoogleHealthMapper.rows(
            point: RJ.from([
                "name": "users/me/dataTypes/nutrition-log/dataPoints/n1",
                "dataSource": ["platform": "ANDROID"],
                "nutritionLog": [
                    "sampleTime": ["physicalTime": "2026-03-04T08:00:00Z", "utcOffset": "0s"],
                    "mealType": "BREAKFAST",
                    "totalEnergyQuantity": ["value": 1046, "unit": "KILOJOULE"],
                    "foodConsumed": [["name": "Oatmeal"]],
                    "nutrients": [
                        ["nutrient": "PROTEIN", "quantity": ["value": 12, "unit": "GRAM"]],
                        ["nutrient": "TOTAL_CARBOHYDRATE", "quantity": ["value": 40, "unit": "GRAM"]],
                        ["nutrient": "SODIUM", "quantity": ["value": 0.2, "unit": "GRAM"]],
                    ],
                ],
            ]),
            type: try #require(map.type("nutrition-log")), calendar: Self.calendar("UTC"), nowMs: 1
        )
        let food = try #require(nutrition.first)
        #expect(abs((food.value ?? 0) - 250) < 1e-9)
        #expect(food.value2 == 12)
        #expect(food.value3 == 40)
        #expect(food.categoryValue == 1)
        #expect(food.title == "Oatmeal")
        #expect(abs(((food.extra["dietary_sodium"] as? NSNumber)?.doubleValue ?? 0) - 200) < 1e-9)
        #expect(GoogleHealthMirrorWriter.foodAmounts(food).map(\.0).contains("dietary_sodium"))
    }

    // MARK: - HealthKit mirror reader

    @Test func healthKitMirrorReaderSkipsGoogleHealthWrites() {
        let type = HKQuantityType(.stepCount)
        let start = Date(timeIntervalSince1970: 1_772_587_800)
        let tagged = HKQuantitySample(
            type: type, quantity: HKQuantity(unit: .count(), doubleValue: 412), start: start, end: start.addingTimeInterval(900),
            metadata: [HealthSampleMapper.googleHealthMetadataKey: "ayuvo_gh_st1"]
        )
        let plain = HKQuantitySample(type: type, quantity: HKQuantity(unit: .count(), doubleValue: 412), start: start, end: start.addingTimeInterval(900))
        #expect(HealthSampleMapper.row(from: tagged, type: F.steps, calendar: F.calendar, nowMs: 1) == nil)
        #expect(HealthSampleMapper.row(from: plain, type: F.steps, calendar: F.calendar, nowMs: 1) != nil)
        #expect(HealthSampleMapper.sources(from: [tagged], nowMs: 1).isEmpty)
        #expect(HealthKitPage(skippedCount: 1).isEmpty == false)
    }

    // MARK: - OAuth helpers

    @Test func pkceAndRedirectFollowTheSpecs() throws {
        // RFC 7636 appendix B.
        #expect(GoogleOAuthPKCE.challenge(for: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk") == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        let pkce = GoogleOAuthPKCE.make()
        #expect(pkce.verifier.count == 43)
        #expect(pkce.challenge == GoogleOAuthPKCE.challenge(for: pkce.verifier))
        let clientID = "1234-abcd.apps.googleusercontent.com"
        #expect(GoogleOAuthPKCE.redirectURI(clientID: clientID) == "com.googleusercontent.apps.1234-abcd:/oauth2redirect")
        #expect(GoogleOAuthPKCE.redirectURI(clientID: "not-a-client") == nil)

        let map = try Self.sharedMap()
        let url = try #require(GoogleOAuthPKCE.authorizationURL(
            authURL: map.api.authURL, clientID: clientID, scopes: map.requestedScopes(groups: ["sleep"]), pkce: pkce
        ))
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        #expect(items.first { $0.name == "code_challenge_method" }?.value == "S256")
        #expect(items.first { $0.name == "scope" }?.value == "openid email https://www.googleapis.com/auth/googlehealth.sleep.readonly")

        let callback = URL(string: "com.googleusercontent.apps.1234-abcd:/oauth2redirect?state=\(pkce.state)&code=4/abc")!
        #expect(try GoogleOAuthPKCE.code(fromCallback: callback, expectedState: pkce.state) == "4/abc")
        #expect(throws: GoogleHealthAuthError.stateMismatch) {
            try GoogleOAuthPKCE.code(fromCallback: callback, expectedState: "other")
        }
    }

    @Test func grantedGroupsNeedEveryScopeOfTheGroup() throws {
        let map = try Self.sharedMap()
        let prefix = map.api.scopePrefix
        let granted: Set<String> = ["openid", prefix + "sleep.readonly", prefix + "ecg.readonly"]
        #expect(map.grantedGroups(scopes: granted) == ["sleep"])
    }
}
