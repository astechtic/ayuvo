import SwiftUI

/// Daily log for one day (docs/cycle-tracking.md §5): period-day toggle, flow, pain and where, symptoms, moods, a note,
/// and read-only Apple Health values. Future days show their estimate only.
struct CycleDaySheet: View {
    let day: String
    @Environment(\.dismiss) private var dismiss
    @State private var store = CycleStore.shared

    @State private var isPeriodDay = false
    @State private var flow: String?
    @State private var painLogged = false
    @State private var pain: Double = 0
    @State private var painLocations: Set<String> = []
    @State private var symptoms: Set<String> = []
    @State private var moods: Set<String> = []
    @State private var note = ""
    @State private var healthLines: [String] = []
    @State private var loaded = false
    @State private var saving = false
    @State private var errorText: String?

    private let config = CycleConfig.shared

    private var isFuture: Bool { CycleDay.o(day) > CycleDay.o(store.today) }
    private var status: CycleDayStatus? { store.status(day) }

    /// The day is inside a period read from Apple Health (not editable here).
    private var inHealthPeriod: Bool {
        guard let p = store.period(containing: day) else { return false }
        return p.source != "app"
    }

    private var initialPeriodDay: Bool { status?.phase == "period" }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    statusRow
                }
                if isFuture {
                    Section {
                        Text("You can log this day once it arrives.")
                            .foregroundStyle(.secondary)
                    }
                } else {
                    periodSection
                    flowSection
                    painSection
                    symptomSection
                    moodSection
                    noteSection
                    if !healthLines.isEmpty {
                        Section {
                            ForEach(healthLines, id: \.self) { Text($0) }
                        } header: {
                            Text("From Apple Health")
                        } footer: {
                            Text("Read-only here. Edit these in the Health app.")
                        }
                    }
                }
                if let errorText {
                    Section { Text(errorText).foregroundStyle(.red) }
                }
            }
            .navigationTitle(Text(verbatim: CycleDates.long(day)))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                if !isFuture {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") { Task { await save() } }
                            .disabled(saving)
                            .accessibilityIdentifier("cycle.day.save")
                    }
                }
            }
            .task {
                guard !loaded else { return }
                loaded = true
                load()
                healthLines = await store.healthLines(day: day)
            }
        }
    }

    // MARK: Sections

    private var statusRow: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let status, status.phase != "unknown", let cycleDay = status.cycleDay {
                let phase = (!store.showFertility && (status.phase == "fertile" || status.phase == "ovulation")) ? "unknown" : status.phase
                Text("Cycle day \(cycleDay)")
                    .font(.system(.headline, design: .rounded))
                if phase != "unknown" {
                    Text(CycleText.phase(phase))
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            } else {
                Text("No estimate for this day")
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var periodSection: some View {
        Section {
            Toggle(isOn: Binding(get: { isPeriodDay }, set: { on in
                isPeriodDay = on
                if !on, let f = flow, config.flowLevel(f)?.period == true { flow = nil }
            })) {
                Label {
                    Text("Period day")
                } icon: {
                    Image(systemName: "drop.fill").foregroundStyle(CycleStyle.period)
                }
            }
            .tint(CycleStyle.period)
            .disabled(inHealthPeriod)
            .accessibilityIdentifier("cycle.day.periodToggle")
        } footer: {
            if inHealthPeriod {
                Text("This period comes from Apple Health. Change it in the Health app.")
            }
        }
    }

    private var flowSection: some View {
        Section("Flow") {
            CycleFlowLayout {
                ForEach(config.flowLevels) { level in
                    CycleChip(title: CycleText.flow(level.key), systemImage: CycleStyle.flowSymbol(level.key), selected: flow == level.key) {
                        if flow == level.key {
                            flow = nil
                        } else {
                            flow = level.key
                            if level.period && !inHealthPeriod { isPeriodDay = true }
                        }
                    }
                    .accessibilityIdentifier("cycle.day.flow.\(level.key)")
                }
            }
            .padding(.vertical, 4)
        }
    }

    private var painSection: some View {
        Section {
            Toggle("Log pain", isOn: $painLogged)
                .tint(CycleStyle.period)
                .accessibilityIdentifier("cycle.day.painToggle")
            if painLogged {
                VStack(alignment: .leading) {
                    HStack {
                        Text("Pain")
                        Spacer()
                        Text("\(Int(pain)) / 10")
                            .font(.system(.body, design: .rounded, weight: .semibold))
                            .monospacedDigit()
                    }
                    Slider(value: $pain, in: 0...Double(config.limits.painMax), step: 1) {
                        Text("Pain")
                    } minimumValueLabel: {
                        Text("0")
                    } maximumValueLabel: {
                        Text("10")
                    }
                    .tint(CycleStyle.period)
                    .accessibilityValue(Text("\(Int(pain)) out of 10"))
                    .accessibilityIdentifier("cycle.day.pain")
                    HStack {
                        Text("No pain")
                        Spacer()
                        Text("Severe")
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
                CycleFlowLayout {
                    ForEach(config.painLocations) { location in
                        CycleChip(title: CycleText.painLocation(location.key), selected: painLocations.contains(location.key)) {
                            toggle(&painLocations, location.key)
                        }
                    }
                }
                .padding(.vertical, 4)
            }
        } header: {
            Text("Pain")
        }
    }

    private var symptomSection: some View {
        ForEach(config.symptomGroups) { group in
            Section(CycleText.symptomGroup(group.key)) {
                CycleFlowLayout {
                    ForEach(config.symptoms.filter { $0.group == group.key }) { symptom in
                        CycleChip(title: CycleText.symptom(symptom.key), selected: symptoms.contains(symptom.key), tint: AyuvoPalette.symptoms) {
                            toggle(&symptoms, symptom.key)
                        }
                        .accessibilityIdentifier("cycle.day.symptom.\(symptom.key)")
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }

    private var moodSection: some View {
        Section("Mood") {
            CycleFlowLayout {
                ForEach(config.moods) { mood in
                    CycleChip(title: CycleText.mood(mood.key), selected: moods.contains(mood.key), tint: AyuvoPalette.mindfulness) {
                        toggle(&moods, mood.key)
                    }
                    .accessibilityIdentifier("cycle.day.mood.\(mood.key)")
                }
            }
            .padding(.vertical, 4)
        }
    }

    private var noteSection: some View {
        Section {
            TextField("Add a note", text: $note, axis: .vertical)
                .lineLimit(2...6)
                .onChange(of: note) { _, value in
                    if value.count > config.limits.noteMaxChars { note = String(value.prefix(config.limits.noteMaxChars)) }
                }
                .accessibilityIdentifier("cycle.day.note")
        } header: {
            Text("Note")
        } footer: {
            Text("Notes stay on this device. They are never shared with Coach.")
        }
    }

    // MARK: Load and save

    private func toggle(_ set: inout Set<String>, _ key: String) {
        if set.contains(key) { set.remove(key) } else { set.insert(key) }
    }

    private func load() {
        isPeriodDay = initialPeriodDay
        guard let log = store.dayLog(day) else { return }
        flow = log.flow
        if let p = log.pain {
            painLogged = true
            pain = Double(p)
        }
        painLocations = Set(log.painLocations)
        symptoms = Set(log.symptoms)
        moods = Set(log.moods)
        note = log.note ?? ""
    }

    /// Catalogue order, so stored lists are stable.
    private func ordered(_ set: Set<String>, _ keys: [String]) -> [String] {
        keys.filter(set.contains) + set.filter { !keys.contains($0) }.sorted()
    }

    private func save() async {
        saving = true
        defer { saving = false }
        if !inHealthPeriod && isPeriodDay != initialPeriodDay {
            if let error = await store.setPeriodDay(day, on: isPeriodDay) {
                errorText = String(localized: "Couldn't update the period (\(error)).")
                return
            }
        }
        let log = CycleDayLogRecord(
            day: day, flow: flow, pain: painLogged ? Int(pain) : nil,
            painLocations: painLogged ? ordered(painLocations, config.painLocations.map(\.key)) : [],
            symptoms: ordered(symptoms, config.symptoms.map(\.key)), moods: ordered(moods, config.moods.map(\.key)),
            note: note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : note, updatedMs: 0)
        if store.dayLog(day) != nil || !log.isEmpty {
            if let error = await store.saveDayLog(log) {
                errorText = String(localized: "Couldn't save this day (\(error)).")
                return
            }
        }
        dismiss()
    }
}
