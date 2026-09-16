import Foundation
import Testing
@testable import calorietracker

struct CloudBackupArchiveTests {
    @Test func roundTripKeepsFoodEntryIDsAndExcludesHealthTokens() throws {
        let id = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!
        let values: [String: CloudBackupValue] = [
            "foodEntries": .string("[{\"id\":\"\(id.uuidString)\"}]"),
            "healthKitFoodRecoveryDone": .bool(false),
            "healthKitNutritionBackfillVersion": .int(8),
        ]
        let photo = Data([1, 2, 3, 4])
        let zip = try CloudBackupArchive.pack(
            values: values,
            photos: ["\(id.uuidString).jpg": photo],
            exportedAt: "2026-09-10T12:00:00Z",
            appVersion: "7.0"
        )
        let (document, photos) = try CloudBackupArchive.unpack(zip)
        #expect(document.format == CloudBackupPolicy.format)
        #expect(document.payload.values["foodEntries"]?.s?.contains(id.uuidString) == true)
        #expect(document.payload.values["healthKitFoodRecoveryDone"] == nil)
        #expect(document.payload.values["healthKitNutritionBackfillVersion"] == nil)
        #expect(photos["\(id.uuidString).jpg"] == photo)
    }

    @Test func newerFormatFailsClosed() throws {
        let zip = try CloudBackupArchive.pack(
            values: ["useMetric": .bool(true)],
            photos: [:],
            exportedAt: "2026-09-10T12:00:00Z",
            appVersion: "7.0"
        )
        var files = try CloudBackupZip.unpack(zip)
        var document = try JSONDecoder().decode(CloudBackupDocument.self, from: files[CloudBackupPolicy.payloadName]!)
        document.formatVersion = CloudBackupPolicy.version + 1
        files[CloudBackupPolicy.payloadName] = try JSONEncoder().encode(document)
        let tampered = CloudBackupZip.pack(files: files.map { ($0.key, $0.value) })
        do {
            _ = try CloudBackupArchive.unpack(tampered)
            Issue.record("expected newer-format backup to fail")
        } catch CloudBackupError.needsNewerApp {
        } catch {
            Issue.record("wrong error \(error)")
        }
    }

    @Test func medicationPreferencesAndPendingRouteNeverEnterTheCloudBackup() throws {
        let values: [String: CloudBackupValue] = [
            "medicationRemindersEnabled": .bool(true),
            "medicationSnoozeMinutes": .int(30),
            "medication.pending": .string("abc"),
            "useMetric": .bool(true),
        ]
        let zip = try CloudBackupArchive.pack(values: values, photos: [:], exportedAt: "2026-09-16T10:00:00Z", appVersion: "1.0")
        let (document, _) = try CloudBackupArchive.unpack(zip)
        let keys = Set(document.payload.values.keys)
        #expect(!keys.contains("medicationRemindersEnabled"))
        #expect(!keys.contains("medicationSnoozeMinutes"))
        #expect(!keys.contains("medication.pending"))
        #expect(keys.contains("useMetric"))
        #expect(!CloudBackupPolicy.include(MedicationSettings.remindersEnabledKey))
        #expect(!CloudBackupPolicy.include(MedicationSettings.pendingRouteKey))
    }

    @Test func coachChatHistoryAndHealthHubThrottlesAreExcludedWhilePreferencesAreKept() throws {
        let values: [String: CloudBackupValue] = [
            "coachChatHistory": .string("[{\"role\":\"user\",\"content\":\"my resting HR is 52\"}]"),
            "healthKitHubLastSyncAt": .int(1_800_000_000),
            "healthKitHubPromptedVersion": .int(1),
            "healthKitHubRateLimitedUntil": .int(0),
            "healthKitBackgroundDeliveryVersion": .int(1),
            "healthKitEnabled": .bool(true),
            "healthHomeTiles": .stringArray(["steps", "sleep"]),
            "healthGlucoseUnit": .string("mmol/L"),
            "coachHealthDataEnabled": .bool(true),
            "coachHealthDataConsentedAt": .string("2026-09-14T10:00:00Z"),
        ]
        let zip = try CloudBackupArchive.pack(values: values, photos: [:], exportedAt: "2026-09-14T10:00:00Z", appVersion: "7.0")
        let (document, _) = try CloudBackupArchive.unpack(zip)
        let keys = Set(document.payload.values.keys)
        #expect(!keys.contains("coachChatHistory"))
        #expect(!keys.contains("healthKitHubLastSyncAt"))
        #expect(!keys.contains("healthKitHubPromptedVersion"))
        #expect(!keys.contains("healthKitHubRateLimitedUntil"))
        #expect(!keys.contains("healthKitBackgroundDeliveryVersion"))
        #expect(keys.contains("healthKitEnabled"))
        #expect(keys.contains("healthHomeTiles"))
        #expect(keys.contains("healthGlucoseUnit"))
        #expect(keys.contains("coachHealthDataEnabled"))
        #expect(keys.contains("coachHealthDataConsentedAt"))
        #expect(CloudBackupPolicy.excludedKeys.contains("coachChatHistory"))
        #expect(CloudBackupPolicy.version == 1)
    }

    @Test func healthDatabaseDirectoryIsOutsideEveryBackedUpKey() {
        // The mirror lives on disk, never in UserDefaults, so no cloud-backup key can carry it.
        let directory = HealthDatabaseLocation.directory()
        #expect(directory.path.contains("Application Support/Ayuvo/Health"))
        #expect(HealthDatabaseLocation.databaseURL().lastPathComponent == "health.sqlite")
        #expect(HealthDatabaseLocation.sidecarSuffixes == ["", "-wal", "-shm", "-journal"])
        for key in ["healthKitHubLastSyncAt", "healthKitHubPromptedVersion", "healthKitHubRateLimitedUntil", "healthKitBackgroundDeliveryVersion"] {
            #expect(!CloudBackupPolicy.include(key), Comment(rawValue: key))
        }
    }

    @Test func contentHashIsStableUntilPayloadChanges() {
        let values = ["foodEntries": CloudBackupValue.string("[]")]
        #expect(CloudBackupArchive.contentHash(values: values, photos: [:]) == CloudBackupArchive.contentHash(values: values, photos: [:]))
        let changed = ["foodEntries": CloudBackupValue.string("[1]")]
        #expect(CloudBackupArchive.contentHash(values: values, photos: [:]) != CloudBackupArchive.contentHash(values: changed, photos: [:]))
    }
}
