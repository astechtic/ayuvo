import SwiftUI

/// Pushed screens under Browse › Cycle tracking.
enum CycleRoute: Hashable {
    case calendar, history, insights
    /// One cycle by its first day ('yyyy-MM-dd').
    case cycle(String)
}

extension View {
    func cycleRouteDestinations() -> some View {
        navigationDestination(for: CycleRoute.self) { route in
            switch route {
            case .calendar: CycleCalendarView()
            case .history: CycleHistoryView()
            case .insights: CycleInsightsView()
            case .cycle(let start): CycleDetailView(start: start)
            }
        }
    }
}

/// A selected day for the day sheet.
struct CycleSelectedDay: Identifiable, Hashable {
    let day: String
    var id: String { day }
}

/// Calendar screen: one month at a time (swipe or chevrons), Today, the legend and the day sheet on tap.
struct CycleCalendarView: View {
    @State private var store = CycleStore.shared
    @State private var month = CycleDay.today()
    @State private var selected: CycleSelectedDay?

    private var monthStart: Int { CycleDay.o(String(CycleDay.string(month).prefix(8)) + "01") }

    private var monthTitle: String {
        CycleDates.date(ordinal: monthStart).formatted(.dateTime.month(.wide).year())
    }

    private func shift(_ months: Int) {
        let date = CycleDates.date(ordinal: monthStart)
        if let next = Calendar.current.date(byAdding: .month, value: months, to: date) {
            month = CycleDay.today(next)
        }
    }

    var body: some View {
        let todayN = CycleDay.o(store.today)
        let statuses = store.statuses(from: monthStart - 7, to: monthStart + 42)
        let logged = Set(store.logs.keys.compactMap(CycleDay.ordinal))
        var flows: [Int: String] = [:]
        for (day, log) in store.logs { if let f = log.flow, let n = CycleDay.ordinal(day) { flows[n] = f } }
        return ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Button { withAnimation { shift(-1) } } label: {
                        Image(systemName: "chevron.left").frame(width: 44, height: 44)
                    }
                    .accessibilityLabel(Text("Previous month"))
                    Spacer()
                    Text(monthTitle)
                        .font(.system(.title3, design: .rounded, weight: .bold))
                        .accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button { withAnimation { shift(1) } } label: {
                        Image(systemName: "chevron.right").frame(width: 44, height: 44)
                    }
                    .accessibilityLabel(Text("Next month"))
                }
                MonthCalendarView(month: month, statuses: statuses, today: todayN, loggedDays: logged, flows: flows,
                                  showFertility: store.showFertility) { day in
                    selected = CycleSelectedDay(day: CycleDay.string(day))
                }
                .ayuvoCard(padding: 10)
                .gesture(DragGesture(minimumDistance: 30).onEnded { value in
                    if value.translation.width < -40 { withAnimation { shift(1) } }
                    if value.translation.width > 40 { withAnimation { shift(-1) } }
                })
                CycleLegend(showFertility: store.showFertility)
                    .padding(.horizontal, 4)
                if store.showFertility {
                    Text(CycleText.fertilityNote)
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                CycleDisclaimer()
            }
            .padding(16)
        }
        .ayuvoScreenBackground()
        .navigationTitle("Calendar")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("Today") { withAnimation { month = CycleDay.today() } }
                    .accessibilityIdentifier("cycle.calendar.today")
            }
        }
        .sheet(item: $selected) { day in
            CycleDaySheet(day: day.day)
        }
        .task { await store.refreshIfDayChanged() }
    }
}
