import SwiftUI

// Reference readings, calibration and validation screens (docs/camera-vitals.md §4, §6, §7.1).

/// Add or edit the reference readings of a saved scan (chest strap / ECG HR, oximeter SpO₂, cuff BP …), stored in
/// `reference_json`. On a finger scan it can also add a personal SpO₂ calibration (Experimental estimates on) or a
/// BP calibration (Research estimates on).
struct VitalsReferenceSheet: View {
    let record: VitalScanRecord
    /// The updated record after saving (nil when cancelled).
    var onSaved: (VitalScanRecord?) -> Void = { _ in }

    @Environment(\.dismiss) private var dismiss
    @AppStorage(VitalsSettings.experimentalKey) private var experimentalEnabled = false
    @AppStorage(VitalsSettings.researchKey) private var researchEnabled = false

    @State private var heartRate = ""
    @State private var rmssd = ""
    @State private var respiratoryRate = ""
    @State private var spo2 = ""
    @State private var systolic = ""
    @State private var diastolic = ""
    @State private var device = ""
    @State private var useSpo2Calibration = false
    @State private var useBpCalibration = false
    @State private var scanGapMin = ""
    @State private var existing: [VitalCalibration] = []
    @State private var isSaving = false
    @State private var failed = false

    private var store: VitalsStore { VitalsStore.shared }

    /// Plausible entry ranges; anything else is a typo.
    static let ranges: [String: ClosedRange<Double>] = [
        "heart_rate": 25...250, "hrv_rmssd": 1...300, "respiratory_rate": 3...60, "spo2": 50...100,
        "systolic": 60...260, "diastolic": 30...160, "scan_gap_min": 0...60,
    ]

    static func number(_ text: String) -> Double? {
        let t = text.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        return t.isEmpty ? nil : Double(t)
    }

    private func field(_ key: String, _ text: String) -> (value: Double?, ok: Bool) {
        guard !text.trimmingCharacters(in: .whitespaces).isEmpty else { return (nil, true) }
        guard let v = Self.number(text), let range = Self.ranges[key], range.contains(v) else { return (nil, false) }
        return (v, true)
    }

    private var reference: VitalsReference {
        VitalsReference(heartRate: field("heart_rate", heartRate).value, rmssd: field("hrv_rmssd", rmssd).value,
                        respiratoryRate: field("respiratory_rate", respiratoryRate).value, spo2: field("spo2", spo2).value,
                        systolic: field("systolic", systolic).value, diastolic: field("diastolic", diastolic).value,
                        device: device)
    }

    private var invalidFields: Bool {
        [("heart_rate", heartRate), ("hrv_rmssd", rmssd), ("respiratory_rate", respiratoryRate), ("spo2", spo2),
         ("systolic", systolic), ("diastolic", diastolic), ("scan_gap_min", scanGapMin)].contains { !field($0.0, $0.1).ok }
            || (systolic.isEmpty != diastolic.isEmpty)
    }

