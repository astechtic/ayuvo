import SwiftUI

/// Summary tab (docs/ui-structure.md §2): Eat · Move · Drink rings, Insights, today's cards, Favourites,
/// Highlights and the "Get More From Ayuvo" checklist. Every card is real data or hidden.
struct SummaryView: View {
    @Environment(AppNavigator.self) private var navigator
    @Environment(MedicationStore.self) private var medicationStore
    @Environment(RecordsStore.self) private var recordsStore
    @Environment(HealthDataStore.self) private var healthDataStore

    var body: some View {
        @Bindable var navigator = navigator
        NavigationStack(path: $navigator.summaryPath) {
            ScrollView {
                // Eager stack: the Summary is short, and keeping every card realized means
                // scrolling never drops a card out of the accessibility tree.
                VStack(alignment: .leading, spacing: 20) {
                    HStack {
                        Text(Date.now, format: .dateTime.weekday(.wide).day().month(.wide))
                            .font(.system(.subheadline, design: .rounded))
                            .foregroundStyle(.secondary)
                        Spacer()
                        AyuvoPrivacyPill(opensPrivacyPage: true)
                    }
                    .padding(.horizontal, 4)

                    SummaryRingsCard()
                    InsightsSummarySection()
                    SummaryTodaySection()
                    SummaryFavouritesSection()
                    SummaryHighlightsSection()
                    SummaryChecklistCard()
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .ayuvoScreenBackground()
            .navigationTitle("Summary")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    SummaryLogMenu()
                }
            }
            .metricRouteDestinations()
            .healthRouteDestinations()
            .insightsRouteDestinations()
            .task {
                if !recordsStore.hasLoadedOnce { await recordsStore.reload() }
                // The medications card needs counts, and the card itself is hidden until they load.
                _ = await medicationStore.openIfNeeded()
                if !medicationStore.hasLoadedOnce {
                    await medicationStore.materializeMissedAndCompletions()
                    await medicationStore.reload()
                }
            }
            .task(id: healthDataStore.snapshotRevision) {
                if healthDataStore.typeSummaries.isEmpty {
                    await healthDataStore.refreshSnapshots()
                }
            }
        }
    }
}

#if DEBUG
#Preview {
    SummaryView()
        .environment(AppNavigator())
        .ayuvoPreviewEnvironment()
}
#endif
