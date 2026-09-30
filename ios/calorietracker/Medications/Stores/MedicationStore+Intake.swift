import Foundation

/// Per-medication adherence (`meds_adherence`) and supplement daily averages (`supplement_daily`) from the intake
/// contract (docs/intake-metrics.md §3), plus the "move reminder" action the suggestion offers.
struct MedicationIntakeInsights: Sendable {
    struct NutrientAverage: Sendable, Identifiable {
        var key: String
        var dailyAverage: Double?
        var doses: Int
        var windowDays: Int
        var upper: Double?
        var aboveUpper: Bool
        var id: String { key }
    }

    static let adherenceWindowDays = 30
    static let supplementMaxWindowDays = 28

    var adherence: MedsAdherence.Result
    var nutrientAverages: [NutrientAverage]
}

extension MedicationStore {
    /// Last 30 days of scheduled doses (PRN excluded) and the dosing-interval average of each supplement nutrient.
    func intakeInsights(_ detail: MedicationDetail) async -> MedicationIntakeInsights? {
        guard let repository = await openIfNeeded() else { return nil }
        let now = nowMs
        let zone = zoneIdentifier
        let day: Int64 = 86_400_000
        let from = now - Int64(MedicationIntakeInsights.adherenceWindowDays) * day
        guard let logs = try? await repository.database.doseLogs(medicationID: detail.medication.id, from: from, to: now + 1) else {
            return nil
        }
        let scheduled = logs.filter { !$0.isPRN }.map {
            MedsAdherence.Log(scheduledMs: $0.scheduledAtMs, takenMs: $0.takenAtMs, status: $0.status.rawValue)
        }
        let adherence = MedsAdherence.medsAdherence(timeZone: zone, logs: scheduled, config: .shared)

        var averages: [MedicationIntakeInsights.NutrientAverage] = []
        if !detail.nutrients.isEmpty {
            let window = Self.supplementWindowDays(detail, nowMs: now)
            let windowLogs = (try? await repository.database.doseLogs(medicationID: detail.medication.id,
                                                                     from: now - Int64(window) * day, to: now + 1)) ?? []
            let taken = windowLogs.filter { $0.status == .taken && $0.takenAtMs != nil }
            var quantity = 0.0
            for log in taken { quantity += log.doseQuantity }
            let meanQuantity = taken.isEmpty ? 1.0 : quantity / Double(taken.count)
            let profile = NutrientCatalog.profile(UserProfile.load())
            for row in NutrientCatalog.referenceOrdered(detail.nutrients, key: \.nutrientKey) {
                let upper = NutrientsReference.referenceLines(key: row.nutrientKey, profile: profile).upperLimit
                    ?? IntakeNutrientKeys.intakeKey(row.nutrientKey).flatMap { IntakeConfig.shared.dri.upper[$0] }
                let result = NutrientGoals.supplementDaily(amountPerDose: row.amountPerUnit * meanQuantity, doses: taken.count,
                                                           windowDays: Double(window), upper: upper)
                averages.append(.init(key: row.nutrientKey, dailyAverage: result.dailyAverage, doses: result.doses,
                                      windowDays: window, upper: upper, aboveUpper: result.aboveUpper))
            }
        }
        return MedicationIntakeInsights(adherence: adherence, nutrientAverages: averages)
    }

    /// Whole dosing intervals (7 days for specific weekdays, else 1) covering up to 28 days since the start date,
    /// so a weekly dose is spread over its week (a 60,000 IU weekly vitamin D dose counts 1/7 per day).
    static func supplementWindowDays(_ detail: MedicationDetail, nowMs: Int64) -> Int {
        let interval = detail.schedule?.frequency == .weekly ? 7 : 1
        var span = MedicationIntakeInsights.supplementMaxWindowDays
        if let start = MedicationFormatting.dateFromISO(detail.medication.startDate) {
            let days = Int((Double(nowMs) / 1000 - start.timeIntervalSince1970) / 86_400) + 1
            span = min(span, max(days, 1))
        }
        span = max(span, interval)
        return max(interval, span / interval * interval)
    }

    /// The single reminder time of a daily or weekly schedule, when there is exactly one.
    static func singleReminderTime(_ detail: MedicationDetail) -> String? {
        guard !detail.medication.isPRN, detail.medication.status == .active, let schedule = detail.schedule,
              schedule.frequency == .daily || schedule.frequency == .weekly, schedule.times.count == 1 else { return nil }
        return schedule.times.first
    }

    static func clockText(_ minutes: Int) -> String {
        String(format: "%02d:%02d", minutes / 60 % 24, minutes % 60)
    }

    /// Moves the schedule's single reminder time (a schedule edit: past doses keep their original times).
    func moveReminder(_ detail: MedicationDetail, toClockMin minutes: Int) async -> Bool {
        guard Self.singleReminderTime(detail) != nil else { return false }
        var draft = MedicationDraft(from: detail.medication, schedule: detail.schedule)
        draft.times = [Self.clockText(minutes)]
        do {
            _ = try await save(draft: draft, editing: detail.medication.id)
            return true
        } catch {
            return false
        }
    }
}
