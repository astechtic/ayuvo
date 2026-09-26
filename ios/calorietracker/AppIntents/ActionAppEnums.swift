import AppIntents
import Foundation

// Shortcuts pickers for the shared catalog enums (shared/actions/action_catalog.json `enums`).
// Raw values are the catalog values, so an enum value passes straight to `ActionExecutor`.

enum ActionDateRangeOption: String, AppEnum {
    case today, yesterday
    case thisWeek = "this_week"
    case lastWeek = "last_week"
    case last7Days = "last_7_days"
    case last30Days = "last_30_days"
    case thisMonth = "this_month"
    case lastMonth = "last_month"

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Date Range"
    static let caseDisplayRepresentations: [ActionDateRangeOption: DisplayRepresentation] = [
        .today: "Today", .yesterday: "Yesterday", .thisWeek: "This Week", .lastWeek: "Last Week",
        .last7Days: "Last 7 Days", .last30Days: "Last 30 Days", .thisMonth: "This Month", .lastMonth: "Last Month",
    ]
}

enum ActionAggregationOption: String, AppEnum {
    case latest, sum, average, min, max, count

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Calculation"
    static let caseDisplayRepresentations: [ActionAggregationOption: DisplayRepresentation] = [
        .latest: "Latest", .sum: "Total", .average: "Average", .min: "Minimum", .max: "Maximum", .count: "Count",
    ]
}

enum ActionNutrientOption: String, AppEnum {
    case calories, protein, carbs, fat, fiber

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Nutrient"
    static let caseDisplayRepresentations: [ActionNutrientOption: DisplayRepresentation] = [
        .calories: DisplayRepresentation(title: "Calories", image: .init(systemName: "flame.fill")),
        .protein: DisplayRepresentation(title: "Protein", image: .init(systemName: "fish.fill")),
        .carbs: DisplayRepresentation(title: "Carbs", image: .init(systemName: "carrot.fill")),
        .fat: DisplayRepresentation(title: "Fat", image: .init(systemName: "drop.triangle.fill")),
        .fiber: DisplayRepresentation(title: "Fiber", image: .init(systemName: "leaf.fill")),
    ]
}

enum ActionSectionOption: String, AppEnum {
    case summary, browse, health, nutrition, water, fasting, body, activity, workouts, records, medications, coach, settings

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Section"
    static let caseDisplayRepresentations: [ActionSectionOption: DisplayRepresentation] = [
        .summary: DisplayRepresentation(title: "Summary", image: .init(systemName: "heart.text.square")),
        .browse: DisplayRepresentation(title: "Browse", image: .init(systemName: "square.grid.2x2")),
        .health: DisplayRepresentation(title: "Health", image: .init(systemName: "heart.fill")),
        .nutrition: DisplayRepresentation(title: "Nutrition", image: .init(systemName: "fork.knife")),
        .water: DisplayRepresentation(title: "Water", image: .init(systemName: "drop.fill")),
        .fasting: DisplayRepresentation(title: "Fasting", image: .init(systemName: "timer")),
        .body: DisplayRepresentation(title: "Body Measurements", image: .init(systemName: "figure")),
        .activity: DisplayRepresentation(title: "Activity", image: .init(systemName: "flame.fill")),
        .workouts: DisplayRepresentation(title: "Workouts", image: .init(systemName: "dumbbell.fill")),
        .records: DisplayRepresentation(title: "Health Records", image: .init(systemName: "doc.text.fill")),
        .medications: DisplayRepresentation(title: "Medications", image: .init(systemName: "pills.fill")),
        .coach: DisplayRepresentation(title: "Coach", image: .init(systemName: "sparkles")),
        .settings: DisplayRepresentation(title: "Settings", image: .init(systemName: "gearshape.fill")),
    ]
}

enum ActionVolumeUnitOption: String, AppEnum {
    case ml, l, floz, cup

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Volume Unit"
    static let caseDisplayRepresentations: [ActionVolumeUnitOption: DisplayRepresentation] = [
        .ml: "Milliliters", .l: "Liters", .floz: "Fluid Ounces", .cup: "Cups",
    ]
}

enum ActionMassUnitOption: String, AppEnum {
    case kg, lb

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Weight Unit"
    static let caseDisplayRepresentations: [ActionMassUnitOption: DisplayRepresentation] = [.kg: "Kilograms", .lb: "Pounds"]
}

enum ActionLengthUnitOption: String, AppEnum {
    case cm
    case inch = "in"

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Length Unit"
    static let caseDisplayRepresentations: [ActionLengthUnitOption: DisplayRepresentation] = [.cm: "Centimeters", .inch: "Inches"]
}

enum ActionMealOption: String, AppEnum {
    case breakfast, lunch, dinner, snack, other

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Meal"
    static let caseDisplayRepresentations: [ActionMealOption: DisplayRepresentation] = [
        .breakfast: "Breakfast", .lunch: "Lunch", .dinner: "Dinner", .snack: "Snack", .other: "Other",
    ]
}

enum ActionDoseActionOption: String, AppEnum {
    case taken, skipped, snoozed

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Dose Action"
    static let caseDisplayRepresentations: [ActionDoseActionOption: DisplayRepresentation] = [
        .taken: DisplayRepresentation(title: "Taken", image: .init(systemName: "checkmark.circle")),
        .skipped: DisplayRepresentation(title: "Skipped", image: .init(systemName: "forward")),
        .snoozed: DisplayRepresentation(title: "Snoozed", image: .init(systemName: "clock")),
    ]
}

enum ActionSnoozeOption: Int, AppEnum {
    case ten = 10, thirty = 30, sixty = 60

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Snooze"
    static let caseDisplayRepresentations: [ActionSnoozeOption: DisplayRepresentation] = [
        .ten: "10 minutes", .thirty: "30 minutes", .sixty: "1 hour",
    ]
}

enum ActionGoalOption: String, AppEnum {
    case calories, protein, carbs, fat, water, steps

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Goal"
    static let caseDisplayRepresentations: [ActionGoalOption: DisplayRepresentation] = [
        .calories: "Calories (kcal)", .protein: "Protein (g)", .carbs: "Carbs (g)", .fat: "Fat (g)",
        .water: "Water (ml)", .steps: "Steps",
    ]
}

enum ActionBodySiteOption: String, AppEnum {
    case neck, waist, hips, chest
    case upperArm = "upper_arm"
    case thigh, calf, wrist

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Measurement"
    static let caseDisplayRepresentations: [ActionBodySiteOption: DisplayRepresentation] = [
        .neck: "Neck", .waist: "Waist", .hips: "Hips", .chest: "Chest", .upperArm: "Upper Arm",
        .thigh: "Thigh", .calf: "Calf", .wrist: "Wrist",
    ]
}

enum ActionSearchDomainOption: String, AppEnum {
    case all, food, exercises, metrics, records, medications

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Search In"
    static let caseDisplayRepresentations: [ActionSearchDomainOption: DisplayRepresentation] = [
        .all: "Everything", .food: "Foods", .exercises: "Exercises", .metrics: "Metrics",
        .records: "Health Records", .medications: "Medications",
    ]
}
