import SwiftUI

/// Cycle history, newest first (docs/cycle-tracking.md §5): dates, cycle length, period length and a flow strip.
struct CycleHistoryView: View {
    @State private var store = CycleStore.shared

    private var cycles: [CycleTrendCycle] { (store.trends?.cycles ?? []).reversed() }

    var body: some View {
        List {
            if cycles.isEmpty {
                EmptyStateView("No cycles yet", systemImage: "calendar",
                               description: "Log a period and it shows up here.")
                    .listRowBackground(Color.clear)
            } else {
                Section {
                    ForEach(cycles, id: \.start) { cycle in
                        NavigationLink(value: CycleRoute.cycle(cycle.start)) {
                            CycleHistoryRow(cycle: cycle, source: source(cycle.start))
                        }
                        .accessibilityIdentifier("cycle.history.row.\(cycle.start)")
                    }
                } footer: {
                    Text("Cycle length runs from the first day of one period to the day before the next. Cycles longer than \(CycleConfig.shared.limits.cycleMax) days usually mean a period wasn't logged and don't count towards averages.")
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("History")
        .navigationBarTitleDisplayMode(.inline)
        .task { await store.refreshIfDayChanged() }
    }

    private func source(_ start: String) -> String? {
        store.snapshot?.periods.first { CycleDay.string($0.start) == start }?.source
    }
}

struct CycleHistoryRow: View {
    let cycle: CycleTrendCycle
    var source: String?

    private var endDay: String? {
        guard let length = cycle.periodLength, let s = CycleDay.ordinal(cycle.start) else { return nil }
        return CycleDay.string(s + length - 1)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(CycleDates.range(cycle.start, endDay))
                    .font(.system(.body, design: .rounded, weight: .semibold))
                if source == "healthkit" || source == "health_connect" {
                    Image(systemName: "heart.fill")
                        .font(.caption2)
                        .foregroundStyle(AyuvoPalette.heart)
                        .accessibilityLabel(Text("From Apple Health"))
                }
                Spacer()
            }
            HStack(spacing: 12) {
                if let length = cycle.cycleLength {
                    Text("Cycle: \(length) days")
                } else {
                    Text("Current cycle")
                }
                if let period = cycle.periodLength {
                    Text("Period: \(period) days")
                }
            }
            .font(.system(.subheadline, design: .rounded))
            .foregroundStyle(.secondary)
            if cycle.flow.contains(where: { $0 != nil }) {
                CycleFlowStrip(flows: cycle.flow)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}

/// One cycle: its period (edit / delete when Ayuvo owns it), lengths and every logged day.
struct CycleDetailView: View {
    let start: String
    @State private var store = CycleStore.shared
    @State private var editing: CyclePeriodRecord?
    @State private var selectedDay: CycleSelectedDay?

    private var cycle: CycleTrendCycle? { store.trends?.cycles.first { $0.start == start } }
    private var normalized: CycleNormalizedPeriod? { store.snapshot?.periods.first { CycleDay.string($0.start) == start } }

    /// The app period behind this cycle (the merged period's members include it).
    private var appPeriod: CyclePeriodRecord? {
        guard let normalized else { return nil }
        return store.periods.first { normalized.members.contains($0.id) }
    }

    private var dayRange: ClosedRange<Int>? {
        guard let s = CycleDay.ordinal(start) else { return nil }
        let end = cycle?.cycleLength.map { s + $0 - 1 } ?? CycleDay.o(store.today)
        return s...max(s, end)
    }

    private var loggedDays: [CycleDayLogRecord] {
        guard let range = dayRange else { return [] }
        return store.logs.values.filter { range.contains(CycleDay.o($0.day)) }.sorted { $0.day < $1.day }
    }

    var body: some View {
        List {
            Section {
                if let cycle {
                    LabeledContent("Period", value: CycleDates.range(start, cycle.periodLength.map { CycleDay.string(CycleDay.o(start) + $0 - 1) }))
                    if let period = cycle.periodLength { LabeledContent("Period length", value: String(localized: "\(period) days")) }
                    LabeledContent("Cycle length", value: cycle.cycleLength.map { String(localized: "\($0) days") } ?? String(localized: "Current cycle"))
                    if let pain = cycle.painMax { LabeledContent("Highest pain", value: "\(pain) / 10") }
                }
                if let appPeriod {
                    Button("Edit Period") { editing = appPeriod }
                        .accessibilityIdentifier("cycle.detail.edit")
                    if appPeriod.syncState == CycleSyncState.failed {
                        Text("Not synced with Apple Health yet.").font(.caption).foregroundStyle(.secondary)
                    }
                } else if normalized?.source != "app" {
                    Text("This period comes from Apple Health. Change it in the Health app.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Section("Logged days") {
                if loggedDays.isEmpty {
                    Text("Nothing logged in this cycle.").foregroundStyle(.secondary)
                }
                ForEach(loggedDays) { log in
                    Button {
                        selectedDay = CycleSelectedDay(day: log.day)
                    } label: {
                        CycleDayLogRow(log: log, cycleDay: CycleDay.o(log.day) - CycleDay.o(start) + 1)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(Text(verbatim: CycleDates.short(start)))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $editing) { period in CyclePeriodSheet(period: period) }
        .sheet(item: $selectedDay) { day in CycleDaySheet(day: day.day) }
    }
}

/// A logged day: date, flow, pain, symptoms, moods and the start of the note.
struct CycleDayLogRow: View {
    let log: CycleDayLogRecord
    var cycleDay: Int?

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack {
                Text(verbatim: CycleDates.long(log.day))
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                if let cycleDay { Text("Day \(cycleDay)").font(.caption).foregroundStyle(.secondary) }
                Spacer()
                if let flow = log.flow {
                    Label(CycleText.flow(flow), systemImage: CycleStyle.flowSymbol(flow))
                        .font(.caption)
                        .foregroundStyle(CycleStyle.period)
                }
            }
            let details = (log.pain.map { [String(localized: "Pain \($0)/10")] } ?? [])
                + log.symptoms.map(CycleText.symptom) + log.moods.map(CycleText.mood)
            if !details.isEmpty {
                Text(details.joined(separator: " · "))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let note = log.note, !note.isEmpty {
                Text(note).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}
