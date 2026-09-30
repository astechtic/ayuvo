import Foundation

enum MuscleGroup: String, CaseIterable, Identifiable, Codable, Hashable {
    case chest = "Chest"
    case back = "Back"
    case legs = "Legs"
    case shoulders = "Shoulders"
    case arms = "Arms"
    case core = "Core"
    case fullBody = "Full Body"

    var id: String { rawValue }
    var title: String { rawValue }
    /// Localized name for display; `rawValue`/`title` stay the stored English value.
    var displayTitle: String { ExerciseTermText.muscle(rawValue) }

    var icon: String {
        switch self {
        case .chest: return "figure.strengthtraining.traditional"
        case .back: return "figure.pullup"
        case .legs: return "figure.run"
        case .shoulders: return "figure.strengthtraining.functional"
        case .arms: return "dumbbell.fill"
        case .core: return "figure.core.training"
        case .fullBody: return "figure.highintensity.intervaltraining"
        }
    }
}

enum Equipment: String, CaseIterable, Identifiable, Codable, Hashable {
    case dumbbells = "Dumbbells"
    case barbell = "Barbell"
    case cableMachine = "Cable Machine"
    case smithMachine = "Smith Machine"
    case bench = "Bench"
    case chestPress = "Chest Press"
    case shoulderPress = "Shoulder Press"
    case latPulldown = "Lat Pulldown"
    case rowMachine = "Row Machine"
    case legPress = "Leg Press"
    case legExtension = "Leg Extension"
    case legCurl = "Leg Curl"
    case pullUpBar = "Pull-up Bar"
    case treadmill = "Treadmill"
    case bodyweight = "Bodyweight"

    var id: String { rawValue }
    var title: String { rawValue }
    /// Localized name for display; `rawValue`/`title` stay the stored English value.
    var displayTitle: String { ExerciseTermText.equipment(rawValue) }

    var icon: String {
        switch self {
        case .dumbbells: return "dumbbell.fill"
        case .barbell: return "scalemass.fill"
        case .cableMachine: return "point.3.connected.trianglepath.dotted"
        case .smithMachine: return "rectangle.connected.to.line.below"
        case .bench: return "rectangle.and.hand.point.up.left"
        case .chestPress: return "figure.strengthtraining.traditional"
        case .shoulderPress: return "figure.strengthtraining.functional"
        case .latPulldown: return "figure.pullup"
        case .rowMachine: return "figure.rower"
        case .legPress: return "figure.run"
        case .legExtension: return "figure.kickboxing"
        case .legCurl: return "figure.flexibility"
        case .pullUpBar: return "figure.pullup"
        case .treadmill: return "figure.run.treadmill"
        case .bodyweight: return "figure.cooldown"
        }
    }
}