    private var canSpo2: Bool { VitalsCalibrations.canCalibrateSpo2(record, experimentalEnabled: experimentalEnabled) }
    private var canBp: Bool { VitalsCalibrations.canCalibrateBp(record, researchEnabled: researchEnabled) }
    private var bpCalibrationMissingGap: Bool { useBpCalibration && field("scan_gap_min", scanGapMin).value == nil }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    numberField("Heart rate", unit: String(localized: "bpm"), text: $heartRate, id: "heartRate")
                    numberField("HRV (RMSSD)", unit: "ms", text: $rmssd, id: "rmssd")
                    numberField("Respiratory rate", unit: String(localized: "/min", comment: "Breaths per minute unit"), text: $respiratoryRate, id: "respiratoryRate")
                } header: {
                    Text("Pulse and breathing")
                } footer: {
                    Text("From a chest strap, an ECG or a respiratory belt, taken at the same time as the scan.")
                }
                Section {
                    numberField("SpO₂", unit: "%", text: $spo2, id: "spo2")
                    if canSpo2 {
                        Toggle("Use as SpO₂ calibration", isOn: $useSpo2Calibration)
                            .disabled(field("spo2", spo2).value == nil)
                            .accessibilityIdentifier("vitals.reference.spo2Calibration")
                    }
                } header: {
                    Text("Pulse oximeter")
                } footer: {
                    if canSpo2 {
                        Text("A calibration pairs this scan with the oximeter reading on this phone model. Experimental estimate; it needs at least \(Int(VitalsConfig.shared.research.spo2MinCalibrations)) pairs.")
                    }
                }
                Section {
                    numberField("Systolic", unit: "mmHg", text: $systolic, id: "systolic")
                    numberField("Diastolic", unit: "mmHg", text: $diastolic, id: "diastolic")
                    if canBp {
                        Toggle("Use as BP calibration", isOn: $useBpCalibration)
                            .disabled(!reference.hasBloodPressure)
                            .accessibilityIdentifier("vitals.reference.bpCalibration")
                        if useBpCalibration {
                            numberField("Minutes between scan and cuff reading", unit: "min", text: $scanGapMin, id: "scanGap")
                        }
                    }
                } header: {
                    Text("Blood pressure cuff")
                } footer: {
                    if canBp {
                        Text("Research estimate, not a blood pressure measurement. Only cuff readings within \(Int(VitalsConfig.shared.research.bpCalibrationMaxGapMin)) minutes of the scan are used, and calibrations expire after \(Int(VitalsConfig.shared.research.bpCalibrationMaxAgeDays)) days.")
                    }
                }
                Section {
                    TextField("Device name, e.g. Polar H10", text: $device)
                        .textInputAutocapitalization(.words)
                        .accessibilityIdentifier("vitals.reference.device")
                } header: {
                    Text("Reference device")
                } footer: {
                    if invalidFields {
                        Text("Check the highlighted values: one is outside the plausible range or a blood pressure value is missing.")
                            .foregroundStyle(AyuvoPalette.heart)
                    } else {
                        Text("Reference readings stay with this scan and feed the validation screen. They are never written to Apple Health.")
                    }
                }
            }
            .navigationTitle("Reference reading")
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("vitals.referenceSheet")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                        onSaved(nil)
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }
                        .disabled(isSaving || invalidFields || bpCalibrationMissingGap)
                        .accessibilityIdentifier("vitals.reference.save")
                }
            }
            .alert("Couldn't save the reading", isPresented: $failed) { Button("OK", role: .cancel) {} }
            .task { await load() }
        }
    }

    private func numberField(_ title: LocalizedStringKey, unit: String, text: Binding<String>, id: String) -> some View {
        HStack {
            Text(title)
            Spacer()
            TextField("—", text: text)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .frame(maxWidth: 90)
                .accessibilityIdentifier("vitals.reference.\(id)")
            Text(unit)
                .font(.system(.footnote, design: .rounded))
                .foregroundStyle(.secondary)
                .frame(minWidth: 36, alignment: .leading)
        }
    }

    private func load() async {
        let r = VitalsReference(referenceJSON: record.referenceJSON)
        func text(_ v: Double?) -> String { v.map { $0.formatted(.number.precision(.fractionLength(0...1)).grouping(.never)) } ?? "" }
        heartRate = text(r.heartRate)
        rmssd = text(r.rmssd)
        respiratoryRate = text(r.respiratoryRate)
        spo2 = text(r.spo2)
        systolic = text(r.systolic)
        diastolic = text(r.diastolic)
        device = r.device ?? ""
        existing = (await store.calibrations()).filter { $0.scanID == record.id }
        useSpo2Calibration = existing.contains { $0.kind == "spo2" }
        if let bp = existing.first(where: { $0.kind == "bp" }) {
            useBpCalibration = true
            scanGapMin = text((try? VitalsJSON.parse(bp.referenceJSON))?["scan_gap_min"].double)
        }
    }

    private func save() async {
        isSaving = true
        defer { isSaving = false }
        let ref = reference
        let now = HealthDatabase.nowMs()
        var calibrations: [VitalCalibration] = []
        if canSpo2, useSpo2Calibration, let value = ref.spo2,
           let c = VitalsCalibrations.spo2(scan: record, spo2: value, id: existing.first { $0.kind == "spo2" }?.id ?? VitalScanRecord.newID(), nowMs: now) {
            calibrations.append(c)
        }
        if canBp, useBpCalibration, let s = ref.systolic, let d = ref.diastolic, let gap = field("scan_gap_min", scanGapMin).value,
           let c = VitalsCalibrations.bp(scan: record, systolic: s, diastolic: d, scanGapMin: gap,
                                         id: existing.first { $0.kind == "bp" }?.id ?? VitalScanRecord.newID(), nowMs: now) {
            calibrations.append(c)
        }
        do {
            try await store.saveReference(scan: record, reference: ref.json, calibrations: calibrations)
            var updated = record
            updated.referenceJSON = ref.json.map(VitalsJSON.encode)
            dismiss()
            onSaved(updated)
        } catch {
            failed = true
        }
    }
}

