import SwiftUI

/// Summary "Today" card: "Cycle day N · estimated period in X days". Shown only when cycle tracking is on and set up.
struct CycleSummaryCard: View {
    @Environment(AppNavigator.self) private var navigator
    @AppStorage(CycleSettings.enabledKey) private var enabled = true
    @State private var store = CycleStore.shared

    var body: some View {
        // A container that always exists, so the task loads the store even while the card is hidden.
        VStack(spacing: 0) {
            if enabled, store.isSetUp, let text = subtitle {
                Button {
                    navigator.openBrowse([.cycle])
                } label: {
                    tile(text)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("summary.card.cycle")
            }
        }
        .task {
            guard enabled else { return }
            if CycleRuntime.shared.databaseExists { await store.refreshIfDayChanged() }
        }
    }

    /// "Cycle day 12" large, the rest of the status ("estimated period in 9 days") under it.
    private func tile(_ text: String) -> some View {
        let day = store.snapshot?.today.cycleDay.map { String(localized: "Cycle day \($0)") }
        var detail: String? = text
        if let day, text.hasPrefix(day) {
            let rest = text.dropFirst(day.count).trimmingCharacters(in: CharacterSet(charactersIn: " ·"))
            detail = rest.isEmpty ? nil : rest
        }
        return SummaryTile(
            title: String(localized: "Period tracker"),
            systemImage: "calendar.circle.fill",
            tint: CycleStyle.period,
            trailing: String(localized: "Today"),
            value: day ?? "",
            detail: day == nil ? text : detail
        )
    }

    /// Status for the card and the Browse row.
    var subtitle: String? { Self.subtitle(store) }

    @MainActor
    static func subtitle(_ store: CycleStore) -> String? {
        guard let snapshot = store.snapshot, snapshot.prediction.basis != "none", let cycleDay = snapshot.today.cycleDay else { return nil }
        let p = snapshot.prediction
        let dayText = String(localized: "Cycle day \(cycleDay)")
        if p.ongoing { return String(localized: "\(dayText) · period", comment: "Summary card: cycle day while a period is ongoing") }
        if p.lateDays > 0 { return String(localized: "\(dayText) · period \(p.lateDays) days later than estimated") }
        guard let next = p.nextStart else { return dayText }
        let days = CycleDay.o(next) - CycleDay.o(store.today)
        if days <= 1 { return String(localized: "\(dayText) · estimated period soon") }
        return String(localized: "\(dayText) · estimated period in \(days) days")
    }
}
