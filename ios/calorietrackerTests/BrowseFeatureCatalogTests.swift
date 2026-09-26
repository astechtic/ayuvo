import Foundation
import SwiftUI
import Testing
import UIKit
@testable import calorietracker

/// Browse search "Go to" features: the table and its synonym matching.
struct BrowseFeatureCatalogTests {
    private func ids(_ query: String) -> [String] {
        BrowseFeatureCatalog.search(query).map(\.id)
    }

    @Test func idsMatchTheAndroidTable() {
        #expect(BrowseFeatureCatalog.all.map(\.id) == [
            "workouts", "workoutLog", "exerciseLibrary", "nutrition", "logFood", "water", "fasting",
            "bodyMeasurements", "logWeight", "logBodyFat", "medications", "addMedication", "records",
            "addRecord", "coach", "settings", "insights", "recovery", "healthAge", "dailyReview", "patterns",
        ])
        #expect(BrowseFeatureCatalog.all.first?.accessibilityID == "browse.feature.workouts")
    }

    @Test func everyFeatureSymbolExists() {
        for feature in BrowseFeatureCatalog.all {
            #expect(UIImage(systemName: feature.systemImage) != nil, "\(feature.id) uses missing symbol \(feature.systemImage)")
        }
    }

    @Test func emptyQueryHasNoResults() {
        #expect(ids("").isEmpty)
        #expect(ids("   ").isEmpty)
    }

    @Test func workoutFindsLogAndLibrary() {
        let found = ids("workout")
        #expect(found.prefix(2) == ["workouts", "workoutLog"])
        #expect(found.contains("exerciseLibrary"))
        #expect(!found.contains("nutrition"))
    }

    @Test func synonymsMatch() {
        #expect(ids("gym").contains("workouts"))
        #expect(ids("gym").contains("exerciseLibrary"))
        #expect(ids("exercise").first == "exerciseLibrary")
        #expect(ids("meds").prefix(2) == ["medications", "addMedication"])
        #expect(ids("pills").contains("medications"))
        #expect(ids("report").prefix(2) == ["records", "addRecord"])
        #expect(ids("lab").contains("records"))
        #expect(ids("meal").contains("nutrition"))
        #expect(ids("meal").contains("logFood"))
        #expect(ids("diary").contains("nutrition"))
        #expect(ids("food").prefix(2) == ["nutrition", "logFood"])
        #expect(ids("hydration") == ["water"])
        #expect(ids("waist") == ["bodyMeasurements"])
        #expect(ids("chat") == ["coach"])
        #expect(ids("api key") == ["settings"])
    }

    @Test func titleMatchesRankFirst() {
        #expect(ids("log weight").first == "logWeight")
        #expect(ids("body fat").first == "logBodyFat")
        #expect(ids("add medication").first == "addMedication")
        #expect(ids("add record").first == "addRecord")
        #expect(ids("fasting").first == "fasting")
    }

    @Test func matchingIsCaseAndDiacriticInsensitive() {
        #expect(ids("WORKOUTS").first == "workouts")
        #expect(ids("Médications").first == "medications")
    }

    @Test func everyWordMustMatch() {
        #expect(ids("workout xyz").isEmpty)
        #expect(ids("zzzz").isEmpty)
    }

    @Test func destinationsOpenTheExpectedPlaces() {
        let byID = Dictionary(uniqueKeysWithValues: BrowseFeatureCatalog.all.map { ($0.id, $0.destination) })
        #expect(byID["workouts"] == .browse([.activity, .workouts]))
        #expect(byID["exerciseLibrary"] == .browse([.activity, .exerciseLibrary]))
        #expect(byID["bodyMeasurements"] == .browse([.body, .bodyMeasurements]))
        #expect(byID["water"] == .metric(.app(.water)))
        #expect(byID["addRecord"] == .addRecord)
        #expect(byID["addMedication"] == .addMedication)
    }

    @MainActor
    @Test func workoutLoggingRequestsThePicker() {
        let navigator = AppNavigator()
        navigator.openWorkoutLogging()
        #expect(navigator.selectedTab == .browse)
        #expect(navigator.browsePath.count == 2)
        #expect(navigator.workoutLogSession.addExerciseRequested)
    }
}
