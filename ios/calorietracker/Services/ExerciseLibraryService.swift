import Foundation

struct ExerciseBodyPartCount: Identifiable, Hashable {
    let bodyPart: String
    let count: Int

    var id: String { bodyPart }
}

struct ExerciseLibraryService {
    static let shared = ExerciseLibraryService()

    let exercises: [ExerciseLibraryItem]
    // Facets are computed once per catalogue snapshot; SwiftUI bodies read them on every pass.
    let availableBodyPartCounts: [ExerciseBodyPartCount]
    let availableRawEquipment: [String]
    let availablePrimaryMuscles: [String]
    let availableSecondaryMuscles: [String]

    init(exercises: [ExerciseLibraryItem]? = nil) {
        let exercises = exercises ?? ExerciseCatalogLoader.load()
        self.exercises = exercises
        availableBodyPartCounts = Dictionary(grouping: exercises, by: \.bodyPart)
            .map { ExerciseBodyPartCount(bodyPart: $0.key, count: $0.value.count) }
            .sorted { lhs, rhs in
                if lhs.count == rhs.count {
                    return lhs.bodyPart.localizedCaseInsensitiveCompare(rhs.bodyPart) == .orderedAscending
                }
                return lhs.count > rhs.count
            }
        availableRawEquipment = Self.sortedUnique(exercises.map(\.rawEquipment))
        availablePrimaryMuscles = Self.sortedUnique(exercises.flatMap(\.primaryMuscles))
        availableSecondaryMuscles = Self.sortedUnique(exercises.flatMap(\.secondaryMuscles))
    }

    func filtered(
        rawEquipment: Set<String>,
        primaryMuscles: Set<String>,
        secondaryMuscles: Set<String>,
        bodyParts: Set<String>,
        sort: ExerciseLibrarySort,
        searchText: String
    ) -> [ExerciseLibraryItem] {
        Self.filter(
            exercises,
            rawEquipment: rawEquipment,
            primaryMuscles: primaryMuscles,
            secondaryMuscles: secondaryMuscles,
            bodyParts: bodyParts,
            sort: sort,
            searchText: searchText
        )
    }

    static func filter(
        _ exercises: [ExerciseLibraryItem],
        rawEquipment: Set<String>,
        primaryMuscles: Set<String>,
        secondaryMuscles: Set<String>,
        bodyParts: Set<String>,
        sort: ExerciseLibrarySort,
        searchText: String
    ) -> [ExerciseLibraryItem] {
        let filteredItems = exercises.filter { item in
            let matchesRawEquipment = rawEquipment.isEmpty || rawEquipment.contains(item.rawEquipment)
            let matchesPrimaryMuscle = primaryMuscles.isEmpty || item.primaryMuscles.contains { primaryMuscles.contains($0) }
            let matchesSecondaryMuscle = secondaryMuscles.isEmpty || item.secondaryMuscles.contains { secondaryMuscles.contains($0) }
            let matchesBodyPart = bodyParts.isEmpty || bodyParts.contains(item.bodyPart)
            let matchesSearch = ExerciseSearchMatcher.matches(
                searchableText: item.searchableText,
                query: searchText,
                exerciseID: item.id
            )

            return matchesRawEquipment &&
                matchesPrimaryMuscle &&
                matchesSecondaryMuscle &&
                matchesBodyPart &&
                matchesSearch
        }

        return filteredItems.sorted { lhs, rhs in
            switch sort {
            case .name:
                return lhs.name < rhs.name
            case .primaryMuscles:
                return Self.compare(lhs.primaryMusclesTitle, rhs.primaryMusclesTitle, lhsName: lhs.name, rhsName: rhs.name)
            case .secondaryMuscles:
                return Self.compare(lhs.secondaryMusclesTitle, rhs.secondaryMusclesTitle, lhsName: lhs.name, rhsName: rhs.name)
            case .bodyPart:
                return Self.compare(lhs.bodyPart, rhs.bodyPart, lhsName: lhs.name, rhsName: rhs.name)
            case .rawEquipment:
                return Self.compare(lhs.rawEquipment, rhs.rawEquipment, lhsName: lhs.name, rhsName: rhs.name)
            }
        }
    }

    private static func sortedUnique(_ values: [String]) -> [String] {
        Array(Set(values.filter { !$0.isEmpty }))
            .sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending }
    }

    private static func compare(_ lhs: String, _ rhs: String, lhsName: String, rhsName: String) -> Bool {
        if lhs == rhs {
            return lhsName < rhsName
        }
        return lhs.localizedCaseInsensitiveCompare(rhs) == .orderedAscending
    }
}

struct ExerciseLibraryFilterRequest: Sendable {
    let rawEquipment: Set<String>
    let primaryMuscles: Set<String>
    let secondaryMuscles: Set<String>
    let bodyParts: Set<String>
    let sort: ExerciseLibrarySort
    let searchText: String
    let splitMuscles: Set<String>
}

enum ExerciseLibraryFilterEngine {
    static func filter(
        exercises: [ExerciseLibraryItem],
        request: ExerciseLibraryFilterRequest
    ) -> [ExerciseLibraryItem] {
        let filtered = ExerciseLibraryService.filter(
            exercises,
            rawEquipment: request.rawEquipment,
            primaryMuscles: request.primaryMuscles,
            secondaryMuscles: request.secondaryMuscles,
            bodyParts: request.bodyParts,
            sort: request.sort,
            searchText: request.searchText
        )

        guard !request.splitMuscles.isEmpty else {
            return filtered
        }

        return filtered.filter { item in
            item.primaryMuscles.contains(where: request.splitMuscles.contains) ||
                item.secondaryMuscles.contains(where: request.splitMuscles.contains)
        }
    }
}
