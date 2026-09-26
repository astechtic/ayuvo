import AppIntents
import Foundation

struct LogFoodIntent: AppIntent {
    static let title: LocalizedStringResource = "Log Food"
    static let description = IntentDescription(
        "Log a food entry in Ayuvo by describing what you ate.",
        categoryName: "Nutrition"
    )
    static let openAppWhenRun = false

    @Parameter(
        title: "Food",
        description: "What you ate, for example 100g chicken breast or two eggs and toast.",
        requestValueDialog: "What did you eat?"
    )
    var foodDescription: String

    static var parameterSummary: some ParameterSummary {
        Summary("Log \(\.$foodDescription)")
    }

    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        // `nutrition.food.log` from a description: the estimate comes from your AI provider, so the
        // catalog asks for confirmation first (docs/actions.md).
        let result = try await runAction("nutrition.food.log", ["description": .string(foodDescription)])
        return .result(dialog: IntentDialog(stringLiteral: result.dialog))
    }
}

/// Type name kept so saved shortcuts keep working; it now returns the full Nutrition Details summary.
struct CalorieSummaryIntent: AppIntent {
    static let title: LocalizedStringResource = "Today's Nutrition"
    static let description = IntentDescription(
        "Everything in Nutrition Details for today: calories, protein, carbs and fat with your targets, water, and every detailed nutrient such as fiber, sugar, sodium and vitamins.",
        categoryName: "Nutrition"
    )
    static let openAppWhenRun = false

    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<NutritionSummaryEntity> & ProvidesDialog {
        let result = try await runAction("nutrition.summary.get", ["range": .string("today")])
        return .result(
            value: NutritionSummaryEntity(range: "today", fields: result.fields),
            dialog: IntentDialog(stringLiteral: result.dialog)
        )
    }
}

struct LogWeightIntent: AppIntent {
    static let title: LocalizedStringResource = "Log Weight"
    static let description = IntentDescription(
        "Log your current weight in Ayuvo.",
        categoryName: "Body Metrics"
    )
    static let openAppWhenRun = false

    @Parameter(
        title: "Weight",
        description: "Your weight, for example 75 kilograms or 165 pounds.",
        requestValueDialog: "What is your weight?"
    )
    var weightDescription: String

    static var parameterSummary: some ParameterSummary {
        Summary("Log weight \(\.$weightDescription)")
    }

    static let authenticationPolicy: IntentAuthenticationPolicy = .requiresLocalDeviceAuthentication

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let parsed = try SpokenWeight.parse(weightDescription, preferred: ActionExecutor.shared.env.massUnit)
        let result = try await runAction("weight.log", ["value": .number(parsed.value), "unit": .string(parsed.unit)])
        return .result(dialog: IntentDialog(stringLiteral: result.dialog))
    }
}

