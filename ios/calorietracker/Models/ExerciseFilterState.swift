import Foundation

struct ExerciseFilterState: Codable, Equatable {
    var searchText = ""
    var splitIdentifier: String?
    var splitGroups: Set<String> = []
    var rawEquipment: Set<String> = []
    var primaryMuscles: Set<String> = []
    var secondaryMuscles: Set<String> = []
    var bodyParts: Set<String> = []
    var sort: ExerciseLibrarySort = .name

    init(
        searchText: String = "",
        splitIdentifier: String? = nil,
        splitGroups: Set<String> = [],
        rawEquipment: Set<String> = [],
        primaryMuscles: Set<String> = [],
        secondaryMuscles: Set<String> = [],
        bodyParts: Set<String> = [],
        sort: ExerciseLibrarySort = .name
    ) {
        self.searchText = searchText
        self.splitIdentifier = splitIdentifier
        self.splitGroups = splitGroups
        self.rawEquipment = rawEquipment
        self.primaryMuscles = primaryMuscles
        self.secondaryMuscles = secondaryMuscles
        self.bodyParts = bodyParts
        self.sort = sort
    }

    private enum CodingKeys: String, CodingKey {
        case searchText
        case splitIdentifier
        case splitGroups
        case rawEquipment
        case primaryMuscles
        case secondaryMuscles
        case bodyParts
        case sort
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        searchText = try container.decodeIfPresent(String.self, forKey: .searchText) ?? ""
        splitIdentifier = try container.decodeIfPresent(String.self, forKey: .splitIdentifier)
        splitGroups = try container.decodeIfPresent(Set<String>.self, forKey: .splitGroups) ?? []
        rawEquipment = try container.decodeIfPresent(Set<String>.self, forKey: .rawEquipment) ?? []
        primaryMuscles = try container.decodeIfPresent(Set<String>.self, forKey: .primaryMuscles) ?? []
        secondaryMuscles = try container.decodeIfPresent(Set<String>.self, forKey: .secondaryMuscles) ?? []
        bodyParts = try container.decodeIfPresent(Set<String>.self, forKey: .bodyParts) ?? []
        // An unknown sort (e.g. from a future build) falls back to name instead of dropping the state.
        sort = (try? container.decodeIfPresent(ExerciseLibrarySort.self, forKey: .sort)) ?? .name
    }
}

enum ExerciseFilterStateStore {
    /// v2: filter vocabulary of the exercises-dataset catalogue. v1 values referenced the
    /// retired free-exercise-db metadata and are discarded by `removeLegacyState()`.
    static let workoutsKey = "ayuvo.workouts.filterState.v2"
    static let pickerKeyPrefix = "ayuvo.workouts.picker.filter.v2."

    private static let legacyWorkoutsKey = "ayuvo.workouts.filterState"
    private static let legacyPickerKeyPrefix = "ayuvo.workouts.picker.filter.v1."

    static func load(key: String) -> ExerciseFilterState {
        guard let data = UserDefaults.standard.data(forKey: key),
              let state = try? JSONDecoder().decode(ExerciseFilterState.self, from: data) else {
            return ExerciseFilterState()
        }
        return state
    }

    static func save(_ state: ExerciseFilterState, key: String) {
        guard let data = try? JSONEncoder().encode(state) else { return }
        UserDefaults.standard.set(data, forKey: key)
    }

    static func removeLegacyState(defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: legacyWorkoutsKey)
        for key in defaults.dictionaryRepresentation().keys where key.hasPrefix(legacyPickerKeyPrefix) {
            defaults.removeObject(forKey: key)
        }
    }
}
