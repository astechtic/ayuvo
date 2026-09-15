import Foundation
import Testing
@testable import calorietracker

@MainActor
struct WorkoutLibraryFilterTests {
    @Test func fullBodyUsesIndividualCatalogBodyParts() {
        let groups = StrengthWorkoutSplitGroup.selectionGroups(
            for: .fullBody,
            availablePrimaryMuscles: ["Chest", "Biceps"],
            availableSecondaryMuscles: ["Triceps", "Chest"]
        )

        #expect(groups.map(\.title) == ["Biceps", "Chest", "Triceps"])
        #expect(groups.allSatisfy { $0.muscles == [$0.title] })
    }

    @Test func configuredSplitUsesItsTrainingGroups() {
        let groups = StrengthWorkoutSplitGroup.selectionGroups(
            for: .pushPullLegs,
            availablePrimaryMuscles: ["Chest", "Lats", "Quadriceps", "Abdominals"],
            availableSecondaryMuscles: ["Shoulders", "Biceps", "Hamstrings"]
        )

        #expect(groups.map(\.title) == ["Push", "Pull", "Legs", "Core"])
        #expect(groups.first(where: { $0.title == "Push" })?.muscles == ["Chest", "Shoulders"])
        #expect(groups.first(where: { $0.title == "Pull" })?.muscles == ["Biceps", "Lats"])
    }

    @Test func filterStateToleratesRetiredFieldsAndSorts() throws {
        let legacyJSON = #"{"searchText":"bench","levels":["Intermediate"],"rawEquipment":["Barbell"],"primaryMuscles":[],"secondaryMuscles":[],"forces":[],"mechanics":[],"categories":[],"sort":"Level"}"#
        let state = try JSONDecoder().decode(ExerciseFilterState.self, from: Data(legacyJSON.utf8))

        #expect(state.searchText == "bench")
        #expect(state.rawEquipment == ["Barbell"])
        #expect(state.bodyParts.isEmpty)
        #expect(state.sort == .name)
        #expect(state.splitIdentifier == nil)
        #expect(state.splitGroups.isEmpty)
    }

    @Test func splitGroupsCoverExercisesDatasetVocabulary() {
        let groups = StrengthWorkoutSplitGroup.selectionGroups(
            for: .broSplit,
            availablePrimaryMuscles: ["Pectorals", "Delts", "Upper Back", "Quads", "Abs", "Cardiovascular System"],
            availableSecondaryMuscles: ["Rear Deltoids", "Rhomboids", "Obliques"]
        )

        #expect(groups.map(\.title) == ["Chest", "Back", "Shoulders", "Legs", "Core", "Cardio"])
        #expect(groups.first(where: { $0.title == "Shoulders" })?.muscles == ["Delts", "Rear Deltoids"])
        #expect(groups.first(where: { $0.title == "Core" })?.muscles == ["Abs", "Obliques"])
    }

    @Test func muscleGlyphsCoverDatasetMuscleNames() {
        #expect(MuscleGlyphAsset.name(title: "Pectorals", muscles: ["Pectorals"]) == "muscle_icon_chest")
        #expect(MuscleGlyphAsset.name(title: "Delts", muscles: ["Delts"]) == "muscle_icon_shoulders")
        #expect(MuscleGlyphAsset.name(title: "Upper Back", muscles: ["Upper Back"]) == "muscle_icon_middle_back")
        #expect(MuscleGlyphAsset.name(title: "Abs", muscles: ["Abs"]) == "muscle_icon_abs")
        #expect(MuscleGlyphAsset.name(title: "Cardio", muscles: ["Cardiovascular System"]) == "muscle_icon_generic")
    }

    @Test func legacyFilterStateKeysAreRemoved() throws {
        let suite = "WorkoutLibraryFilterTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(Data("{}".utf8), forKey: "ayuvo.workouts.filterState")
        defaults.set(Data("{}".utf8), forKey: "ayuvo.workouts.picker.filter.v1.Chest")
        defaults.set(Data("{}".utf8), forKey: ExerciseFilterStateStore.workoutsKey)

        ExerciseFilterStateStore.removeLegacyState(defaults: defaults)

        #expect(defaults.data(forKey: "ayuvo.workouts.filterState") == nil)
        #expect(defaults.data(forKey: "ayuvo.workouts.picker.filter.v1.Chest") == nil)
        #expect(defaults.data(forKey: ExerciseFilterStateStore.workoutsKey) != nil)
    }

    @Test func filterEngineMatchesBodyPartAndSplitMuscles() {
        let exercises = [
            ExerciseLibraryItem(id: "0025", name: "Barbell Bench Press", bodyPart: "chest", rawEquipment: "barbell", primaryMuscles: ["Chest"], secondaryMuscles: ["Triceps"]),
            ExerciseLibraryItem(id: "0294", name: "Dumbbell Biceps Curl", bodyPart: "upper arms", rawEquipment: "dumbbell", primaryMuscles: ["Biceps"])
        ]
        let request = ExerciseLibraryFilterRequest(
            rawEquipment: [], primaryMuscles: [], secondaryMuscles: [], bodyParts: ["Upper Arms"],
            sort: .name, searchText: "", splitMuscles: []
        )
        #expect(ExerciseLibraryFilterEngine.filter(exercises: exercises, request: request).map(\.id) == ["0294"])

        let split = ExerciseLibraryFilterRequest(
            rawEquipment: [], primaryMuscles: [], secondaryMuscles: [], bodyParts: [],
            sort: .name, searchText: "", splitMuscles: ["Triceps"]
        )
        #expect(ExerciseLibraryFilterEngine.filter(exercises: exercises, request: split).map(\.id) == ["0025"])
    }

    @Test func filterStateRoundTripsSplitContext() throws {
        let expected = ExerciseFilterState(
            searchText: "press",
            splitIdentifier: StrengthWorkoutSplit.pushPullLegs.rawValue,
            splitGroups: ["Push"],
            bodyParts: ["Chest"],
            sort: .bodyPart
        )

        let data = try JSONEncoder().encode(expected)
        let restored = try JSONDecoder().decode(ExerciseFilterState.self, from: data)

        #expect(restored == expected)
    }
}