/// Display names for the exercise catalogue's English metadata (muscles, equipment, body parts) and the
/// preference enums. Stored values, filters, search and `MuscleGlyphKind` keep the English raw values.
nonisolated enum ExerciseTermText {
    static func muscle(_ raw: String) -> String {
        switch raw {
        case "Abductors": String(localized: "Abductors", comment: "Exercise muscle group")
        case "Abs": String(localized: "Abs", comment: "Exercise muscle group")
        case "Adductors": String(localized: "Adductors", comment: "Exercise muscle group")
        case "Ankle Stabilizers": String(localized: "Ankle Stabilizers", comment: "Exercise muscle group")
        case "Ankles": String(localized: "Ankles", comment: "Exercise muscle group")
        case "Arms": String(localized: "Arms", comment: "Exercise muscle group")
        case "Back": String(localized: "Back", comment: "Exercise muscle group")
        case "Biceps": String(localized: "Biceps", comment: "Exercise muscle group")
        case "Brachialis": String(localized: "Brachialis", comment: "Exercise muscle group")
        case "Calves": String(localized: "Calves", comment: "Exercise muscle group")
        case "Cardiovascular System": String(localized: "Cardiovascular System", comment: "Exercise muscle group")
        case "Chest": String(localized: "Chest", comment: "Exercise muscle group")
        case "Core": String(localized: "muscle.core", defaultValue: "Core", comment: "Exercise muscle group (abdominal/trunk muscles), not the sleep stage")
        case "Delts": String(localized: "Delts", comment: "Exercise muscle group")
        case "Feet": String(localized: "Feet", comment: "Exercise muscle group")
        case "Forearms": String(localized: "Forearms", comment: "Exercise muscle group")
        case "Full Body": String(localized: "Full Body", comment: "Exercise muscle group")
        case "Glutes": String(localized: "Glutes", comment: "Exercise muscle group")
        case "Grip Muscles": String(localized: "Grip Muscles", comment: "Exercise muscle group")
        case "Groin": String(localized: "Groin", comment: "Exercise muscle group")
        case "Hamstrings": String(localized: "Hamstrings", comment: "Exercise muscle group")
        case "Hands": String(localized: "Hands", comment: "Exercise muscle group")
        case "Hip Flexors": String(localized: "Hip Flexors", comment: "Exercise muscle group")
        case "Inner Thighs": String(localized: "Inner Thighs", comment: "Exercise muscle group")
        case "Lats": String(localized: "Lats", comment: "Exercise muscle group")
        case "Legs": String(localized: "Legs", comment: "Exercise muscle group")
        case "Levator Scapulae": String(localized: "Levator Scapulae", comment: "Exercise muscle group")
        case "Lower Abs": String(localized: "Lower Abs", comment: "Exercise muscle group")
        case "Lower Back": String(localized: "Lower Back", comment: "Exercise muscle group")
        case "Obliques": String(localized: "Obliques", comment: "Exercise muscle group")
        case "Pectorals": String(localized: "Pectorals", comment: "Exercise muscle group")
        case "Quads": String(localized: "Quads", comment: "Exercise muscle group")
        case "Rear Deltoids": String(localized: "Rear Deltoids", comment: "Exercise muscle group")
        case "Rhomboids": String(localized: "Rhomboids", comment: "Exercise muscle group")
        case "Rotator Cuff": String(localized: "Rotator Cuff", comment: "Exercise muscle group")
        case "Serratus Anterior": String(localized: "Serratus Anterior", comment: "Exercise muscle group")
        case "Shins": String(localized: "Shins", comment: "Exercise muscle group")
        case "Shoulders": String(localized: "Shoulders", comment: "Exercise muscle group")
        case "Soleus": String(localized: "Soleus", comment: "Exercise muscle group")
        case "Spine": String(localized: "Spine", comment: "Exercise muscle group")
        case "Sternocleidomastoid": String(localized: "Sternocleidomastoid", comment: "Exercise muscle group")
        case "Traps": String(localized: "Traps", comment: "Exercise muscle group")
        case "Triceps": String(localized: "Triceps", comment: "Exercise muscle group")
        case "Upper Back": String(localized: "Upper Back", comment: "Exercise muscle group")
        case "Upper Chest": String(localized: "Upper Chest", comment: "Exercise muscle group")
        case "Wrist Extensors": String(localized: "Wrist Extensors", comment: "Exercise muscle group")
        case "Wrist Flexors": String(localized: "Wrist Flexors", comment: "Exercise muscle group")
        case "Wrists": String(localized: "Wrists", comment: "Exercise muscle group")
        case "Unspecified": String(localized: "Unspecified", comment: "Exercise has no listed muscles")
        default: raw
        }
    }

    static func muscles(_ raws: [String]) -> String {
        raws.isEmpty ? String(localized: "Unspecified", comment: "Exercise has no listed muscles") : raws.map(muscle).joined(separator: ", ")
    }

    static func equipment(_ raw: String) -> String {
        switch raw {
        case "Assisted": String(localized: "Assisted", comment: "Exercise equipment")
        case "Band": String(localized: "Band", comment: "Exercise equipment")
        case "Barbell": String(localized: "Barbell", comment: "Exercise equipment")
        case "Bench": String(localized: "Bench", comment: "Exercise equipment")
        case "Body Weight": String(localized: "Body Weight", comment: "Exercise equipment")
        case "Bodyweight": String(localized: "Bodyweight", comment: "Exercise equipment")
        case "Bosu Ball": String(localized: "Bosu Ball", comment: "Exercise equipment")
        case "Cable": String(localized: "Cable", comment: "Exercise equipment")
        case "Cable Machine": String(localized: "Cable Machine", comment: "Exercise equipment")
        case "Chest Press": String(localized: "Chest Press", comment: "Exercise equipment")
        case "Dumbbell": String(localized: "Dumbbell", comment: "Exercise equipment")
        case "Dumbbells": String(localized: "Dumbbells", comment: "Exercise equipment")
        case "Elliptical Machine": String(localized: "Elliptical Machine", comment: "Exercise equipment")
        case "Ez Barbell": String(localized: "EZ Barbell", comment: "Exercise equipment")
        case "Hammer": String(localized: "Hammer", comment: "Exercise equipment")
        case "Kettlebell": String(localized: "Kettlebell", comment: "Exercise equipment")
        case "Lat Pulldown": String(localized: "Lat Pulldown", comment: "Exercise equipment")
        case "Leg Curl": String(localized: "Leg Curl", comment: "Exercise equipment")
        case "Leg Extension": String(localized: "Leg Extension", comment: "Exercise equipment")
        case "Leg Press": String(localized: "Leg Press", comment: "Exercise equipment")
        case "Leverage Machine": String(localized: "Leverage Machine", comment: "Exercise equipment")
        case "Medicine Ball": String(localized: "Medicine Ball", comment: "Exercise equipment")
        case "Olympic Barbell": String(localized: "Olympic Barbell", comment: "Exercise equipment")
        case "Pull-up Bar": String(localized: "Pull-up Bar", comment: "Exercise equipment")
        case "Resistance Band": String(localized: "Resistance Band", comment: "Exercise equipment")
        case "Roller": String(localized: "Roller", comment: "Exercise equipment")
        case "Rope": String(localized: "Rope", comment: "Exercise equipment")
        case "Row Machine": String(localized: "Row Machine", comment: "Exercise equipment")
        case "Shoulder Press": String(localized: "Shoulder Press", comment: "Exercise equipment")
        case "Skierg Machine": String(localized: "SkiErg Machine", comment: "Exercise equipment")
        case "Sled Machine": String(localized: "Sled Machine", comment: "Exercise equipment")
        case "Smith Machine": String(localized: "Smith Machine", comment: "Exercise equipment")
        case "Stability Ball": String(localized: "Stability Ball", comment: "Exercise equipment")
        case "Stationary Bike": String(localized: "Stationary Bike", comment: "Exercise equipment")
        case "Stepmill Machine": String(localized: "Stepmill Machine", comment: "Exercise equipment")
        case "Tire": String(localized: "Tire", comment: "Exercise equipment")
        case "Trap Bar": String(localized: "Trap Bar", comment: "Exercise equipment")
        case "Treadmill": String(localized: "Treadmill", comment: "Exercise equipment")
        case "Upper Body Ergometer": String(localized: "Upper Body Ergometer", comment: "Exercise equipment")
        case "Weighted": String(localized: "Weighted", comment: "Exercise equipment")
        case "Wheel Roller": String(localized: "Wheel Roller", comment: "Exercise equipment")
        case "Unspecified": String(localized: "Unspecified", comment: "Exercise has no listed equipment")
        default: raw
        }
    }

    static func bodyPart(_ raw: String) -> String {
        switch raw {
        case "Back": String(localized: "Back", comment: "Exercise body region")
        case "Cardio": String(localized: "Cardio", comment: "Exercise body region")
        case "Chest": String(localized: "Chest", comment: "Exercise body region")
        case "Lower Arms": String(localized: "Lower Arms", comment: "Exercise body region")
        case "Lower Legs": String(localized: "Lower Legs", comment: "Exercise body region")
        case "Neck": String(localized: "Neck", comment: "Exercise body region")
        case "Shoulders": String(localized: "Shoulders", comment: "Exercise body region")
        case "Upper Arms": String(localized: "Upper Arms", comment: "Exercise body region")
        case "Upper Legs": String(localized: "Upper Legs", comment: "Exercise body region")
        case "Waist": String(localized: "Waist", comment: "Exercise body region")
        case "Unspecified": String(localized: "Unspecified", comment: "Exercise has no listed body region")
        default: raw
        }
    }
}
