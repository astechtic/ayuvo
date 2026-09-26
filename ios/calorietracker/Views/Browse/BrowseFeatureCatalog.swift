import Foundation
import SwiftUI

/// Where a Browse "Go to" search result leads. `BrowseView` performs it through `AppNavigator`
/// and the stores' one-shot requests, so every result opens an existing flow.
enum BrowseFeatureDestination: Hashable {
    /// Replace the Browse stack with these places.
    case browse([BrowseRoute])
    /// Push a metric detail (trend + log) on the Browse stack.
    case metric(MetricKey)
    /// Browse › Insights, optionally with one Insights screen on top.
    case insights(InsightsRoute?)
    /// Nutrition diary with a food logging action.
    case logFood(QuickAction)
    case logWeight
    case logBodyFat
    /// Workouts diary with the exercise picker open.
    case logWorkout
    /// Medications with the Add medication form open.
    case addMedication
    case recordsTab
    /// Records tab with the Add record sheet open.
    case addRecord
    case coach
    case settings
}

/// One Browse search "Go to" result. Ids are shared with Android (`browse.feature.<id>`).
struct BrowseFeature: Identifiable, Hashable {
    let id: String
    let title: String
    let subtitle: String
    let systemImage: String
    /// Catalog domain id for the icon colour (`AyuvoPalette.domain`); nil → neutral.
    let domain: String?
    /// Lowercase English synonyms matched in addition to the (localized) title.
    let keywords: [String]
    let destination: BrowseFeatureDestination

    var tint: Color { domain.map(AyuvoPalette.domain) ?? AyuvoPalette.other }
    var accessibilityID: String { "browse.feature.\(id)" }

    /// Opens another tab (or a sheet over Browse) rather than pushing on the Browse stack.
    var leavesBrowse: Bool {
        switch destination {
        case .browse, .metric, .insights, .logFood, .logWorkout, .addMedication: false
        case .logWeight, .logBodyFat, .recordsTab, .addRecord, .coach, .settings: true
        }
    }
}

