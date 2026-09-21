import SwiftUI

struct ProgressCardStyle: ViewModifier {
    let cornerRadius: CGFloat

    func body(content: Content) -> some View {
        // Apple-flat card (docs/ui-structure.md): same surface as `.ayuvoCard`, no accent stroke.
        let shape = RoundedRectangle(cornerRadius: AyuvoPalette.cardRadius, style: .continuous)
        content
            .background(AyuvoPalette.card)
            .clipShape(shape)
    }
}

extension View {
    func progressCardStyle(cornerRadius: CGFloat = 20) -> some View {
        modifier(ProgressCardStyle(cornerRadius: cornerRadius))
    }
}

// MARK: - Stats Section

struct StatBadge: View {
    let label: String
    let value: String

    var body: some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(LocalizedDisplayText.text(label))
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 4)
        .padding(.vertical, 8)
        .background(AyuvoPalette.panel, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

// MARK: - Log Weight Sheet

struct LogWeightSheet: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage("weightUnit") private var weightUnitRaw = "lbs"
    let currentWeightKg: Double
    let onSave: (Double) -> Void

    @State private var wholeNumber: Int
    @State private var decimal: Int

    init(currentWeightKg: Double, onSave: @escaping (Double) -> Void) {
        self.currentWeightKg = currentWeightKg
        self.onSave = onSave
        // Respect @AppStorage at the time the sheet is created.
        let metric = UserDefaults.standard.string(forKey: "weightUnit") == "kg"
        let displayValue = metric ? currentWeightKg : currentWeightKg * 2.20462
        let whole = Int(displayValue)
        let dec = min(9, max(0, Int((displayValue - Double(whole)) * 10 + 0.5)))
        _wholeNumber = State(initialValue: whole)
        _decimal = State(initialValue: dec)
    }

    private var useMetric: Bool { weightUnitRaw == "kg" }

    private var selectedValue: Double {
        Double(wholeNumber) + Double(decimal) / 10.0
    }

    private var selectedKg: Double {
        useMetric ? selectedValue : selectedValue / 2.20462
    }

    private var unit: String { useMetric ? "kg" : "lbs" }
    private var wholeRange: ClosedRange<Int> { useMetric ? 20...250 : 50...500 }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("Log Weight")
                    .font(.system(.title2, design: .rounded, weight: .bold))

                Picker("Unit", selection: $weightUnitRaw) {
                    Text("kg").tag("kg")
                    Text("lbs").tag("lbs")
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 24)
                .onChange(of: weightUnitRaw) { _, newValue in
                    // Convert the currently selected value so toggling mid-edit keeps it,
                    // clamped into the destination wheel's rows (20...250 kg / 50...500 lbs)
                    // so the selection never lands on a tag the wheel doesn't offer.
                    let value = Double(wholeNumber) + Double(decimal) / 10.0
                    let converted = newValue == "kg" ? value / 2.20462 : value * 2.20462
                    let bounds = newValue == "kg" ? 20.0...250.0 : 50.0...500.0
                    let clamped = min(bounds.upperBound, max(bounds.lowerBound, converted))
                    let whole = Int(clamped)
                    wholeNumber = whole
                    decimal = min(9, max(0, Int((clamped - Double(whole)) * 10 + 0.5)))
                }

                // Scroll wheel pickers
                HStack(spacing: 0) {
                    Picker("Whole", selection: $wholeNumber) {
                        ForEach(wholeRange, id: \.self) { num in
                            Text("\(num)").tag(num)
                                .font(.system(.title2, design: .rounded, weight: .medium))
                        }
                    }
                    .pickerStyle(.wheel)
                    .frame(width: 100)
                    .clipped()

                    Text(".")
                        .font(.system(.largeTitle, design: .rounded, weight: .bold))
                        .offset(y: -1)

                    Picker("Decimal", selection: $decimal) {
                        ForEach(0...9, id: \.self) { num in
                            Text("\(num)").tag(num)
                                .font(.system(.title2, design: .rounded, weight: .medium))
                        }
                    }
                    .pickerStyle(.wheel)
                    .frame(width: 70)
                    .clipped()

                    Text(unit)
                        .font(.system(.title3, design: .rounded))
                        .foregroundStyle(.secondary)
                        .padding(.leading, 4)
                }

                Button {
                    onSave(selectedKg)
                    dismiss()
                } label: {
                    Text("Save")
                        .font(.system(.headline, design: .rounded, weight: .semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(
                            LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing)
                        )
                        .foregroundStyle(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .padding(.horizontal, 24)

                Spacer()
            }
            .padding(.top, 24)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

// MARK: - Weight History Link (tap to open full list)

struct WeightHistoryLink: View {
    let totalCount: Int
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                Image(systemName: "list.bullet.rectangle")
                    .font(.body.weight(.medium))
                    .foregroundStyle(AppColors.calorie)
                    .frame(width: 28, height: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Weight History")
                        .font(.system(.body, design: .rounded, weight: .medium))
                        .foregroundStyle(.primary)
                    Text("\(totalCount) \(totalCount == 1 ? "entry" : "entries") · tap to view or delete")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.vertical, 12)
            .padding(.horizontal, 14)
            .progressCardStyle(cornerRadius: 18)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - All Weight History (full-screen sheet)

struct AllWeightHistoryView: View {
    let entries: [WeightEntry]
    let useMetric: Bool
    let onDelete: (WeightEntry) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var pendingDeletion: WeightEntry?
    // Local mirror so the list updates immediately after deletion without needing the parent to re-bind.
    @State private var visibleEntries: [WeightEntry] = []

    var body: some View {
        NavigationStack {
            List {
                ForEach(visibleEntries) { entry in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(displayWeight(entry.weightKg, useMetric: useMetric))
                                .font(.system(.body, design: .rounded, weight: .medium))
                            Text(weightHistoryFormatter.string(from: entry.date))
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            pendingDeletion = entry
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Weight History")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onAppear { visibleEntries = entries }
        .alert("Delete Weight Entry", isPresented: Binding(
            get: { pendingDeletion != nil },
            set: { if !$0 { pendingDeletion = nil } }
        )) {
            Button("Cancel", role: .cancel) { pendingDeletion = nil }
            Button("Delete", role: .destructive) {
                if let entry = pendingDeletion {
                    visibleEntries.removeAll { $0.id == entry.id }
                    onDelete(entry)
                }
                pendingDeletion = nil
            }
        } message: {
            if let entry = pendingDeletion {
                Text("Remove \(weightHistoryFormatter.string(from: entry.date))'s entry of \(displayWeight(entry.weightKg, useMetric: useMetric))? This also deletes the matching sample from Apple Health.")
            }
        }
    }
}

private let weightHistoryFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateStyle = .medium
    f.timeStyle = .none
    return f
}()

private func displayWeight(_ kg: Double, useMetric: Bool) -> String {
    if useMetric {
        return String(format: "%.1f kg", kg)
    }
    let lbs = kg * 2.20462
    return String(format: "%.1f lb", lbs)
}

// MARK: - Body Fat History (link + full list, mirroring Weight History)

struct BodyFatHistoryLink: View {
    let totalCount: Int
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                Image(systemName: "list.bullet.rectangle")
                    .font(.body.weight(.medium))
                    .foregroundStyle(AppColors.calorie)
                    .frame(width: 28, height: 28)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Body Fat History")
                        .font(.system(.body, design: .rounded, weight: .medium))
                        .foregroundStyle(.primary)
                    Text("\(totalCount) \(totalCount == 1 ? "entry" : "entries") · tap to view or delete")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.vertical, 12)
            .padding(.horizontal, 14)
            .progressCardStyle(cornerRadius: 18)
        }
        .buttonStyle(.plain)
    }
}

struct AllBodyFatHistoryView: View {
    let entries: [BodyFatEntry]
    let onDelete: (BodyFatEntry) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var pendingDeletion: BodyFatEntry?
    // Local mirror so the list updates immediately after deletion without needing the parent to re-bind.
    @State private var visibleEntries: [BodyFatEntry] = []

    var body: some View {
        NavigationStack {
            List {
                ForEach(visibleEntries) { entry in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(displayBodyFat(entry.bodyFatFraction))
                                .font(.system(.body, design: .rounded, weight: .medium))
                            Text(weightHistoryFormatter.string(from: entry.date))
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            pendingDeletion = entry
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Body Fat History")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onAppear { visibleEntries = entries }
        .alert("Delete Body Fat Entry", isPresented: Binding(
            get: { pendingDeletion != nil },
            set: { if !$0 { pendingDeletion = nil } }
        )) {
            Button("Cancel", role: .cancel) { pendingDeletion = nil }
            Button("Delete", role: .destructive) {
                if let entry = pendingDeletion {
                    visibleEntries.removeAll { $0.id == entry.id }
                    onDelete(entry)
                }
                pendingDeletion = nil
            }
        } message: {
            if let entry = pendingDeletion {
                Text("Remove \(weightHistoryFormatter.string(from: entry.date))'s entry of \(displayBodyFat(entry.bodyFatFraction))? This also deletes the matching sample from Apple Health.")
            }
        }
    }
}

private func displayBodyFat(_ fraction: Double) -> String {
    String(format: "%.1f%%", fraction * 100)
}

// MARK: - Log Body Fat Sheet

/// Single-wheel picker for body-fat %. Whole-number precision (matches
/// BodyFatPickerSheet in Settings) — body-fat measurements rarely justify
/// 0.1% resolution given the noise of calipers / smart scales.
struct LogBodyFatSheet: View {
    @Environment(\.dismiss) private var dismiss
    let currentFraction: Double
    let onSave: (Double) -> Void

    @State private var percentage: Int

    init(currentFraction: Double, onSave: @escaping (Double) -> Void) {
        self.currentFraction = currentFraction
        self.onSave = onSave
        _percentage = State(initialValue: Int(currentFraction * 100))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("Log Body Fat")
                    .font(.system(.title2, design: .rounded, weight: .bold))

                HStack(spacing: 0) {
                    Picker("Percentage", selection: $percentage) {
                        ForEach(3...60, id: \.self) { n in
                            Text("\(n)").tag(n)
                                .font(.system(.title2, design: .rounded, weight: .medium))
                        }
                    }
                    .pickerStyle(.wheel)
                    .frame(width: 100)
                    .clipped()

                    Text("%")
                        .font(.system(.title3, design: .rounded))
                        .foregroundStyle(.secondary)
                        .padding(.leading, 4)
                }

                Button {
                    onSave(Double(percentage) / 100.0)
                    dismiss()
                } label: {
                    Text("Save")
                        .font(.system(.headline, design: .rounded, weight: .semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 14)
                        .background(
                            LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing)
                        )
                        .foregroundStyle(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 14))
                }
                .padding(.horizontal, 24)

                Spacer()
            }
            .padding(.top, 24)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

// MARK: - Helpers

private func emptyState(_ message: String) -> some View {
    Text(message)
        .font(.system(.subheadline, design: .rounded))
        .foregroundStyle(.secondary)
        .frame(maxWidth: .infinity, minHeight: 80)
}

// MARK: - Body Measurements

/// cm → display string in the user's unit ("92.0 cm" / "36.2 in").
private func displayLength(_ cm: Double, useMetric: Bool) -> String {
    useMetric ? String(format: "%.1f cm", cm) : String(format: "%.1f in", cm / 2.54)
}

/// The logged sites in display order, skipping any that weren't entered.
private func measurementSites(_ m: BodyMeasurement) -> [(label: String, cm: Double)] {
    var rows: [(String, Double)] = []
    func add(_ label: String, _ value: Double?) { if let value { rows.append((label, value)) } }
    add("Neck", m.neckCm)
    add("Waist", m.waistCm)
    add("Hips", m.hipsCm)
    add("Chest", m.chestCm)
    add("Upper arm", m.upperArmCm)
    add("Thigh", m.thighCm)
    add("Calf", m.calfCm)
    add("Wrist", m.wristCm)
    return rows
}

/// The four derived metrics that can be computed from `m` + the profile, skipping any that can't.
private func derivedMetricChips(_ m: BodyMeasurement, gender: Gender, heightCm: Double) -> [(label: String, value: String)] {
    var chips: [(String, String)] = []
    if let whr = m.waistToHipRatio {
        chips.append(("Waist-to-hip", String(format: "%.2f", whr)))
    }
    if let whtr = m.waistToHeightRatio(heightCm: heightCm) {
        chips.append(("Waist-to-height", String(format: "%.2f", whtr)))
    }
    if let bf = m.usNavyBodyFatPercent(gender: gender, heightCm: heightCm) {
        chips.append(("Body fat (Navy)", String(format: "%.0f%%", bf)))
    }
    if let frame = m.wristFrame(gender: gender, heightCm: heightCm) {
        chips.append(("Frame", frame.label))
    }
    return chips
}

/// Settings → Personal Info detail screen. Mirrors the Other Nutrients screen: a tappable row per
/// body part that opens a wheel picker to set its value, plus the AI-derived metrics and history.
/// Lives in Settings (not Progress) so it sits with the other body inputs.
struct BodyMeasurementsDetailView: View {
    @Environment(BodyMeasurementStore.self) private var store
    @AppStorage("heightUnit") private var heightUnitRaw = "ftin"
    let gender: Gender
    let heightCm: Double

    private var useMetric: Bool { heightUnitRaw == "cm" }

    @State private var editingSite: BodyMeasurement.Site?
    @State private var showHistory = false

    private var latest: BodyMeasurement? { store.latestEntry }
    private var unit: String { useMetric ? "cm" : "in" }

    private func displayValue(_ site: BodyMeasurement.Site) -> String {
        guard let cm = latest?.value(for: site) else { return "Not set" }
        return useMetric ? String(format: "%.0f cm", cm) : String(format: "%.0f in", cm / 2.54)
    }

    var body: some View {
        List {
            Section {
                ForEach(BodyMeasurement.Site.allCases) { site in
                    Button {
                        editingSite = site
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "ruler")
                                .foregroundStyle(AppColors.calorie)
                                .frame(width: 22)
                            Text(site.label)
                                .foregroundStyle(.primary)
                            Spacer()
                            Text(displayValue(site))
                                .foregroundStyle(.secondary)
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .buttonStyle(.plain)
                }
            } header: {
                Text("Measurements")
            } footer: {
                Text("Optional. Ayuvo turns these into waist-to-hip, waist-to-height, body-fat %, and frame size, and reads them when it recalculates your goals and in Coach.")
            }
            .listRowBackground(AppColors.appCard)

            if let latest {
                let chips = derivedMetricChips(latest, gender: gender, heightCm: heightCm)
                if !chips.isEmpty {
                    Section("Derived") {
                        ForEach(chips, id: \.label) { chip in
                            HStack {
                                Text(chip.label)
                                Spacer()
                                Text(chip.value)
                                    .foregroundStyle(AppColors.calorie)
                                    .fontWeight(.semibold)
                            }
                        }
                    }
                    .listRowBackground(AppColors.appCard)
                }
            }

            if store.entries.count > 1 {
                Section {
                    Button {
                        showHistory = true
                    } label: {
                        HStack {
                            Text("Measurement History")
                                .foregroundStyle(.primary)
                            Spacer()
                            Text("\(store.entries.count)")
                                .foregroundStyle(.secondary)
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .buttonStyle(.plain)
                }
                .listRowBackground(AppColors.appCard)
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle("Body Measurements")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $editingSite) { site in
            MeasurementEditSheet(
                site: site,
                currentCm: latest?.value(for: site),
                onSave: { cm in store.setValue(site, cm: cm) },
                onClear: { store.setValue(site, cm: nil) }
            )
        }
        .sheet(isPresented: $showHistory) {
            AllBodyMeasurementsHistoryView(
                entries: store.sortedEntries,
                gender: gender,
                heightCm: heightCm,
                useMetric: useMetric,
                onDelete: { entry in store.deleteEntry(entry) }
            )
        }
    }
}

/// Editor for one measurement site. The cm|in switcher persists the shared
/// length standard (same pref as the Height editor), and — matching the
/// height/weight editors — flipping it converts the value currently on the
/// wheel (clamped into the destination wheel's rows) instead of re-seeding.
private struct MeasurementEditSheet: View {
    let site: BodyMeasurement.Site
    let hasCurrent: Bool
    let onSave: (Double) -> Void
    let onClear: () -> Void

    @AppStorage("heightUnit") private var heightUnitRaw = "ftin"
    @State private var displayValue: Int

    init(
        site: BodyMeasurement.Site,
        currentCm: Double?,
        onSave: @escaping (Double) -> Void,
        onClear: @escaping () -> Void
    ) {
        self.site = site
        self.hasCurrent = currentCm != nil
        self.onSave = onSave
        self.onClear = onClear
        let metric = UserDefaults.standard.string(forKey: "heightUnit") == "cm"
        let seed = currentCm.map { metric ? Int($0.rounded()) : Int(($0 / 2.54).rounded()) } ?? (metric ? 80 : 32)
        _displayValue = State(initialValue: seed)
    }

    private var useMetric: Bool { heightUnitRaw == "cm" }

    // Converts in the binding's setter so the new unit and the converted value
    // land in the same update — the re-keyed wheel below then seeds correctly.
    private var unitSelection: Binding<String> {
        Binding(
            get: { heightUnitRaw },
            set: { newValue in
                guard newValue != heightUnitRaw else { return }
                if newValue == "cm" {
                    displayValue = min(250, max(10, Int((Double(displayValue) * 2.54).rounded())))
                } else {
                    displayValue = min(100, max(4, Int((Double(displayValue) / 2.54).rounded())))
                }
                heightUnitRaw = newValue
            }
        )
    }

    var body: some View {
        VStack(spacing: 0) {
            Picker("Unit", selection: unitSelection) {
                Text("cm").tag("cm")
                Text("in").tag("ftin")
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 24)
            .padding(.top, 20)

            NutritionPickerSheet(
                label: site.label,
                unit: useMetric ? "cm" : "in",
                currentValue: displayValue,
                range: useMetric ? 10...250 : 4...100,
                step: 1,
                onSave: { value in onSave(useMetric ? Double(value) : Double(value) * 2.54) },
                onResetToAuto: hasCurrent ? onClear : nil,
                resetLabel: "Clear",
                onValueChange: { displayValue = $0 }
            )
            // Re-key so a unit flip rebuilds the wheel seeded with the value
            // converted above (its selection state is set once, in init).
            .id(heightUnitRaw)
        }
    }
}

/// Full history with swipe-to-delete, mirroring AllWeightHistoryView.
struct AllBodyMeasurementsHistoryView: View {
    let entries: [BodyMeasurement]
    let gender: Gender
    let heightCm: Double
    let useMetric: Bool
    let onDelete: (BodyMeasurement) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var pendingDeletion: BodyMeasurement?
    @State private var visibleEntries: [BodyMeasurement] = []

    var body: some View {
        NavigationStack {
            List {
                ForEach(visibleEntries) { entry in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(weightHistoryFormatter.string(from: entry.date))
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        Text(summary(entry))
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            pendingDeletion = entry
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Measurement History")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onAppear { visibleEntries = entries }
        .alert("Delete Measurement", isPresented: Binding(
            get: { pendingDeletion != nil },
            set: { if !$0 { pendingDeletion = nil } }
        )) {
            Button("Cancel", role: .cancel) { pendingDeletion = nil }
            Button("Delete", role: .destructive) {
                if let entry = pendingDeletion {
                    visibleEntries.removeAll { $0.id == entry.id }
                    onDelete(entry)
                }
                pendingDeletion = nil
            }
        } message: {
            if let entry = pendingDeletion {
                Text("Remove \(weightHistoryFormatter.string(from: entry.date))'s measurements?")
            }
        }
    }

    private func summary(_ m: BodyMeasurement) -> String {
        let sites = measurementSites(m).map { "\($0.label) \(displayLength($0.cm, useMetric: useMetric))" }
        if let bf = m.usNavyBodyFatPercent(gender: gender, heightCm: heightCm) {
            return (sites + [String(format: "BF %.0f%%", bf)]).joined(separator: " · ")
        }
        return sites.joined(separator: " · ")
    }
}