/// "75 kilograms", "165 lbs", "70" (the user's unit) → catalog `weight.log` parameters.
enum SpokenWeight {
    static func parse(_ description: String, preferred: String) throws -> (value: Double, unit: String) {
        let normalized = description.lowercased().replacingOccurrences(of: ",", with: ".")
        guard let numberRange = normalized.range(of: #"[-+]?[0-9]*\.?[0-9]+"#, options: .regularExpression),
              let value = Double(normalized[numberRange]) else {
            throw ActionError.invalid(code: "missing_param", param: "value")
        }
        let pounds = ["lb", "pound"].contains { normalized.contains($0) }
        let kilograms = ["kg", "kilo"].contains { normalized.contains($0) }
        let unit = pounds ? "lb" : (kilograms ? "kg" : preferred)
        return (value, unit)
    }
}

@MainActor
private func performQuickAction(slot: Int) -> some IntentResult {
    QuickActionCoordinator.request(QuickActionSettings.action(for: slot))
    return .result()
}

struct QuickActionOneIntent: AppIntent {
    static let title: LocalizedStringResource = "Quick Action 1"
    static let description = IntentDescription("Open your first configurable Ayuvo quick action.")
    static let openAppWhenRun = true
    @MainActor func perform() async throws -> some IntentResult { performQuickAction(slot: 0) }
}

struct QuickActionTwoIntent: AppIntent {
    static let title: LocalizedStringResource = "Quick Action 2"
    static let description = IntentDescription("Open your second configurable Ayuvo quick action.")
    static let openAppWhenRun = true
    @MainActor func perform() async throws -> some IntentResult { performQuickAction(slot: 1) }
}

struct QuickActionThreeIntent: AppIntent {
    static let title: LocalizedStringResource = "Quick Action 3"
    static let description = IntentDescription("Open your third configurable Ayuvo quick action.")
    static let openAppWhenRun = true
    @MainActor func perform() async throws -> some IntentResult { performQuickAction(slot: 2) }
}

/// Siri phrases (Apple allows at most 10 App Shortcuts; every other catalog action is in the
/// Shortcuts app and can be run by voice through the shortcut's name). Phrases never contain values.
struct AyuvoShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: CalorieSummaryIntent(),
            phrases: [
                "Calories today in \(.applicationName)",
                "How many calories in \(.applicationName)",
                "Today's nutrition in \(.applicationName)",
                "Show my health summary in \(.applicationName)",
                "Nutrition summary in \(.applicationName)",
            ],
            shortTitle: "Today's Nutrition",
            systemImageName: "chart.bar.fill"
        )

        AppShortcut(
            intent: GetNutrientIntent(),
            phrases: [
                "How much \(\.$nutrient) today in \(.applicationName)",
                "Get my \(\.$nutrient) in \(.applicationName)",
            ],
            shortTitle: "One Nutrient",
            systemImageName: "fork.knife"
        )

        AppShortcut(
            intent: LogFoodIntent(),
            phrases: [
                "Log food in \(.applicationName)",
                "Add food in \(.applicationName)",
                "Track food in \(.applicationName)",
            ],
            shortTitle: "Log Food",
            systemImageName: "fork.knife"
        )

        AppShortcut(
            intent: LogWaterIntent(),
            phrases: [
                "Log water in \(.applicationName)",
                "Add water in \(.applicationName)",
                "I drank water \(.applicationName)",
            ],
            shortTitle: "Log Water",
            systemImageName: "drop.fill"
        )

        AppShortcut(
            intent: LogWeightIntent(),
            phrases: [
                "Log my weight in \(.applicationName)",
                "Record weight in \(.applicationName)",
            ],
            shortTitle: "Log Weight",
            systemImageName: "scalemass.fill"
        )

        AppShortcut(
            intent: StartFastIntent(),
            phrases: [
                "Start a fast in \(.applicationName)",
                "Start fasting in \(.applicationName)",
            ],
            shortTitle: "Start Fast",
            systemImageName: "timer"
        )

        AppShortcut(
            intent: OpenAyuvoSectionIntent(),
            phrases: [
                "Open \(\.$section) in \(.applicationName)",
                "Show \(\.$section) in \(.applicationName)",
            ],
            shortTitle: "Open Ayuvo",
            systemImageName: "square.grid.2x2"
        )

        AppShortcut(
            intent: QuickActionOneIntent(),
            phrases: ["Quick action one in \(.applicationName)"],
            shortTitle: "Quick Action 1",
            systemImageName: "1.circle.fill"
        )

        AppShortcut(
            intent: QuickActionTwoIntent(),
            phrases: ["Quick action two in \(.applicationName)"],
            shortTitle: "Quick Action 2",
            systemImageName: "2.circle.fill"
        )

        AppShortcut(
            intent: QuickActionThreeIntent(),
            phrases: ["Quick action three in \(.applicationName)"],
            shortTitle: "Quick Action 3",
            systemImageName: "3.circle.fill"
        )
    }
}
