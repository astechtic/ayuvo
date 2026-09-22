import AppIntents
import Foundation

// Widget option ids shared by the app and the widget extension (docs/widgets.md,
// shared/widgets/widget_options.json). This file has a byte-identical copy at
// FudAIWidgets/Shared/WidgetOptions.swift; `WidgetSharedCopiesTests` fails when they differ.

/// One Quick Log widget slot. Raw values are the shared action ids.
nonisolated enum QuickLogAction: String, CaseIterable, Codable, Sendable {
    case foodMenu = "food.menu"
    case foodCamera = "food.camera"
    case foodPhotos = "food.photos"
    case foodBarcode = "food.barcode"
    case foodVoice = "food.voice"
    case foodText = "food.text"
    case foodManual = "food.manual"
    case foodFavorites = "food.favorites"
    case foodRecent = "food.recent"
    case foodFrequent = "food.frequent"
    case foodCopyFromDay = "food.copy_from_day"
    case water
    case fasting
    case weight
    case bodyFat = "body_fat"
    case workout
    case medication
    case record

    static let defaults: [QuickLogAction] = [.foodCamera, .water, .weight, .workout]

    /// The slot's action, or that slot's default for an unknown / missing id.
    static func resolve(_ raw: String?, slot: Int) -> QuickLogAction {
        if let raw, let action = QuickLogAction(rawValue: raw) { return action }
        return defaults[min(max(slot, 0), defaults.count - 1)]
    }

    /// `FoodLogMethod` raw value for `food.<method>`; nil for `food.menu` and non-food actions.
    var foodMethodRaw: String? {
        guard rawValue.hasPrefix("food."), self != .foodMenu else { return nil }
        return String(rawValue.dropFirst("food.".count))
    }

    var isFood: Bool { rawValue.hasPrefix("food.") }

    var group: String {
        switch self {
        case .water, .fasting: "nutrition"
        case .weight, .bodyFat: "body"
        case .workout: "activity"
        case .medication, .record: "more"
        default: "food"
        }
    }

    var title: String {
        switch self {
        case .foodMenu: String(localized: "Log Food")
        case .foodCamera: String(localized: "Camera")
        case .foodPhotos: String(localized: "Photos")
        case .foodBarcode: String(localized: "Barcode")
        case .foodVoice: String(localized: "Voice")
        case .foodText: String(localized: "Text")
        case .foodManual: String(localized: "Manual")
        case .foodFavorites: String(localized: "Favorites")
        case .foodRecent: String(localized: "Recent")
        case .foodFrequent: String(localized: "Frequent")
        case .foodCopyFromDay: String(localized: "Copy Day")
        case .water: String(localized: "Water")
        case .fasting: String(localized: "Fasting")
        case .weight: String(localized: "Weight")
        case .bodyFat: String(localized: "Body Fat")
        case .workout: String(localized: "Workout")
        case .medication: String(localized: "Log Dose")
        case .record: String(localized: "Add Record")
        }
    }

    var systemImage: String {
        switch self {
        case .foodMenu: "fork.knife"
        case .foodCamera: "camera.fill"
        case .foodPhotos: "photo.on.rectangle"
        case .foodBarcode: "barcode.viewfinder"
        case .foodVoice: "mic.fill"
        case .foodText: "character.cursor.ibeam"
        case .foodManual: "square.and.pencil"
        case .foodFavorites: "heart.fill"
        case .foodRecent: "clock.fill"
        case .foodFrequent: "repeat"
        case .foodCopyFromDay: "calendar"
        case .water: "drop.fill"
        case .fasting: "timer"
        case .weight: "scalemass"
        case .bodyFat: "percent"
        case .workout: "figure.strengthtraining.traditional"
        case .medication: "pills.fill"
        case .record: "doc.text.fill"
        }
    }

    /// Domain colour of the action's tile (docs/ui-structure.md palette).
    var tintHex: String {
        switch self {
        case .water: "#007AFF"
        case .fasting: "#00C7BE"
        case .weight, .bodyFat: "#AF52DE"
        case .workout: "#FF9500"
        case .medication: "#32ADE6"
        case .record: "#5856D6"
        default: "#34C759"
        }
    }

    var url: URL { URL(string: "ayuvo://log/\(rawValue)")! }
}

