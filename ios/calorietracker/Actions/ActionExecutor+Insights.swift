import Foundation

/// `insights.*` actions (read only): the same Recovery, Health Age and Daily Review the Insights screens show,
/// recomputed from the stores on every call. Nothing is stored; results carry values only after unlock.
extension ActionExecutor {
    /// The environment's stores and Health mirror as an Insights source (fresh, non-observing stores).
    func insightsSource() throws -> InsightsDataSource {
        guard env.healthSyncEnabled else {
            throw ActionError.permissionRequired(String(localized: "Turn on Apple Health in Ayuvo (Settings → Health Sync) to use Insights."))
        }
        guard InsightsSettings.isEnabled(env.defaults) else {
            throw ActionError.unavailable(String(localized: "Insights is turned off in Ayuvo (Settings → Insights)."))
        }
        let env = env
        var source = InsightsDataSource.live(
            food: env.foodStore(), water: env.waterStore(), fasting: env.fastingStore(), weight: env.weightStore(),
            bodyFat: env.bodyFatStore(), workouts: env.workoutStore(), importedWorkouts: env.importedWorkoutStore(),
            profile: { env.profile ?? .default }, defaults: env.defaults, calendar: env.calendar
        )
        source.healthDatabase = {
            guard let runtime = env.healthRuntime, await runtime.openIfNeeded() else { return nil }
            return runtime.reader ?? runtime.writer
        }
        if !env.sideEffects { source.dailyTotals = { _, _, _ in nil } }
        return source
    }

    func insightsReport() async throws -> InsightsReport {
        try await insightsSource().report(now: env.nowDate)
    }

    func recoveryGet(_ v: ActionValidation) async throws -> ActionResult {
        let r = try await insightsReport().recovery
        let fields: [String: ActionField] = [
            "status": .string(r.status), "score": .optional(r.score), "label": .optional(r.labelText),
            "recommendation": .optional(r.recommendation), "confidence": .optional(r.confidence),
            "positives": .list(r.positives.map { .string($0.text) }), "negatives": .list(r.negatives.map { .string($0.text) }),
            "training_load": .optional(r.load?.label), "value": .optional(r.score),
        ]
        let dialog: String
        switch r.status {
        case "ok":
            dialog = String(localized: "Your Recovery is \(r.score ?? 0), \(r.labelText ?? ""). \(r.recommendation ?? "").")
        case "collecting":
            dialog = String(localized: "Ayuvo is still learning your baseline (\(r.collecting?.have ?? 0)/\(r.collecting?.need ?? 14) nights).")
        case "no_sleep":
            dialog = String(localized: "Last night's sleep hasn't synced yet, so there's no Recovery score.")
        default:
            dialog = String(localized: "There's no heart reading for last night yet, so there's no Recovery score.")
        }
        return ActionResult(actionID: v.actionID, fields: fields, dialog: dialog)
    }

    func healthAgeGet(_ v: ActionValidation) async throws -> ActionResult {
        let report = try await insightsReport()
        let h = report.healthAge, p = report.pace
        let markers: [ActionField] = h.markers.filter(\.available).map { m in
            .object(["id": .string(m.id), "contribution_years": .optional(m.contributionYears), "offset_years": .optional(m.offsetYears)])
        }
        let fields: [String: ActionField] = [
            "status": .string(h.status), "actual_age": .optional(h.actualAge), "health_age": .optional(h.healthAge),
            "difference": .optional(h.difference), "confidence": .optional(h.confidence),
            "pace": .optional(p.status == "ok" ? p.pace : nil), "direction": .optional(p.status == "ok" ? p.direction : nil),
            "markers": .list(markers), "value": .optional(h.healthAge),
        ]
        let dialog: String
        if h.isReady, let age = h.healthAge, let actual = h.actualAge, let difference = h.difference {
            let ageText = age.formatted(.number.precision(.fractionLength(1)))
            let actualText = actual.formatted(.number.precision(.fractionLength(1)))
            let gap = abs(difference).formatted(.number.precision(.fractionLength(1)))
            dialog = difference <= 0
                ? String(localized: "Your Ayuvo Health Age is \(ageText), \(gap) years below your actual age of \(actualText). It's Ayuvo's own estimate, not a clinical age.")
                : String(localized: "Your Ayuvo Health Age is \(ageText), \(gap) years above your actual age of \(actualText). It's Ayuvo's own estimate, not a clinical age.")
        } else if h.status == "collecting" {
            dialog = String(localized: "Ayuvo is still collecting data for Health Age (\(h.collecting?.have ?? 0)/\(h.collecting?.need ?? 30) days).")
        } else if h.status == "no_birthday" {
            dialog = String(localized: "Add your birthday in Ayuvo to see your Health Age.")
        } else {
            dialog = String(localized: "Health Age isn't available for this profile.")
        }
        return ActionResult(actionID: v.actionID, fields: fields, dialog: dialog)
    }

    func dailyReviewGet(_ v: ActionValidation) async throws -> ActionResult {
        let report = try await insightsReport()
        let day = v.string("day") == "yesterday" ? InsightsDay.add(report.today, -1) : report.today
        guard let review = report.reviews[day] else { throw ActionError.notFound(String(localized: "There's no review for that day.")) }
        func texts(_ items: [ReviewItem]) -> ActionField { .list(items.map { .string($0.text) }) }
        let fields: [String: ActionField] = [
            "status": .string(review.dayScore == nil ? "empty" : "ok"), "day": .string(day), "day_score": .optional(review.dayScore),
            "went_well": texts(review.wentWell), "needs_attention": texts(review.needsAttention), "improve": texts(review.improve),
            "reduce": texts(review.reduce), "not_logged": .list(review.notLogged.map { .string($0.params["area"]?.text ?? "") }),
            "value": .optional(review.dayScore),
        ]
        let when = day == report.today ? String(localized: "today") : String(localized: "yesterday")
        let dialog: String
        if let score = review.dayScore {
            dialog = String(localized: "Your Day Score \(when) is \(score). \(review.wentWell.count) went well, \(review.needsAttention.count) need attention.")
        } else {
            dialog = String(localized: "Nothing was logged or synced \(when) yet, so there's no Day Score.")
        }
        return ActionResult(actionID: v.actionID, fields: fields, dialog: dialog)
    }
}
