import SwiftUI

/// Browse › Cycle tracking (docs/cycle-tracking.md §5): setup the first time, then the dashboard with the cycle ring,
/// the status line and its basis, one-tap period start/end, quick actions, notes and the disclaimer.
struct CycleHomeView: View {
    @Environment(AppNavigator.self) private var navigator
    @State private var store = CycleStore.shared
    @State private var selectedDay: CycleSelectedDay?
    @State private var showPeriodSheet = false
    @State private var working = false

    var body: some View {
        Group {
            if !store.hasLoaded {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if !store.isSetUp {
                CycleSetupView()
            } else {
                dashboard
            }
        }
        .navigationTitle("Period tracker")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            if store.isSetUp {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        navigator.openSettings(.cycleTracking)
                    } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityLabel(Text("Period tracker settings"))
                    .accessibilityIdentifier("cycle.settings")
                }
            }
        }
        .sheet(item: $selectedDay) { day in CycleDaySheet(day: day.day) }
        .sheet(isPresented: $showPeriodSheet) { CyclePeriodSheet() }
        .task { await store.refreshIfDayChanged() }
        .accessibilityIdentifier("cycle.home")
    }

    // MARK: Dashboard

    private var dashboard: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                ringCard
                actions
                if let window = upcomingWindow, store.showFertility {
                    fertilityCard(window)
                }
                notes
                CycleDisclaimer()
            }
            .padding(16)
        }
        .ayuvoScreenBackground()
        .refreshable { await store.reload() }
    }

    private var snapshot: CycleSnapshot? { store.snapshot }

    /// Phases for the current cycle, day 1 … max(estimated length, today's cycle day).
    private var ringPhases: (phases: [String], todayIndex: Int?) {
        guard let snapshot, let last = snapshot.periods.last, let length = snapshot.prediction.cycleLength else { return ([], nil) }
        let todayN = CycleDay.o(store.today)
        let n = max(length, todayN - last.start + 1)
        let statuses = store.statuses(from: last.start, to: last.start + n - 1)
        let phases = (0..<n).map { statuses[last.start + $0]?.phase ?? "unknown" }
        return (phases, todayN - last.start + 1)
    }

    private func phaseText(_ phase: String) -> String {
        if !store.showFertility && (phase == "fertile" || phase == "ovulation") { return CycleText.phase("follicular") }
        return CycleText.phase(phase)
    }

    private var ringCard: some View {
        VStack(spacing: 14) {
            if let snapshot, snapshot.prediction.basis != "none", let cycleDay = snapshot.today.cycleDay {
                let ring = ringPhases
                CycleRingView(phases: ring.phases, todayIndex: ring.todayIndex,
                              centerTitle: String(localized: "Day \(cycleDay)", comment: "Cycle ring centre: cycle day number"),
                              centerSubtitle: phaseText(snapshot.today.phase), showFertility: store.showFertility)
                    .frame(maxWidth: 280)
                    .frame(maxWidth: .infinity)
                Text(statusLine(snapshot))
                    .font(.system(.headline, design: .rounded))
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("cycle.status")
                CycleBasisBadge(basis: snapshot.prediction.basis)
            } else {
                Image(systemName: "calendar.badge.plus")
                    .font(.system(size: 40))
                    .foregroundStyle(CycleStyle.period)
                    .accessibilityHidden(true)
                Text("Log the start of a period to see estimates.")
                    .font(.system(.headline, design: .rounded))
                    .multilineTextAlignment(.center)
            }
            primaryButton
        }
        .frame(maxWidth: .infinity)
        .ayuvoCard()
    }

    private var primaryButton: some View {
        VStack(spacing: 8) {
            Button {
                Task {
                    working = true
                    if store.ongoingPeriod != nil { await store.periodEnded() } else { await store.periodStarted() }
                    working = false
                }
            } label: {
                Label(store.ongoingPeriod != nil ? String(localized: "Period ended today") : String(localized: "Period started today"),
                      systemImage: store.ongoingPeriod != nil ? "checkmark.circle.fill" : "drop.fill")
                    .font(.system(.body, design: .rounded, weight: .semibold))
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .tint(CycleStyle.period)
            .disabled(working)
            .accessibilityIdentifier("cycle.primary")
            Button("Log a different date") { showPeriodSheet = true }
                .font(.system(.subheadline, design: .rounded, weight: .medium))
                .frame(minHeight: 44)
                .accessibilityIdentifier("cycle.logPeriod")
        }
    }

    private func statusLine(_ snapshot: CycleSnapshot) -> String {
        let p = snapshot.prediction
        if p.ongoing, let day = snapshot.today.cycleDay {
            if let end = p.expectedEnd, CycleDay.o(end) >= CycleDay.o(store.today) {
                return String(localized: "Period day \(day) · may end around \(CycleDates.short(end))")
            }
            return String(localized: "Period day \(day)")
        }
        if p.lateDays > 0 {
            return String(localized: "Your period is \(p.lateDays) days later than estimated")
        }
        if snapshot.today.phase == "late" {
            return String(localized: "Period estimated around today")
        }
        guard let next = p.nextStart, let range = p.nextRange else { return "" }
        let days = CycleDay.o(next) - CycleDay.o(store.today)
        let rangeText = range[0] == range[1] ? CycleDates.short(range[0]) : "\(CycleDates.short(range[0]))–\(CycleDates.short(range[1]))"
        if days == 1 { return String(localized: "Period estimated tomorrow (\(rangeText))") }
        return String(localized: "Period estimated in \(days) days (\(rangeText))")
    }

    // MARK: Quick actions

    private var actions: some View {
        let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]
        return LazyVGrid(columns: columns, spacing: 12) {
            Button {
                selectedDay = CycleSelectedDay(day: store.today)
            } label: {
                actionTile(String(localized: "Log today"), systemImage: "square.and.pencil")
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("cycle.action.logToday")
            NavigationLink(value: CycleRoute.calendar) {
                actionTile(String(localized: "Calendar"), systemImage: "calendar")
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("cycle.action.calendar")
            NavigationLink(value: CycleRoute.history) {
                actionTile(String(localized: "History"), systemImage: "clock.arrow.circlepath")
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("cycle.action.history")
            NavigationLink(value: CycleRoute.insights) {
                actionTile(String(localized: "Insights"), systemImage: "chart.bar.xaxis")
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("cycle.action.insights")
        }
    }

    private func actionTile(_ title: String, systemImage: String) -> some View {
        HStack(spacing: 10) {
            CategoryIconView(systemImage: systemImage, tint: CycleStyle.period)
            Text(title)
                .font(.system(.body, design: .rounded, weight: .medium))
                .foregroundStyle(.primary)
            Spacer(minLength: 0)
        }
        .frame(minHeight: 44)
        .ayuvoCard(padding: 12)
    }

    // MARK: Fertility and notes

    private var upcomingWindow: CycleWindow? {
        guard let snapshot else { return nil }
        let today = CycleDay.o(store.today)
        return snapshot.prediction.windows.first { w in
            guard let fertile = w.fertile else { return false }
            return CycleDay.o(fertile[1]) >= today
        }
    }

    private func fertilityCard(_ window: CycleWindow) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            if let fertile = window.fertile {
                Label {
                    Text("\(CycleText.phase("fertile")): \(CycleDates.short(fertile[0]))–\(CycleDates.short(fertile[1]))")
                } icon: {
                    RoundedRectangle(cornerRadius: 3).fill(CycleStyle.fertile.opacity(0.4)).frame(width: 14, height: 14)
                }
                .font(.system(.subheadline, design: .rounded, weight: .medium))
            }
            if let ovulation = window.ovulation {
                Label {
                    Text("\(CycleText.phase("ovulation")): \(CycleDates.short(ovulation))")
                } icon: {
                    Circle().strokeBorder(CycleStyle.fertile, lineWidth: 2).frame(width: 14, height: 14)
                }
                .font(.system(.subheadline, design: .rounded, weight: .medium))
            }
            Text(CycleText.fertilityNote)
                .font(.system(.caption, design: .rounded))
                .foregroundStyle(.secondary)
        }
        .ayuvoCard()
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("cycle.fertility")
    }

    @ViewBuilder
    private var notes: some View {
        if let insights = snapshot?.insights, !insights.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(insights.prefix(2), id: \.self) { insight in
                    Label {
                        Text(CycleText.insight(insight))
                            .font(.system(.subheadline, design: .rounded))
                    } icon: {
                        Image(systemName: CycleText.isProfessional(insight) ? "stethoscope" : "info.circle")
                            .foregroundStyle(.secondary)
                    }
                }
                NavigationLink(value: CycleRoute.insights) {
                    Text("See insights")
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .ayuvoCard()
        }
    }
}
