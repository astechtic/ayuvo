import SwiftUI

/// Favourite metric tiles (two columns, 7-day sparkline) with an Edit link to the pins editor.
struct SummaryFavouritesSection: View {
    @Environment(AppNavigator.self) private var navigator
    @Environment(HealthDataStore.self) private var healthStore
    @Environment(FoodStore.self) private var foodStore
    @Environment(WaterStore.self) private var waterStore
    @Environment(FastingStore.self) private var fastingStore
    @Environment(WeightStore.self) private var weightStore
    @Environment(BodyFatStore.self) private var bodyFatStore
    @Environment(StrengthWorkoutStore.self) private var workoutStore
    @Environment(ImportedHealthWorkoutStore.self) private var importedWorkoutStore
    @Environment(ProfileStore.self) private var profileStore

    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    private var sources: MetricDataSources {
        MetricDataSources(
            food: foodStore, water: waterStore, fasting: fastingStore, weight: weightStore, bodyFat: bodyFatStore,
            workouts: workoutStore, importedWorkouts: importedWorkoutStore, health: healthStore, profile: profileStore
        )
    }

    private var tiles: [MetricTileModel] {
        MetricTileBuilder.tiles(pinIDs: healthStore.pinnedTypeIDs, sources: sources)
    }

    var body: some View {
        let tiles = tiles
        VStack(alignment: .leading, spacing: 12) {
            AyuvoSectionHeader("Favourites") {
                NavigationLink(value: MetricRoute.favourites) {
                    Text("Edit")
                }
                .accessibilityIdentifier("summary.favourites.edit")
            }
            if tiles.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("No favourites yet")
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    Text("Pick the metrics you want on Summary.")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                    NavigationLink(value: MetricRoute.favourites) {
                        Text("Add favourites")
                    }
                }
                .ayuvoCard()
            } else {
                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(tiles) { tile in
                        if let key = MetricKey(pinID: tile.key) {
                            NavigationLink(value: MetricRoute.detail(key)) {
                                MetricTile(model: tile)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("summary.favourite.\(tile.key)")
                        }
                    }
                }
            }
        }
        .accessibilityIdentifier("summary.favourites")
    }
}
