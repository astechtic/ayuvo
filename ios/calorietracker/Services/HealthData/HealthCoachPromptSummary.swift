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

    /// "- Recovery Indicator (2026-03-15): 64/100, moderate, confidence 87% (ayuvo.recovery@2). Lower: HRV, resting
    /// heart rate. Higher: sleep." nil without a score.
    static func recoveryLine(_ r: AJ, day: String) -> String? {
        guard let score = r["score"].int else { return nil }
        let conf = Int(((r["confidence"].double ?? 0) * 100).rounded())
        var line = "- Recovery Indicator (\(day)): \(score)/100, \(r["label"].string ?? ""), confidence \(conf)% (\(r["algorithm_id"].string ?? "ayuvo.recovery")@\(r["algorithm_version"].int ?? 2), compared with the user's own baseline)."
        let neg = r["drivers"].array.filter { $0["direction"].string == "negative" }.compactMap { $0["id"].string }
        let pos = r["drivers"].array.filter { $0["direction"].string == "positive" }.compactMap { $0["id"].string }
        if !neg.isEmpty { line += " Pulling down: \(neg.joined(separator: ", "))." }
        if !pos.isEmpty { line += " Supporting: \(pos.joined(separator: ", "))." }
        return line
    }

    /// "- Signals (2026-03-15): several health signals are outside the recent normal range (HRV, resting heart rate)."
    static func signalsLine(_ a: AJ, day: String) -> String? {
        guard let state = a["state"].string, state != "NORMAL" else { return nil }
        let flagged = a["signals"].array.filter { $0["flagged"].bool == true }.compactMap { $0["id"].string }
        let text = AnalyticsConfig.shared["anomaly"]["states"][state].string ?? state
        var line = "- Signals (\(day)): \(text)"
        if !flagged.isEmpty { line += " (\(flagged.joined(separator: ", ")))" }
        if a["persistent"].bool == true { line += " Persistent for several days." }
        return line + " Not a diagnosis."
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
