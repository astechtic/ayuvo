import Foundation

/// ≤12-line, 7-day digest for Coach modes that cannot call tools (Apple Intelligence /
/// LiteRT). Canonical units, plain English, no medical interpretation.
nonisolated enum HealthCoachPromptSummary {
    static let maxLines = 12

    /// Types worth a line, in priority order. Sleep is handled separately.
    static let priorityTypeIDs = [
        "steps", "active_energy", "exercise_minutes", "resting_heart_rate", "heart_rate", "hrv_sdnn", "blood_oxygen",
        "respiratory_rate", "weight", "body_fat", "blood_pressure", "blood_glucose", "distance", "workout", "hydration",
    ]

    static func lines(query: CoachHealthQuery, calendar: Calendar, now: Date = Date()) async -> [String] {
        let to = HealthCoachQueryBuilder.isoDay(now, calendar: calendar)
        let fromDate = calendar.date(byAdding: .day, value: -6, to: now) ?? now
        let from = HealthCoachQueryBuilder.isoDay(fromDate, calendar: calendar)
        let available = Set(await query.dataTypes().map(\.dataType))
        var lines: [String] = []

        let nights = await query.sleep(from, to, 7)
        if !nights.isEmpty {
            let average = nights.map(\.asleepS).reduce(0, +) / Double(nights.count)
            var line = "- Sleep: avg \(durationText(average)) asleep over \(nights.count) night\(nights.count == 1 ? "" : "s")"
            if let last = nights.last {
                line += " (last night \(durationText(last.asleepS)), in bed \(durationText(last.inBedS)))"
            }
            lines.append(line)
        }

        for typeID in priorityTypeIDs where available.contains(typeID) && lines.count < maxLines {
            guard let type = HealthMetricRegistry.type(id: typeID),
                  let summary = await query.summary(typeID, from, to, 7),
                  !summary.days.isEmpty
            else { continue }
            lines.append(line(for: type, summary: summary))
        }
        return Array(lines.prefix(maxLines))
    }

    static func line(for type: HealthMetricType, summary: HealthCoachSummary) -> String {
        let name = type.englishName
        let days = summary.days.count
        switch type.kind {
        case .cumulative:
            let avg = summary.average.map { format($0, unit: type.unit) } ?? "—"
            let total = summary.total.map { format($0, unit: type.unit) } ?? "—"
            return "- \(name): avg \(avg)/day over \(days) day\(days == 1 ? "" : "s"), total \(total)"
        case .duration, .session:
            let avg = summary.average.map { durationText($0) } ?? "—"
            let total = summary.total.map { durationText($0) } ?? "—"
            return "- \(name): avg \(avg)/day over \(days) day\(days == 1 ? "" : "s"), total \(total)"
        case .discrete, .series:
            if type.isBloodPressure {
                let sys = summary.average.map { format($0, unit: "") } ?? "—"
                let dia = summary.days.compactMap(\.v2Avg)
                let diaText = dia.isEmpty ? "—" : format(dia.reduce(0, +) / Double(dia.count), unit: "")
                return "- \(name): avg \(sys)/\(diaText) mmHg over \(days) day\(days == 1 ? "" : "s")"
            }
            var text = "- \(name): avg \(summary.average.map { format($0, unit: type.unit) } ?? "—")"
            if let min = summary.min, let max = summary.max {
                text += " (range \(format(min, unit: ""))–\(format(max, unit: type.unit)))"
            }
            if let latest = summary.latest {
                text += ", latest \(format(latest, unit: type.unit))"
            }
            return text + " over \(days) day\(days == 1 ? "" : "s")"
        case .category:
            return "- \(name): \(Int(summary.total ?? 0)) entries over \(days) day\(days == 1 ? "" : "s")"
        }
    }

    static func format(_ value: Double, unit: String) -> String {
        let digits = abs(value) >= 100 ? 0 : (abs(value) >= 10 ? 1 : 2)
        let number = value.formatted(.number.precision(.fractionLength(0...digits)).grouping(.never).locale(Locale(identifier: "en_US_POSIX")))
        switch unit {
        case "": return number
        case "count": return number
        case "%": return "\(number)%"
        default: return "\(number) \(unit)"
        }
    }

    static func durationText(_ seconds: Double) -> String {
        let total = Int(seconds.rounded())
        let hours = total / 3600
        let minutes = (total % 3600) / 60
        if hours == 0 { return "\(minutes) min" }
        return "\(hours) h \(minutes) min"
    }
}
