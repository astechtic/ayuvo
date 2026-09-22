import Foundation
import Observation

/// Settings, profile and logs as the `ayuvo-cloud-backup` zip (`CloudBackupArchive`; the format id
/// predates the removal of iCloud Backup and is kept so older exports still restore). Export All
/// Data writes it as `app-backup/ayuvo-backup.zip` and Import All Data restores it. Nothing leaves
/// the device through this type.
@Observable
final class AppBackupService {
    /// Preferences left behind by the removed iCloud Backup; cleared on launch.
    static let retiredKeys = ["cloudBackupEnabled", "cloudBackupLastAt", "cloudBackupLastHash"]

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        for key in Self.retiredKeys {
            defaults.removeObject(forKey: key)
        }
    }

    func snapshotValues() -> [String: CloudBackupValue] {
        var out: [String: CloudBackupValue] = [:]
        for (key, raw) in defaults.dictionaryRepresentation() {
            guard CloudBackupPolicy.include(key) else { continue }
            if let data = raw as? Data {
                out[key] = .data(data)
            } else if let strings = raw as? [String] {
                out[key] = .stringArray(strings)
            } else if let string = raw as? String {
                out[key] = .string(string)
            } else if let number = raw as? NSNumber {
                if CFGetTypeID(number) == CFBooleanGetTypeID() {
                    out[key] = .bool(number.boolValue)
                } else {
                    out[key] = .int(number.intValue)
                }
            }
        }
        return out
    }

    /// Replaces every backed-up preference with the backup's values (keys the backup lacks are removed).
    func applyValues(_ values: [String: CloudBackupValue]) {
        let restoredKeys = Set(values.keys)
        for key in defaults.dictionaryRepresentation().keys
        where CloudBackupPolicy.include(key) && !restoredKeys.contains(key) {
            defaults.removeObject(forKey: key)
        }
        for (key, value) in values {
            guard CloudBackupPolicy.include(key) else { continue }
            switch value.t {
            case "b":
                if let b = value.b { defaults.set(b, forKey: key) }
            case "i":
                if let i = value.i { defaults.set(i, forKey: key) }
            case "s":
                if let s = value.s { defaults.set(s, forKey: key) }
            case "d":
                if let d = value.d, let data = Data(base64Encoded: d) {
                    defaults.set(data, forKey: key)
                }
            case "ss":
                if let ss = value.ss { defaults.set(ss, forKey: key) }
            default:
                continue
            }
        }
        defaults.set(true, forKey: "healthKitFoodRecoveryDone")
    }

    func snapshotPhotos() -> [String: Data] {
        var photos: [String: Data] = [:]
        for name in FoodImageStore.shared.filenames() {
            guard let safe = CloudBackupPolicy.safePhotoName(name),
                  let data = FoodImageStore.shared.load(filename: safe)
            else { continue }
            photos[safe] = data
        }
        return photos
    }

    func restorePhotos(_ photos: [String: Data]) {
        FoodImageStore.shared.deleteAll()
        for (name, data) in photos {
            _ = FoodImageStore.shared.restore(data: data, filename: name)
        }
    }

    /// Restores an iPhone-made backup zip: meal photos, then preferences, profile and logs. The
    /// stores reload on `.appBackupDidRestore`.
    @discardableResult
    func restore(zip: Data) throws -> CloudBackupDocument {
        let (document, photos) = try CloudBackupArchive.unpack(zip)
        guard document.platform == "ios" else { throw CloudBackupError.otherPlatform }
        restorePhotos(photos)
        applyValues(document.payload.values)
        NotificationCenter.default.post(name: .appBackupDidRestore, object: nil)
        return document
    }
}

extension Notification.Name {
    static let appBackupDidRestore = Notification.Name("app.ayuvo.appBackupDidRestore")
}
