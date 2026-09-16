import Foundation
import CryptoKit

enum CloudBackupPolicy {
    static let format = "ayuvo-cloud-backup"
    static let version = 1
    static let payloadName = "backup.json"
    static let photosDirectory = "photos/"
    static let minAutoBackupInterval: TimeInterval = 15 * 60

    static let excludedKeys: Set<String> = [
        "healthKitFoodRecoveryDone",
        "healthKitNutritionBackfillVersion",
        "healthKitWeightBackfillVersion",
        "healthKitBodyFatBackfillVersion",
        "healthKitWorkoutBurnDeletionTombstones",
        "healthKitTypesVersion",
        "healthKitAuthVersion",
        "lastNotifiedAppUpdateVersion",
        // Coach transcripts can quote Health data the user asked about; App Store 5.1.3(ii)
        // forbids health data in iCloud, so the chat history stays on the device.
        "coachChatHistory",
    ]

    static func include(_ key: String) -> Bool {
        if key == "healthKitEnabled" { return true }
        if excludedKeys.contains(key) { return false }
        if key.hasPrefix("healthKit") { return false }
        // Health Records preferences never enter iCloud (docs/health-records.md §6); the records
        // themselves live in the backup-excluded `Application Support/Ayuvo/Records/`.
        if key.hasPrefix("healthRecords") { return false }
        // Medication preferences and the pending notification route stay on the device; the
        // medications database lives in the backup-excluded `Application Support/Ayuvo/Medications/`
        // and has its own portable `ayuvo-medications` export (docs/medications.md §2, §14).
        if key.hasPrefix("medication") { return false }
        if key.hasPrefix("Apple") || key.hasPrefix("NS") || key.hasPrefix("com.apple") { return false }
        if key.hasPrefix("AK") { return false }
        return true
    }

    static func safePhotoName(_ name: String) -> String? {
        let base = (name as NSString).lastPathComponent
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._-"))
        guard base.unicodeScalars.allSatisfy({ allowed.contains($0) }) else { return nil }
        let ext = (base as NSString).pathExtension.lowercased()
        guard ["jpg", "jpeg", "png", "webp"].contains(ext) else { return nil }
        return base
    }
}

struct CloudBackupDocument: Codable {
    var format: String
    var formatVersion: Int
    var exportedAt: String
    var appVersion: String
    var platform: String
    var contentSha256: String
    var payload: CloudBackupPayload

    enum CodingKeys: String, CodingKey {
        case format
        case formatVersion = "format_version"
        case exportedAt = "exported_at"
        case appVersion = "app_version"
        case platform
        case contentSha256 = "content_sha256"
        case payload
    }
}

struct CloudBackupPayload: Codable {
    var values: [String: CloudBackupValue]
}

struct CloudBackupValue: Codable {
    var t: String
    var b: Bool? = nil
    var i: Int? = nil
    var s: String? = nil
    var d: String? = nil
    var ss: [String]? = nil

    static func bool(_ v: Bool) -> CloudBackupValue { CloudBackupValue(t: "b", b: v) }
    static func int(_ v: Int) -> CloudBackupValue { CloudBackupValue(t: "i", i: v) }
    static func string(_ v: String) -> CloudBackupValue { CloudBackupValue(t: "s", s: v) }
    static func data(_ v: Data) -> CloudBackupValue { CloudBackupValue(t: "d", d: v.base64EncodedString()) }
    static func stringArray(_ v: [String]) -> CloudBackupValue { CloudBackupValue(t: "ss", ss: v) }
}

enum CloudBackupArchive {
    static func contentHash(values: [String: CloudBackupValue], photos: [String: Data]) -> String {
        var canonical = ""
        for key in values.keys.sorted() {
            guard let value = values[key] else { continue }
            canonical += "\(key)="
            switch value.t {
            case "b": canonical += "b:\(value.b.map { $0 ? "true" : "false" } ?? "")"
            case "i": canonical += "i:\(value.i.map(String.init) ?? "")"
            case "s": canonical += "s:\(value.s ?? "")"
            case "d": canonical += "d:\(value.d ?? "")"
            case "ss": canonical += "ss:\((value.ss ?? []).sorted().joined(separator: ","))"
            default: canonical += value.t
            }
            canonical += "\n"
        }
        for name in photos.keys.sorted() {
            canonical += "photo:\(name):\(photos[name]?.count ?? 0)\n"
        }
        return sha256(Data(canonical.utf8))
    }

    static func pack(
        values: [String: CloudBackupValue],
        photos: [String: Data],
        exportedAt: String,
        appVersion: String
    ) throws -> Data {
        let filtered = values.filter { CloudBackupPolicy.include($0.key) && !CloudBackupPolicy.excludedKeys.contains($0.key) }
        let safePhotos = Dictionary(uniqueKeysWithValues: photos.compactMap { name, data in
            CloudBackupPolicy.safePhotoName(name).map { ($0, data) }
        })
        let document = CloudBackupDocument(
            format: CloudBackupPolicy.format,
            formatVersion: CloudBackupPolicy.version,
            exportedAt: exportedAt,
            appVersion: appVersion,
            platform: "ios",
            contentSha256: contentHash(values: filtered, photos: safePhotos),
            payload: CloudBackupPayload(values: filtered)
        )
        let payload = try JSONEncoder().encode(document)
        var files: [(String, Data)] = [(CloudBackupPolicy.payloadName, payload)]
        for (name, data) in safePhotos.sorted(by: { $0.key < $1.key }) {
            files.append((CloudBackupPolicy.photosDirectory + name, data))
        }
        return CloudBackupZip.pack(files: files)
    }

