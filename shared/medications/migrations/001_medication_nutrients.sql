-- Ayuvo Medications — migration 001 → schema v2 (contract: docs/medications.md §4, docs/nutrients.md §6).
--
-- Supplement nutrients: what ONE dose unit (tablet, capsule, ml, …) of a medication contains. Amounts are stored in
-- the nutrient's canonical unit from shared/nutrients/nutrient_reference.json (g | mg | mcg; IU is converted before
-- saving), so there is no unit column. nutrient_key is a reference key or one of the 8 sports supplement keys;
-- unknown keys are ignored by readers. Rows are replaced as a set when the user edits the medication's nutrients,
-- and that edit bumps medications.updated_ms. Deleting a medication deletes its rows (ON DELETE CASCADE).
-- Runs in one transaction after schema.sql (v1); afterwards medications_meta.schema_version = PRAGMA user_version = 2.

CREATE TABLE medication_nutrients (
  medication_id TEXT NOT NULL REFERENCES medications(id) ON DELETE CASCADE,
  nutrient_key TEXT NOT NULL,
  amount_per_unit REAL NOT NULL CHECK (amount_per_unit > 0),
  PRIMARY KEY (medication_id, nutrient_key));