/// One My Metrics widget slot. Raw values are metric catalog keys (plus `medications:next_dose`).
nonisolated enum WidgetMetricOption: String, CaseIterable, Codable, Sendable {
    case calories = "app:calories"
    case protein = "app:protein"
    case carbs = "app:carbs"
    case fat = "app:fat"
    case fiber = "app:fiber"
    case water = "app:water"
    case weight = "app:weight"
    case bodyFat = "app:body_fat"
    case fasting = "app:fasting"
    case workouts = "app:workouts"
    case workoutBurn = "app:workout_burn"
    case steps
    case activeEnergy = "active_energy"
    case sleep
    case heartRate = "heart_rate"
    case nextDose = "medications:next_dose"

    static let defaults: [WidgetMetricOption] = [.calories, .steps, .water, .weight]

    static func resolve(_ raw: String?, slot: Int) -> WidgetMetricOption {
        if let raw, let option = WidgetMetricOption(rawValue: raw) { return option }
        return defaults[min(max(slot, 0), defaults.count - 1)]
    }

    var title: String {
        switch self {
        case .calories: String(localized: "Calories")
        case .protein: String(localized: "Protein")
        case .carbs: String(localized: "Carbs")
        case .fat: String(localized: "Fat")
        case .fiber: String(localized: "Fiber")
        case .water: String(localized: "Water")
        case .weight: String(localized: "Weight")
        case .bodyFat: String(localized: "Body Fat")
        case .fasting: String(localized: "Fasting")
        case .workouts: String(localized: "Workouts")
        case .workoutBurn: String(localized: "Workout Burn")
        case .steps: String(localized: "Steps")
        case .activeEnergy: String(localized: "Active Energy")
        case .sleep: String(localized: "Sleep")
        case .heartRate: String(localized: "Heart Rate")
        case .nextDose: String(localized: "Next Dose")
        }
    }

    var systemImage: String {
        switch self {
        case .calories: "flame.fill"
        case .protein: "fork.knife"
        case .carbs: "leaf.fill"
        case .fat: "drop.halffull"
        case .fiber: "leaf"
        case .water: "drop.fill"
        case .weight: "scalemass"
        case .bodyFat: "percent"
        case .fasting: "timer"
        case .workouts: "figure.strengthtraining.traditional"
        case .workoutBurn: "flame"
        case .steps: "figure.walk"
        case .activeEnergy: "flame.fill"
        case .sleep: "bed.double.fill"
        case .heartRate: "heart.fill"
        case .nextDose: "pills.fill"
        }
    }

    var url: URL { URL(string: "ayuvo://metric/\(rawValue)")! }
}

/// Where a widget deep link lands (`ayuvo://log/<id>`, `ayuvo://metric/<key>`, `ayuvo://summary`).
nonisolated enum WidgetDeepLink: Equatable, Sendable {
    case log(QuickLogAction)
    case metric(String)
    case summary

    static let summaryURL = URL(string: "ayuvo://summary")!

    init?(url: URL) {
        guard url.scheme == "ayuvo" else { return nil }
        let value = url.path.hasPrefix("/") ? String(url.path.dropFirst()) : url.path
        switch url.host {
        case "log":
            guard let action = QuickLogAction(rawValue: value) else { return nil }
            self = .log(action)
        case "metric":
            guard !value.isEmpty else { return nil }
            self = .metric(value)
        case "summary":
            self = .summary
        default:
            return nil
        }
    }

    /// Storage form for the pending-route keys.
    var storageValue: String {
        switch self {
        case .log(let action): "log:\(action.rawValue)"
        case .metric(let key): "metric:\(key)"
        case .summary: "summary"
        }
    }

    init?(storageValue: String) {
        if storageValue == "summary" {
            self = .summary
        } else if storageValue.hasPrefix("log:"), let action = QuickLogAction(rawValue: String(storageValue.dropFirst(4))) {
            self = .log(action)
        } else if storageValue.hasPrefix("metric:"), storageValue.count > 7 {
            self = .metric(String(storageValue.dropFirst(7)))
        } else {
            return nil
        }
    }
}

extension Notification.Name {
    /// A widget route is pending (`WidgetRouteCoordinator`); `ContentView` consumes it.
    static let widgetRouteRequested = Notification.Name("Ayuvo.widgetRouteRequested")
}

/// Pending widget route handed from the small Quick Log widget's button intent to the app
/// through the App Group (the intent may run in the widget process).
enum WidgetPendingRoute {
    static let key = "widget.route.pending"

    static func write(_ link: WidgetDeepLink) {
        WidgetSnapshot.sharedDefaults?.set(link.storageValue, forKey: key)
    }

    static func consume() -> WidgetDeepLink? {
        guard let defaults = WidgetSnapshot.sharedDefaults,
              let raw = defaults.string(forKey: key) else { return nil }
        defaults.removeObject(forKey: key)
        return WidgetDeepLink(storageValue: raw)
    }
}