    static func unpack(_ data: Data) throws -> (CloudBackupDocument, [String: Data]) {
        let files = try CloudBackupZip.unpack(data)
        guard let payload = files[CloudBackupPolicy.payloadName] else {
            throw CloudBackupError.missingPayload
        }
        let document = try JSONDecoder().decode(CloudBackupDocument.self, from: payload)
        guard document.format == CloudBackupPolicy.format else { throw CloudBackupError.invalidFormat }
        guard document.formatVersion <= CloudBackupPolicy.version else { throw CloudBackupError.needsNewerApp }
        var photos: [String: Data] = [:]
        for (name, bytes) in files where name.hasPrefix(CloudBackupPolicy.photosDirectory) {
            if let safe = CloudBackupPolicy.safePhotoName(name) {
                photos[safe] = bytes
            }
        }
        return (document, photos)
    }

    static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }
}

enum CloudBackupError: LocalizedError, Equatable {
    case missingPayload
    case invalidFormat
    case needsNewerApp
    case iCloudUnavailable
    case noBackup

    var errorDescription: String? {
        switch self {
        case .missingPayload, .invalidFormat: return "This is not a Ayuvo backup."
        case .needsNewerApp: return "This backup needs a newer Ayuvo."
        case .iCloudUnavailable: return "Sign into iCloud in iOS Settings first."
        case .noBackup: return "No iCloud backup found."
        }
    }
}

/// Uncompressed ZIP (store method) so we don't need a third-party zip library.
enum CloudBackupZip {
    static func pack(files: [(String, Data)]) -> Data {
        var locals = Data()
        var central = Data()
        var offset: UInt32 = 0
        for (name, data) in files {
            let nameData = Data(name.utf8)
            let crc = crc32(data)
            var local = Data()
            local.append(contentsOf: u32(0x04034b50))
            local.append(contentsOf: u16(20))
            local.append(contentsOf: u16(0))
            local.append(contentsOf: u16(0))
            local.append(contentsOf: u16(0))
            local.append(contentsOf: u16(0))
            local.append(contentsOf: u32(crc))
            local.append(contentsOf: u32(UInt32(data.count)))
            local.append(contentsOf: u32(UInt32(data.count)))
            local.append(contentsOf: u16(UInt16(nameData.count)))
            local.append(contentsOf: u16(0))
            local.append(nameData)
            local.append(data)
            locals.append(local)

            var dir = Data()
            dir.append(contentsOf: u32(0x02014b50))
            dir.append(contentsOf: u16(20))
            dir.append(contentsOf: u16(20))
            dir.append(contentsOf: u16(0))
            dir.append(contentsOf: u16(0))
            dir.append(contentsOf: u16(0))
            dir.append(contentsOf: u16(0))
            dir.append(contentsOf: u32(crc))
            dir.append(contentsOf: u32(UInt32(data.count)))
            dir.append(contentsOf: u32(UInt32(data.count)))
            dir.append(contentsOf: u16(UInt16(nameData.count)))
            dir.append(contentsOf: u16(0))
            dir.append(contentsOf: u16(0))
            dir.append(contentsOf: u16(0))
            dir.append(contentsOf: u16(0))
            dir.append(contentsOf: u32(0))
            dir.append(contentsOf: u32(offset))
            dir.append(nameData)
            central.append(dir)
            offset += UInt32(local.count)
        }
        var end = Data()
        end.append(contentsOf: u32(0x06054b50))
        end.append(contentsOf: u16(0))
        end.append(contentsOf: u16(0))
        end.append(contentsOf: u16(UInt16(files.count)))
        end.append(contentsOf: u16(UInt16(files.count)))
        end.append(contentsOf: u32(UInt32(central.count)))
        end.append(contentsOf: u32(offset))
        end.append(contentsOf: u16(0))
        var out = Data()
        out.append(locals)
        out.append(central)
        out.append(end)
        return out
    }

    /// Central-directory reader (stored + deflate), so an Android-produced backup — which
    /// uses `ZipOutputStream` deflate — restores on iOS as well as our own stored archives.
    /// Unreadable bytes yield an empty map, matching the old walker's fail-soft behaviour;
    /// `CloudBackupArchive.unpack` then throws `missingPayload`.
    static func unpack(_ data: Data) throws -> [String: Data] {
        guard let reader = try? ZipArchiveReader(data: data) else { return [:] }
        return (try? reader.allEntries()) ?? [:]
    }

    private static func u16(_ v: UInt16) -> [UInt8] {
        [UInt8(v & 0xff), UInt8((v >> 8) & 0xff)]
    }

    private static func u32(_ v: UInt32) -> [UInt8] {
        [UInt8(v & 0xff), UInt8((v >> 8) & 0xff), UInt8((v >> 16) & 0xff), UInt8((v >> 24) & 0xff)]
    }

    private static func crc32(_ data: Data) -> UInt32 {
        CRC32.checksum(data)
    }
}
