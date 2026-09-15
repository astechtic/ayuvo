import Foundation

struct ExerciseLibraryItem: Identifiable, Hashable {
    let id: String
    let name: String
    /// Dataset body region ("Upper Arms", "Waist", "Cardio"); user exercises pick one too.
    let bodyPart: String
    /// Local photo filenames for user-created exercises. Catalogue exercises use `imageURL`/`gifURL`.
    let imagePaths: [String]
    let rawEquipment: String
    let primaryMuscles: [String]
    let secondaryMuscles: [String]
    let instructions: [String]
    /// Static 180×180 thumbnail (© Gym visual) shown in lists.
    let imageURL: URL?
    /// Animated demonstration (© Gym visual) shown on the detail screen.
    let gifURL: URL?
    /// Precomputed once so library filtering does not rebuild haystacks every keystroke.
    let searchableText: String

    init(
        id: String,
        name: String,
        bodyPart: String? = nil,
        imagePaths: [String] = [],
        rawEquipment: String? = nil,
        primaryMuscles: [String] = [],
        secondaryMuscles: [String] = [],
        instructions: [String] = [],
        imageURL: URL? = nil,
        gifURL: URL? = nil
    ) {
        self.id = id
        self.name = name
        self.bodyPart = Self.metadataTitle(bodyPart)
        self.imagePaths = imagePaths
        self.rawEquipment = Self.metadataTitle(rawEquipment)
        self.primaryMuscles = Self.metadataTitles(primaryMuscles)
        self.secondaryMuscles = Self.metadataTitles(secondaryMuscles)
        self.instructions = instructions.compactMap { $0.trimmed.nilIfEmpty }
        self.imageURL = imageURL
        self.gifURL = gifURL
        // Instructions are deliberately left out: matching on the exercise's identity
        // keeps results relevant and keeps each keystroke's filter pass cheap.
        self.searchableText = Self.buildSearchableText(
            name: name,
            bodyPart: self.bodyPart,
            rawEquipment: self.rawEquipment,
            primaryMuscles: self.primaryMuscles,
            secondaryMuscles: self.secondaryMuscles
        )
    }

    /// Catalogue exercises have exactly one target muscle; user exercises may list several.
    var target: String {
        primaryMuscles.first ?? "Unspecified"
    }

    var primaryMusclesTitle: String {
        primaryMuscles.isEmpty ? "Unspecified" : primaryMuscles.joined(separator: ", ")
    }

    var secondaryMusclesTitle: String {
        secondaryMuscles.isEmpty ? "None" : secondaryMuscles.joined(separator: ", ")
    }

    var isCardio: Bool { bodyPart.caseInsensitiveCompare("cardio") == .orderedSame }

    /// Media licensing attribution for catalogue exercises that carry dataset media.
    var mediaAttribution: String? {
        imageURL == nil && gifURL == nil ? nil : ExerciseCatalogLoader.mediaAttribution
    }

    nonisolated private static func buildSearchableText(
        name: String,
        bodyPart: String,
        rawEquipment: String,
        primaryMuscles: [String],
        secondaryMuscles: [String]
    ) -> String {
        [
            name,
            bodyPart,
            rawEquipment,
            primaryMuscles.joined(separator: " "),
            secondaryMuscles.joined(separator: " ")
        ]
        .joined(separator: " ")
        .lowercased()
    }

    nonisolated private static func metadataTitles(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values
            .map(metadataTitle)
            .filter { $0 != "Unspecified" && seen.insert($0).inserted }
    }

    nonisolated static func metadataTitle(_ value: String?) -> String {
        guard let value else { return "Unspecified" }

        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "Unspecified" }

        return trimmed
            .split(separator: " ")
            .map { word in
                word
                    .split(separator: "-", omittingEmptySubsequences: false)
                    .map { segment in
                        guard let first = segment.first else { return "" }
                        return first.uppercased() + segment.dropFirst().lowercased()
                    }
                    .joined(separator: "-")
            }
            .joined(separator: " ")
    }
}

enum ExerciseLibrarySort: String, CaseIterable, Identifiable, Codable, Hashable {
    case name = "Name"
    case bodyPart = "Body Part"
    case primaryMuscles = "Target"
    case secondaryMuscles = "Secondary"
    case rawEquipment = "Equipment"

    var id: String { rawValue }
    // Sort titles share catalog keys with the filter-pill titles ("Name", "Target",
    // "Secondary", ...) so the results-header subtitle localizes like the rest of the UI.
    var title: String { String(localized: String.LocalizationValue(rawValue)) }
}

private extension String {
    var trimmed: String {
        trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