/// The feature table behind Browse search (docs/ui-structure.md §2): one place for every
/// destination, its synonyms and its target. Matching is pure so it is unit-tested.
enum BrowseFeatureCatalog {
    static var all: [BrowseFeature] {
        [
            BrowseFeature(
                id: "workouts", title: String(localized: "Workouts"),
                subtitle: String(localized: "Workout diary, sets, reps and burn"),
                systemImage: "figure.strengthtraining.traditional", domain: "activity",
                keywords: ["workout", "workouts", "gym", "exercise", "exercises", "training", "strength", "lift", "lifting",
                           "weights", "sets", "reps", "cardio", "fitness", "activity", "diary"],
                destination: .browse([.activity, .workouts])
            ),
            BrowseFeature(
                id: "workoutLog", title: String(localized: "Log a Workout"),
                subtitle: String(localized: "Start today's workout by adding exercises"),
                systemImage: "plus.circle.fill", domain: "activity",
                keywords: ["log workout", "start workout", "new workout", "add exercise", "workout", "gym", "exercise",
                           "training", "lift", "sets", "reps", "record workout"],
                destination: .logWorkout
            ),
            BrowseFeature(
                id: "exerciseLibrary", title: String(localized: "Exercise Library"),
                subtitle: String(localized: "Explore exercises with demonstrations"),
                systemImage: "dumbbell.fill", domain: "activity",
                keywords: ["exercise", "exercises", "library", "explore", "gym", "movements", "muscles", "dumbbell",
                           "barbell", "machine", "stretch", "workout"],
                destination: .browse([.activity, .exerciseLibrary])
            ),
            BrowseFeature(
                id: "nutrition", title: String(localized: "Food Diary"),
                subtitle: String(localized: "Nutrition, meals and macros by day"),
                systemImage: "fork.knife", domain: "nutrition",
                keywords: ["nutrition", "food", "foods", "meal", "meals", "diary", "calories", "kcal", "macros", "protein",
                           "carbs", "fat", "diet", "eat", "eating", "breakfast", "lunch", "dinner", "snack"],
                destination: .browse([.nutrition])
            ),
            BrowseFeature(
                id: "logFood", title: String(localized: "Log Food"),
                subtitle: String(localized: "Describe a meal to add it to today"),
                systemImage: "plus.circle.fill", domain: "nutrition",
                keywords: ["log food", "add food", "log meal", "add meal", "food", "meal", "diary", "calories", "eat",
                           "breakfast", "lunch", "dinner", "snack", "track food"],
                destination: .logFood(.text)
            ),
            BrowseFeature(
                id: "water", title: String(localized: "Water"),
                subtitle: String(localized: "Log and review your hydration"),
                systemImage: "drop.fill", domain: "hydration",
                keywords: ["water", "hydration", "drink", "drinks", "fluid", "fluids", "glass", "bottle", "ml", "oz"],
                destination: .metric(.app(.water))
            ),
            BrowseFeature(
                id: "fasting", title: String(localized: "Fasting"),
                subtitle: String(localized: "Start, end and review fasts"),
                systemImage: "timer", domain: "fasting",
                keywords: ["fasting", "fast", "fasts", "intermittent", "timer", "16:8"],
                destination: .browse([.fasting])
            ),
            BrowseFeature(
                id: "bodyMeasurements", title: String(localized: "Body Measurements"),
                subtitle: String(localized: "Waist, hips, chest and more"),
                systemImage: "ruler", domain: "body",
                keywords: ["body", "measurements", "measure", "waist", "hips", "chest", "neck", "arm", "thigh", "tape",
                           "circumference", "inches", "cm"],
                destination: .browse([.body, .bodyMeasurements])
            ),
            BrowseFeature(
                id: "logWeight", title: String(localized: "Log Weight"),
                subtitle: String(localized: "Add today's weight"),
                systemImage: "scalemass.fill", domain: "body",
                keywords: ["weight", "weigh", "scale", "kg", "lbs", "pounds", "body", "bmi", "log weight"],
                destination: .logWeight
            ),
            BrowseFeature(
                id: "logBodyFat", title: String(localized: "Log Body Fat"),
                subtitle: String(localized: "Add a body fat percentage"),
                systemImage: "percent", domain: "body",
                keywords: ["body fat", "fat", "bodyfat", "percentage", "composition", "body", "log body fat"],
                destination: .logBodyFat
            ),
            BrowseFeature(
                id: "medications", title: String(localized: "Medications"),
                subtitle: String(localized: "Today's doses, reminders and history"),
                systemImage: "pills.fill", domain: "medications",
                keywords: ["medications", "medication", "medicine", "medicines", "meds", "pills", "pill", "tablets",
                           "tablet", "doses", "dose", "reminders", "drugs", "prescription", "supplements", "vitamins"],
                destination: .browse([.medications])
            ),
            BrowseFeature(
                id: "addMedication", title: String(localized: "Add Medication"),
                subtitle: String(localized: "Add a medicine with its schedule"),
                systemImage: "plus.circle.fill", domain: "medications",
                keywords: ["add medication", "new medication", "add medicine", "medication", "medicine", "meds", "pills",
                           "pill", "tablet", "dose", "reminder", "supplement", "vitamin"],
                destination: .addMedication
            ),
            BrowseFeature(
                id: "records", title: String(localized: "Health Records"),
                subtitle: String(localized: "Lab reports, prescriptions and scans"),
                systemImage: "doc.text.fill", domain: "records",
                keywords: ["records", "record", "health records", "report", "reports", "lab", "labs", "test", "tests",
                           "blood", "prescription", "prescriptions", "scan", "scans", "document", "documents", "pdf",
                           "results", "doctor"],
                destination: .recordsTab
            ),
            BrowseFeature(
                id: "addRecord", title: String(localized: "Add Record"),
                subtitle: String(localized: "Scan, photograph or import a document"),
                systemImage: "plus.circle.fill", domain: "records",
                keywords: ["add record", "new record", "scan", "import", "upload", "record", "report", "lab",
                           "prescription", "document", "pdf", "photo"],
                destination: .addRecord
            ),
            BrowseFeature(
                id: "coach", title: String(localized: "Coach"),
                subtitle: String(localized: "Ask about your food, workouts and health"),
                systemImage: "bubble.left.and.bubble.right.fill", domain: nil,
                keywords: ["coach", "chat", "ask", "ai", "assistant", "advice", "question", "help", "talk"],
                destination: .coach
            ),
            BrowseFeature(
                id: "settings", title: String(localized: "Settings"),
                subtitle: String(localized: "Profile, goals, AI providers and backups"),
                systemImage: "gearshape.fill", domain: nil,
                keywords: ["settings", "preferences", "profile", "goals", "units", "notifications", "ai provider", "api key",
                           "backup", "export", "import", "appearance", "theme", "privacy", "account"],
                destination: .settings
            ),
            BrowseFeature(
                id: "insights", title: String(localized: "Insights"),
                subtitle: String(localized: "Recovery, Health Age, Daily Review and patterns"),
                systemImage: "gauge.with.dots.needle.67percent", domain: "insights",
                keywords: ["insights", "insight", "score", "scores", "baseline", "baselines", "trends", "readiness",
                           "wellness", "analysis"],
                destination: .insights(nil)
            ),
            BrowseFeature(
                id: "recovery", title: String(localized: "Recovery"),
                subtitle: String(localized: "This morning's score from sleep, HRV and resting heart rate"),
                systemImage: "bolt.heart.fill", domain: "insights",
                keywords: ["recovery", "readiness", "hrv", "resting heart rate", "rested", "strain", "morning score",
                           "body battery"],
                destination: .insights(.recovery)
            ),
            BrowseFeature(
                id: "healthAge", title: String(localized: "Health Age"),
                subtitle: String(localized: "Ayuvo's estimate from your fitness and habits"),
                systemImage: "hourglass", domain: "insights",
                keywords: ["health age", "age", "biological age", "fitness age", "vo2 max", "longevity", "pace"],
                destination: .insights(.healthAge)
            ),
            BrowseFeature(
                id: "dailyReview", title: String(localized: "Daily Review"),
                subtitle: String(localized: "Day Score, what went well and what to try tomorrow"),
                systemImage: "checklist", domain: "insights",
                keywords: ["daily review", "review", "day score", "summary", "how did my day go", "went well", "improve"],
                destination: .insights(.review(nil))
            ),
            BrowseFeature(
                id: "patterns", title: String(localized: "Patterns"),
                subtitle: String(localized: "Associations found in your own data"),
                systemImage: "point.3.connected.trianglepath.dotted", domain: "insights",
                keywords: ["patterns", "pattern", "correlation", "association", "trends", "habits"],
                destination: .insights(.patterns)
            ),
        ]
    }