/// The reference readings of a scan, for its detail screen.
struct VitalsReferenceSection: View {
    let record: VitalScanRecord
    let onEdit: () -> Void

    var body: some View {
        let r = VitalsReference(referenceJSON: record.referenceJSON)
        Section {
            if r.isEmpty {
                Text("No reference reading yet.")
                    .foregroundStyle(.secondary)
            } else {
                if let v = r.heartRate { LabeledContent(VitalsText.metricTitle("heart_rate"), value: "\(v.formatted()) " + String(localized: "bpm")) }
                if let v = r.rmssd { LabeledContent(VitalsText.metricTitle("hrv_rmssd"), value: "\(v.formatted()) ms") }
                if let v = r.respiratoryRate { LabeledContent(VitalsText.metricTitle("respiratory_rate"), value: "\(v.formatted()) " + String(localized: "/min", comment: "Breaths per minute unit")) }
                if let v = r.spo2 { LabeledContent(VitalsText.metricTitle("spo2"), value: "\(v.formatted()) %") }
                if let s = r.systolic, let d = r.diastolic { LabeledContent(VitalsText.metricTitle("blood_pressure"), value: "\(s.formatted())/\(d.formatted()) mmHg") }
                if let device = r.device { LabeledContent("Device", value: device) }
            }
            Button(r.isEmpty ? "Add reference reading" : "Edit reference reading", action: onEdit)
                .accessibilityIdentifier("vitals.detail.reference")
        } header: {
            Text("Reference reading")
        }
    }
}

// MARK: - Calibration

/// Camera measurements › Calibration: what the experimental SpO₂ and research BP calibrations need, and the
/// user's calibrations with delete. Shown only while one of those toggles is on.
struct VitalsCalibrationView: View {
    @AppStorage(VitalsSettings.experimentalKey) private var experimentalEnabled = false
    @AppStorage(VitalsSettings.researchKey) private var researchEnabled = false
    @State private var calibrations: [VitalCalibration] = []
    @State private var loaded = false

    private var store: VitalsStore { VitalsStore.shared }
    private var research: VitalsConfig.Research { VitalsConfig.shared.research }

