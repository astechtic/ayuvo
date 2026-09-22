import Foundation
import SwiftUI
import UniformTypeIdentifiers

/// The portable `ayuvo-medications` v1 archive (docs §14). Shape rules live in the reference
/// (`MR.exportArchive` / `MR.mergeArchive`); this type only carries the JSON and serializes it.
nonisolated struct MedicationArchive: Sendable {
    static let fileName = "ayuvo-medications.json"

    let json: RJ

    init(json: RJ) {
        self.json = json
    }

    /// Parses and checks the envelope (`format`, `version`); the merge validates every row later.
    init(data: Data) throws {
        guard let text = String(data: data, encoding: .utf8), let parsed = RJ.parse(text), parsed.object != nil else {
            throw MedicationStoreError.archive("bad_format")
        }
        guard parsed["format"].string == MR.archiveFormat else { throw MedicationStoreError.archive("bad_format") }
        guard parsed["version"].double == Double(MR.archiveVersion) else { throw MedicationStoreError.archive("unsupported_version") }
        json = parsed
    }

    /// Sorted keys, pretty printed, UTF-8, trailing newline.
    var data: Data {
        guard JSONSerialization.isValidJSONObject(json.anyValue),
              var data = try? JSONSerialization.data(withJSONObject: json.anyValue, options: [.sortedKeys, .prettyPrinted, .withoutEscapingSlashes])
        else { return Data() }
        data.append(0x0A)
        return data
    }

    var medicationCount: Int { json["medications"].array?.count ?? 0 }
    var scheduleCount: Int { json["schedules"].array?.count ?? 0 }
    var doseLogCount: Int { json["dose_logs"].array?.count ?? 0 }
    var exportedMs: Int64? { MR.int(json["exported_ms"]).map(Int64.init) }
}