    /// Features whose title or synonyms match `query`: every query word must start a word of the
    /// title or of a synonym. Title matches rank above synonym-only matches; ties keep table order.
    static func search(_ query: String, in features: [BrowseFeature] = all) -> [BrowseFeature] {
        let tokens = words(query)
        guard !tokens.isEmpty else { return [] }
        let phrase = tokens.joined(separator: " ")
        var scored: [(score: Int, index: Int, feature: BrowseFeature)] = []
        for (index, feature) in features.enumerated() {
            let titleWords = words(feature.title)
            let keywordWords = feature.keywords.flatMap(words)
            let everyWord = tokens.allSatisfy { token in
                (titleWords + keywordWords).contains { $0.hasPrefix(token) }
            }
            guard everyWord else { continue }
            let title = titleWords.joined(separator: " ")
            let score: Int
            if title == phrase {
                score = 0
            } else if title.hasPrefix(phrase) {
                score = 1
            } else if tokens.allSatisfy({ token in titleWords.contains { $0.hasPrefix(token) } }) {
                score = 2
            } else if feature.keywords.contains(where: { words($0).joined(separator: " ").hasPrefix(phrase) }) {
                score = 3
            } else {
                score = 4
            }
            scored.append((score, index, feature))
        }
        return scored.sorted { ($0.score, $0.index) < ($1.score, $1.index) }.map(\.feature)
    }

    /// Lowercased, diacritic-folded words (letters, digits and ':' kept, e.g. "16:8").
    static func words(_ text: String) -> [String] {
        text.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: .current)
            .lowercased()
            .split { !($0.isLetter || $0.isNumber || $0 == ":") }
            .map(String.init)
    }
}