    var body: some View {
        List {
            Section {
                Label {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("SpO₂ (experimental)").font(.system(.subheadline, design: .rounded, weight: .semibold))
                        Text("Needs at least \(Int(research.spo2MinCalibrations)) pairs of a finger scan and a pulse oximeter reading taken on this phone model. Healthy readings cluster between 95 and 99 %, so a calibration rarely covers low values; values outside it stay unavailable.")
                            .font(.system(.footnote, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "lungs.fill").foregroundStyle(AyuvoPalette.activity)
                }
                Label {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Blood pressure (research)").font(.system(.subheadline, design: .rounded, weight: .semibold))
                        Text("Needs at least \(Int(research.bpMinCalibrations)) cuff readings, each within \(Int(research.bpCalibrationMaxGapMin)) minutes of a finger scan. Calibrations expire after \(Int(research.bpCalibrationMaxAgeDays)) days. Research estimate, not a blood pressure measurement.")
                            .font(.system(.footnote, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "heart.text.square").foregroundStyle(AyuvoPalette.heart)
                }
            } header: {
                Text("How calibration works")
            } footer: {
                Text("Add a calibration from a finger scan's reference reading. \(VitalsText.disclaimer)")
            }

            ForEach(["spo2", "bp"], id: \.self) { kind in
                let rows = calibrations.filter { $0.kind == kind }.sorted { $0.tMs > $1.tMs }
                Section {
                    if rows.isEmpty {
                        Text("No calibrations yet.").foregroundStyle(.secondary)
                    }
                    ForEach(rows, id: \.id) { c in
                        calibrationRow(c)
                            .swipeActions {
                                Button("Delete", role: .destructive) { Task { await delete(c) } }
                            }
                    }
                } header: {
                    Text(kind == "spo2" ? "SpO₂ calibrations" : "Blood pressure calibrations")
                } footer: {
                    if kind == "spo2" {
                        Text("\(rows.filter { $0.deviceModel == VitalsDevice.model }.count) of \(Int(research.spo2MinCalibrations)) on this phone model (\(VitalsDevice.model)).")
                    } else {
                        Text("\(usableBp(rows)) of \(Int(research.bpMinCalibrations)) usable now.")
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Calibration")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("vitals.calibrationScreen")
        .task(id: store.revision) {
            calibrations = await store.calibrations()
            loaded = true
        }
    }

    private func usableBp(_ rows: [VitalCalibration]) -> Int {
        let nowMs = HealthDatabase.nowMs()
        return rows.filter { c in
            let gap = (try? VitalsJSON.parse(c.referenceJSON))?["scan_gap_min"].double ?? .infinity
            return gap <= research.bpCalibrationMaxGapMin && Double(nowMs - c.tMs) <= research.bpCalibrationMaxAgeDays * 86_400_000
        }.count
    }

    private func calibrationRow(_ c: VitalCalibration) -> some View {
        let ref = (try? VitalsJSON.parse(c.referenceJSON)) ?? .null
        let feat = (try? VitalsJSON.parse(c.featuresJSON)) ?? .null
        let date = Date(timeIntervalSince1970: Double(c.tMs) / 1000)
        let expired = c.kind == "bp" && Double(HealthDatabase.nowMs() - c.tMs) > research.bpCalibrationMaxAgeDays * 86_400_000
        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                if c.kind == "spo2" {
                    Text("\(ref["spo2"].double.map { $0.formatted() } ?? "—") % · ratio \(feat["ratio"].double.map { $0.formatted(.number.precision(.fractionLength(3))) } ?? "—")")
                        .font(.ayuvoNumber(.subheadline))
                } else {
                    Text("\(ref["sbp"].double.map { $0.formatted() } ?? "—")/\(ref["dbp"].double.map { $0.formatted() } ?? "—") mmHg · \(ref["scan_gap_min"].double.map { $0.formatted() } ?? "—") min after the scan")
                        .font(.ayuvoNumber(.subheadline))
                }
                Text("\(date.formatted(date: .abbreviated, time: .shortened)) · \(c.deviceModel)")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if expired {
                Text("Expired").font(.system(.caption, design: .rounded)).foregroundStyle(AyuvoPalette.activity)
            }
            Button(role: .destructive) {
                Task { await delete(c) }
            } label: {
                Image(systemName: "trash")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(Text("Delete calibration"))
        }
        .accessibilityIdentifier("vitals.calibration.\(c.kind)")
    }

    private func delete(_ c: VitalCalibration) async {
        await store.deleteCalibration(id: c.id)
        calibrations = await store.calibrations()
    }
}

// MARK: - Validation

/// Camera measurements › Validation: `validation_stats` per mode × metric over the user's scans with reference
/// readings, and the `ayuvo-vitals-validation` export for `scripts/vitals_eval.py`.
struct VitalsValidationView: View {
    @State private var exportFailed = false
    private var store: VitalsStore { VitalsStore.shared }

    var body: some View {
        let rows = VitalsValidation.rows(store.scans)
        let withReference = store.scans.filter { !VitalsReference(referenceJSON: $0.referenceJSON).isEmpty }.count
        List {
            Section {
                Text("Pairs each scan's value with the reference reading you entered for it. A failure is a scan with a reference reading but no valid value.")
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            if rows.isEmpty {
                Section {
                    ContentUnavailableView("No reference readings yet", systemImage: "checkmark.seal",
                                           description: Text("Open a scan and add a reference reading from a chest strap, ECG, pulse oximeter or cuff."))
                }
            }
            ForEach(VitalsMode.allCases) { mode in
                let modeRows = rows.filter { $0.mode == mode }
                if !modeRows.isEmpty {
                    Section {
                        ForEach(modeRows, id: \.id) { row in
                            statsRow(row)
                        }
                    } header: {
                        Label(mode == .finger ? "Finger scans" : "Face scans", systemImage: mode.systemImage)
                    }
                }
            }
            Section {
                Button {
                    export()
                } label: {
                    Label("Export validation dataset", systemImage: "square.and.arrow.up")
                }
                .disabled(withReference == 0)
                .accessibilityIdentifier("vitals.validation.export")
            } footer: {
                Text("Writes \(VitalsValidation.fileName) with every scan that has a reference reading (\(withReference)), for offline analysis with scripts/vitals_eval.py.")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Validation")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("vitals.validationScreen")
        .task(id: store.revision) { await store.reload() }
        .alert("Couldn't export the dataset", isPresented: $exportFailed) { Button("OK", role: .cancel) {} }
    }

    private func statsRow(_ row: VitalsValidation.Row) -> some View {
        let s = row.stats
        func num(_ key: String, _ digits: Int = 1) -> String {
            s[key].double.map { $0.formatted(.number.precision(.fractionLength(digits))) } ?? "—"
        }
        let loa = s["loa_low"].double != nil ? "\(num("loa_low")) … \(num("loa_high"))" : "—"
        let failure = s["failure_rate"].double.map { $0.formatted(.percent.precision(.fractionLength(0))) } ?? "—"
        let byConfidence = (s["by_confidence"].object ?? [:]).keys.sorted().compactMap { label -> String? in
            guard let mae = s["by_confidence"][label]["mae"].double else { return nil }
            let name = vitalsConfidenceText(label) ?? label
            return "\(name): \(mae.formatted(.number.precision(.fractionLength(1)))) (n \(Int(s["by_confidence"][label]["n"].double ?? 0)))"
        }
        return VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(VitalsText.metricTitle(row.metric) + (row.metric == "blood_pressure" ? " " + String(localized: "(systolic)") : ""))
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                Spacer()
                Text("n \(Int(s["n"].double ?? 0))").font(.ayuvoNumber(.caption)).foregroundStyle(.secondary)
            }
            Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 3) {
                GridRow {
                    stat("MAE", num("mae"))
                    stat("RMSE", num("rmse"))
                    stat("Bias", num("bias"))
                }
                GridRow {
                    stat("Limits of agreement", loa)
                    stat("r", num("r", 2))
                    stat("Failure rate", failure)
                }
            }
            if !byConfidence.isEmpty {
                Text("MAE by confidence: \(byConfidence.joined(separator: " · "))")
                    .font(.system(.caption2, design: .rounded))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("vitals.validation.\(row.id)")
    }

    private func stat(_ title: LocalizedStringKey, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title).font(.system(.caption2, design: .rounded)).foregroundStyle(.secondary)
            Text(value).font(.ayuvoNumber(.footnote)).monospacedDigit()
        }
    }

    private func export() {
        do {
            let url = try VitalsValidation.writeDataset(store.scans)
            ShareSheetPresenter.present(url: url) {
                try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
            }
        } catch {
            exportFailed = true
        }
    }
}
