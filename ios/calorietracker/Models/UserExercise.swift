import Foundation

enum UserExercise {
    static let idPrefix = "user_exercise_"

    static func newID() -> String { "\(idPrefix)\(UUID().uuidString)" }

    static func isUserExercise(_ id: String) -> Bool { id.hasPrefix(idPrefix) }

    static func photoFilename(forExerciseID id: String) -> String {
        let uuid = id.replacingOccurrences(of: idPrefix, with: "")
        return "exercise_\(uuid).jpg"
    }

    /// Fresh on-disk name for a new/replaced custom exercise photo.
    static func newPhotoFilename() -> String { "exercise_\(UUID().uuidString).jpg" }

    static func isUserPhotoFilename(_ filename: String) -> Bool {
        filename.hasPrefix("exercise_") && filename.hasSuffix(".jpg")
    }
}

struct UserExerciseDraft: Equatable {
    var name: String = ""
    var instructions: String = ""
    var bodyPart: String = "Upper Arms"
    var rawEquipment: String = "Unspecified"
    var primaryMuscles: [String] = []
    var secondaryMuscles: [String] = []
    var photoData: Data?
    var removePhoto = false

    static let fallbackBodyParts = [
        "Back", "Cardio", "Chest", "Lower Arms", "Lower Legs", "Neck", "Shoulders", "Upper Arms", "Upper Legs", "Waist"
    ]

    var trimmedName: String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var instructionLines: [String] {
        instructions
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    func libraryItem(id: String, imagePaths: [String]) -> ExerciseLibraryItem {
        ExerciseLibraryItem(
            id: id,
            name: trimmedName,
            bodyPart: bodyPart,
            imagePaths: imagePaths,
            rawEquipment: rawEquipment,
            primaryMuscles: primaryMuscles,
            secondaryMuscles: secondaryMuscles,
            instructions: instructionLines
        )
    }
}
