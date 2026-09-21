import SwiftUI

/// Browse › Body: what's logged in Ayuvo first, then Apple Health body types
/// (weight and body fat are hidden there because the app metrics already include them).
struct BodyCategoryView: View {
    @Environment(HealthDataStore.self) private var store
    @Environment(ProfileStore.self) private var profileStore

    private var healthTypes: [HealthMetricType] {
        store.types(in: .body).filter { !MetricCatalog.descriptor(for: .health($0.id)).browseHidden }
    }

    var body: some View {
        List {
            Section("Logged in Ayuvo") {
                NavigationLink(value: MetricRoute.detail(.app(.weight))) {
                    AppMetricLinkRow(metric: .weight)
                }
                .accessibilityIdentifier("browse.metric.app:weight")
                NavigationLink(value: MetricRoute.detail(.app(.bodyFat))) {
                    AppMetricLinkRow(metric: .bodyFat)
                }
                .accessibilityIdentifier("browse.metric.app:body_fat")
                NavigationLink {
                    BodyMeasurementsDetailView(gender: profileStore.profile.gender, heightCm: profileStore.profile.heightCm)
                } label: {
                    MetricRow(systemImage: "ruler", tint: AyuvoPalette.body, title: String(localized: "Body Measurements"))
                }
                .accessibilityIdentifier("browse.link.bodyMeasurements")
            }

            HealthTypeSections(types: healthTypes)
        }
        .listStyle(.insetGrouped)
        .navigationTitle(BrowseCategory.body.title)
        .navigationBarTitleDisplayMode(.large)
    }
}

/// Browse › Activity: workout log, library and trends, then Apple Health activity types.
struct ActivityCategoryView: View {
    @Environment(HealthDataStore.self) private var store

    var body: some View {
        List {
            Section("Workouts") {
                NavigationLink(value: BrowseRoute.workouts) {
                    MetricRow(systemImage: "figure.strengthtraining.traditional", tint: AyuvoPalette.activity, title: String(localized: "Workouts"), subtitle: String(localized: "Log sets, reps and burn"))
                }
                .accessibilityIdentifier("browse.link.workouts")
                NavigationLink(value: BrowseRoute.exerciseLibrary) {
                    MetricRow(systemImage: "dumbbell.fill", tint: AyuvoPalette.activity, title: String(localized: "Exercise Library"))
                }
                .accessibilityIdentifier("browse.link.exerciseLibrary")
                ForEach([AppMetric.workouts, .workoutMinutes, .workoutBurn], id: \.self) { metric in
                    NavigationLink(value: MetricRoute.detail(.app(metric))) {
                        // "Workouts" already names the log link above.
                        AppMetricLinkRow(metric: metric, title: metric == .workouts ? String(localized: "Workout Count") : nil)
                    }
                    .accessibilityIdentifier("browse.metric.\(metric.key)")
                }
            }

            HealthTypeSections(types: store.types(in: .activity))
        }
        .listStyle(.insetGrouped)
        .navigationTitle(BrowseCategory.activity.title)
        .navigationBarTitleDisplayMode(.large)
    }
}

/// Apple Health rows: types with data, then a dimmed "No data yet" group.
struct HealthTypeSections: View {
    let types: [HealthMetricType]
    @Environment(HealthDataStore.self) private var store

    var body: some View {
        let withData = types.filter { (store.summary(for: $0.id)?.count ?? 0) > 0 }
        let withoutData = types.filter { (store.summary(for: $0.id)?.count ?? 0) == 0 }
        if !withData.isEmpty {
            Section("Apple Health") {
                ForEach(withData) { type in
                    NavigationLink(value: HealthRoute.metric(type.id)) {
                        HealthMetricRow(type: type, summary: store.summary(for: type.id))
                    }
                    .accessibilityIdentifier("browse.metric.\(type.id)")
                }
            }
        }
        if !withoutData.isEmpty {
            Section("No data yet") {
                ForEach(withoutData) { type in
                    NavigationLink(value: HealthRoute.metric(type.id)) {
                        HealthMetricRow(type: type, summary: nil)
                    }
                    .opacity(0.6)
                }
            }
        }
    }
}
