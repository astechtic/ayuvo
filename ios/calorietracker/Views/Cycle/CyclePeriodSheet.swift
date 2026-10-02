import SwiftUI

/// Add or edit one period (docs/cycle-tracking.md §5): start and end (no future days, end ≥ start), "Still going",
/// the live duration, and the engine's validation. An overlap offers Merge (one period over both) or Adjust.
struct CyclePeriodSheet: View {
    /// nil = a new period.
    var period: CyclePeriodRecord?
    var onSaved: (() -> Void)?
    @Environment(\.dismiss) private var dismiss
    @State private var store = CycleStore.shared

    @State private var start = Date()
    @State private var end = Date()
    @State private var ongoing = false
    @State private var errors: [String] = []
    @State private var showOverlap = false
    @State private var confirmDelete = false
    @State private var loaded = false

    private var today: Date { CycleDates.date(store.today) }
    private var startDay: String { CycleDates.string(start) }
    private var endDay: String? { ongoing ? nil : CycleDates.string(end) }

    private var duration: Int? {
        guard let endDay, let s = CycleDay.ordinal(startDay), let e = CycleDay.ordinal(endDay), e >= s else { return nil }
        return e - s + 1
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker("Started", selection: $start, in: ...today, displayedComponents: .date)
                        .accessibilityIdentifier("cycle.period.start")
                    Toggle("Still going", isOn: $ongoing)
                        .tint(CycleStyle.period)
                        .accessibilityIdentifier("cycle.period.ongoing")
                    if !ongoing {
                        DatePicker("Ended", selection: $end, in: start...max(start, today), displayedComponents: .date)
                            .accessibilityIdentifier("cycle.period.end")
                    }
                } footer: {
                    if let duration {
                        Text("Duration: \(duration) days")
                            .accessibilityIdentifier("cycle.period.duration")
                    }
                }
                if !errors.isEmpty {
                    Section {
                        ForEach(errors, id: \.self) { code in
                            Label(message(code), systemImage: "exclamationmark.triangle.fill")
                                .foregroundStyle(.red)
                        }
                    }
                }
                if let period {
                    Section {
                        Button("Delete Period", role: .destructive) { confirmDelete = true }
                            .accessibilityIdentifier("cycle.period.delete")
                    } footer: {
                        if period.syncState == CycleSyncState.failed {
                            Text("Not synced with Apple Health yet. Ayuvo will try again.")
                        }
                    }
                }
            }
            .navigationTitle(period == nil ? Text("Log Period") : Text("Edit Period"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }
                        .accessibilityIdentifier("cycle.period.save")
                }
            }
            .onAppear(perform: load)
            .onChange(of: start) { _, value in
                if end < value { end = value }
                errors = []
            }
            .onChange(of: end) { _, _ in errors = [] }
            .confirmationDialog("This period overlaps another one", isPresented: $showOverlap, titleVisibility: .visible) {
                Button("Merge into one period") { Task { await merge() } }
                Button("Adjust dates", role: .cancel) {}
            } message: {
                Text("Merge them into one period, or change the dates so they don't overlap.")
            }
            .confirmationDialog("Delete this period?", isPresented: $confirmDelete, titleVisibility: .visible) {
                Button("Delete", role: .destructive) {
                    Task {
                        if let period { await store.deletePeriod(id: period.id) }
                        onSaved?()
                        dismiss()
                    }
                }
            }
        }
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        if let period {
            start = CycleDates.date(period.startDay)
            ongoing = period.endDay == nil
            end = period.endDay.map { CycleDates.date($0) } ?? today
        } else {
            start = today
            end = today
            ongoing = true
        }
    }

    private func save() async {
        let codes = await store.savePeriod(id: period?.id, start: startDay, end: endDay)
        if codes.isEmpty {
            onSaved?()
            dismiss()
        } else if codes == ["overlap"] {
            showOverlap = true
        } else {
            errors = codes
        }
    }

    private func merge() async {
        let codes = await store.mergeSave(id: period?.id, start: startDay, end: endDay)
        if codes.isEmpty {
            onSaved?()
            dismiss()
        } else {
            errors = codes
        }
    }

    private func message(_ code: String) -> String {
        switch code {
        case "future_start": String(localized: "The start can't be in the future.")
        case "future_end": String(localized: "The end can't be in the future.")
        case "end_before_start": String(localized: "The end must be on or after the start.")
        case "too_long": String(localized: "A period can be at most \(CycleConfig.shared.limits.periodMax) days. Check the dates.")
        case "open_not_latest": String(localized: "Only the most recent period can still be going. Add an end date.")
        case "overlap": String(localized: "This period overlaps another one.")
        default: String(localized: "Couldn't save this period.")
        }
    }
}
