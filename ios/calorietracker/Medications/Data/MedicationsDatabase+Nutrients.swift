import Foundation
import SQLite3

/// What ONE dose unit of a medication contains (schema v2 `medication_nutrients`, docs/medications.md §21),
/// in the nutrient's canonical unit (docs/nutrients.md §6).
nonisolated struct MedicationNutrient: Hashable, Sendable, Identifiable {
    var medicationID: String
    var nutrientKey: String
    var amountPerUnit: Double

    var id: String { "\(medicationID)|\(nutrientKey)" }

    var referenceInput: NutrientsReference.MedicationNutrientInput {
        NutrientsReference.MedicationNutrientInput(medicationID: medicationID, nutrientKey: nutrientKey, amountPerUnit: amountPerUnit)
    }
}

/// Reads and writes of `medication_nutrients` and the taken-dose query supplement totals are built from.
extension MedicationsDatabase {
    nonisolated static let nutrientColumns = "medication_id, nutrient_key, amount_per_unit"

    nonisolated static func decodeNutrient(_ s: HealthDBStatement) -> MedicationNutrient {
        MedicationNutrient(medicationID: s.text(0) ?? "", nutrientKey: s.text(1) ?? "", amountPerUnit: s.double(2) ?? 0)
    }

    /// One medication's rows, by key (unknown keys are kept here; readers drop them).
    func nutrients(medicationID: String) throws -> [MedicationNutrient] {
        var rows: [MedicationNutrient] = []
        try connection.query("SELECT \(Self.nutrientColumns) FROM medication_nutrients WHERE medication_id=? ORDER BY nutrient_key",
                             [.text(medicationID)]) { rows.append(Self.decodeNutrient($0)) }
        return rows
    }

    func allNutrients() throws -> [MedicationNutrient] {
        var rows: [MedicationNutrient] = []
        try connection.query("SELECT \(Self.nutrientColumns) FROM medication_nutrients ORDER BY medication_id, nutrient_key") {
            rows.append(Self.decodeNutrient($0))
        }
        return rows
    }

    /// Nutrient keys listed by a medication with `status = active` (Nutrition Details rows, docs/nutrients.md §5b).
    func activeNutrientKeys() throws -> Set<String> {
        var keys = Set<String>()
        try connection.query("""
            SELECT DISTINCT n.nutrient_key FROM medication_nutrients n JOIN medications m ON m.id = n.medication_id
            WHERE m.status = 'active'
            """) { s in
            if let key = s.text(0) { keys.insert(key) }
        }
        return keys
    }

    private func insertNutrient(_ row: MedicationNutrient) throws {
        try connection.run("INSERT INTO medication_nutrients (\(Self.nutrientColumns)) VALUES (?, ?, ?)",
                           [.text(row.medicationID), .text(row.nutrientKey), .real(row.amountPerUnit)])
    }

    /// Replaces a medication's rows as a set (docs §21); `updatedMs` also bumps `medications.updated_ms` so
    /// archive merges carry the change. Runs inside the caller's transaction when there is one.
    func replaceNutrientsNoTransaction(medicationID: String, with rows: [MedicationNutrient], updatedMs: Int64?) throws {
        try connection.run("DELETE FROM medication_nutrients WHERE medication_id=?", [.text(medicationID)])
        for row in rows {
            try insertNutrient(MedicationNutrient(medicationID: medicationID, nutrientKey: row.nutrientKey, amountPerUnit: row.amountPerUnit))
        }
        if let updatedMs {
            try connection.run("UPDATE medications SET updated_ms=? WHERE id=?", [.int(updatedMs), .text(medicationID)])
        }
    }

    func replaceNutrients(medicationID: String, with rows: [MedicationNutrient], updatedMs: Int64?) throws {
        try connection.inTransaction {
            try replaceNutrientsNoTransaction(medicationID: medicationID, with: rows, updatedMs: updatedMs)
        }
    }

    /// Taken doses whose `taken_at_ms` lies in `[fromMs, toMs)` (`idx_dose_logs_status` narrows to `taken`).
    func takenDoseLogs(from fromMs: Int64, to toMs: Int64) throws -> [DoseLog] {
        var rows: [DoseLog] = []
        try connection.query(
            "SELECT \(Self.doseLogColumns) FROM dose_logs WHERE status='taken' AND taken_at_ms >= ? AND taken_at_ms < ? ORDER BY taken_at_ms, id",
            [.int(fromMs), .int(toMs)]
        ) { rows.append(Self.decodeDoseLog($0)) }
        return rows
    }
}