extension QuickLogAction: AppEnum {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Log Action"

    static let caseDisplayRepresentations: [QuickLogAction: DisplayRepresentation] = [
        .foodMenu: DisplayRepresentation(title: "Log Food", image: .init(systemName: "fork.knife")),
        .foodCamera: DisplayRepresentation(title: "Camera", image: .init(systemName: "camera.fill")),
        .foodPhotos: DisplayRepresentation(title: "Photos", image: .init(systemName: "photo.on.rectangle")),
        .foodBarcode: DisplayRepresentation(title: "Barcode", image: .init(systemName: "barcode.viewfinder")),
        .foodVoice: DisplayRepresentation(title: "Voice", image: .init(systemName: "mic.fill")),
        .foodText: DisplayRepresentation(title: "Text", image: .init(systemName: "character.cursor.ibeam")),
        .foodManual: DisplayRepresentation(title: "Manual", image: .init(systemName: "square.and.pencil")),
        .foodFavorites: DisplayRepresentation(title: "Favorites", image: .init(systemName: "heart.fill")),
        .foodRecent: DisplayRepresentation(title: "Recent", image: .init(systemName: "clock.fill")),
        .foodFrequent: DisplayRepresentation(title: "Frequent", image: .init(systemName: "repeat")),
        .foodCopyFromDay: DisplayRepresentation(title: "Copy Day", image: .init(systemName: "calendar")),
        .water: DisplayRepresentation(title: "Water", image: .init(systemName: "drop.fill")),
        .fasting: DisplayRepresentation(title: "Fasting", image: .init(systemName: "timer")),
        .weight: DisplayRepresentation(title: "Weight", image: .init(systemName: "scalemass")),
        .bodyFat: DisplayRepresentation(title: "Body Fat", image: .init(systemName: "percent")),
        .workout: DisplayRepresentation(title: "Workout", image: .init(systemName: "figure.strengthtraining.traditional")),
        .medication: DisplayRepresentation(title: "Log Dose", image: .init(systemName: "pills.fill")),
        .record: DisplayRepresentation(title: "Add Record", image: .init(systemName: "doc.text.fill")),
    ]
}

extension WidgetMetricOption: AppEnum {
    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Metric"

    static let caseDisplayRepresentations: [WidgetMetricOption: DisplayRepresentation] = [
        .calories: DisplayRepresentation(title: "Calories", image: .init(systemName: "flame.fill")),
        .protein: DisplayRepresentation(title: "Protein", image: .init(systemName: "fork.knife")),
        .carbs: DisplayRepresentation(title: "Carbs", image: .init(systemName: "leaf.fill")),
        .fat: DisplayRepresentation(title: "Fat", image: .init(systemName: "drop.halffull")),
        .fiber: DisplayRepresentation(title: "Fiber", image: .init(systemName: "leaf")),
        .water: DisplayRepresentation(title: "Water", image: .init(systemName: "drop.fill")),
        .weight: DisplayRepresentation(title: "Weight", image: .init(systemName: "scalemass")),
        .bodyFat: DisplayRepresentation(title: "Body Fat", image: .init(systemName: "percent")),
        .fasting: DisplayRepresentation(title: "Fasting", image: .init(systemName: "timer")),
        .workouts: DisplayRepresentation(title: "Workouts", image: .init(systemName: "figure.strengthtraining.traditional")),
        .workoutBurn: DisplayRepresentation(title: "Workout Burn", image: .init(systemName: "flame")),
        .steps: DisplayRepresentation(title: "Steps", image: .init(systemName: "figure.walk")),
        .activeEnergy: DisplayRepresentation(title: "Active Energy", image: .init(systemName: "flame.fill")),
        .sleep: DisplayRepresentation(title: "Sleep", image: .init(systemName: "bed.double.fill")),
        .heartRate: DisplayRepresentation(title: "Heart Rate", image: .init(systemName: "heart.fill")),
        .nextDose: DisplayRepresentation(title: "Next Dose", image: .init(systemName: "pills.fill")),
    ]
}

/// Quick Log small-widget button: opens Ayuvo at the chosen action, like the Summary "+" menu.
struct OpenLogEntryIntent: AppIntent {
    static var title: LocalizedStringResource = "Open Log Action"
    static var openAppWhenRun = true
    static var isDiscoverable = false

    @Parameter(title: "Action", default: .foodCamera)
    var action: QuickLogAction

    init() {}

    init(action: QuickLogAction) {
        self.action = action
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        WidgetPendingRoute.write(.log(action))
        NotificationCenter.default.post(name: .widgetRouteRequested, object: nil)
        return .result()
    }
}
