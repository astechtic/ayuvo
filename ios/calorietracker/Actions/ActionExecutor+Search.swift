import Foundation

/// `search.universal` and the OPEN actions.
extension ActionExecutor {
    func universalSearch(_ v: ActionValidation, source: ActionSource) async throws -> ActionResult {
        let query = (v.string("query") ?? "").lowercased()
        let domain = v.string("domain") ?? "all"
        let limit = v.int("limit") ?? 20
        func wants(_ name: String) -> Bool { domain == "all" || domain == name }
        var items: [[String: ActionField]] = []
        func add(_ domain: String, _ id: String, _ title: String, _ subtitle: String) {
            items.append(["domain": .string(domain), "id": .string(id), "title": .string(title), "subtitle": .string(subtitle)])
        }

        if wants("food") {
            let store = env.foodStore()
            var seen = Set<String>()
            for entry in store.favorites + store.entries.sorted(by: { $0.timestamp > $1.timestamp })
            where entry.name.lowercased().contains(query) && seen.insert(entry.favoriteKey).inserted {
                add("food", entry.id.uuidString, entry.name, String(localized: "\(entry.calories) kcal"))
                if seen.count >= limit { break }
            }
        }
        if wants("exercises") {
            for item in env.workoutStore().exerciseLibrary.exercises where item.searchableText.contains(query) {
                add("exercises", item.id, item.name, item.bodyPart)
                if items.filter({ $0["domain"]?.string == "exercises" }).count >= limit { break }
            }
        }
        if wants("metrics") {
            for metric in AppMetric.allCases {
                let title = MetricCatalog.descriptor(for: .app(metric)).title
                if title.lowercased().contains(query) { add("metrics", metric.key, title, String(localized: "Logged in Ayuvo")) }
            }
            for type in HealthMetricRegistry.iOSTypes where type.displayName.lowercased().contains(query) || type.id.contains(query) {
                add("metrics", type.id, type.displayName, type.category.displayName)
            }
        }
        // Coach sees records / medicines only with that source's own Coach consent.
        let recordsAllowed = source != .coach || env.defaults.bool(forKey: RecordsStore.coachAccessKey)
        if wants("records"), recordsAllowed, let payload = try? await recordsPayload(RecordsCoachContract.searchTool, ["query": query, "limit": limit]) {
            for record in payload["records"].array ?? [] {
                add("records", record["record_id"].string ?? "", record["title"].string ?? "", record["date"].string ?? "")
            }
        }
        let medicationsAllowed = source != .coach || env.defaults.bool(forKey: MedicationStore.coachEnabledKey)
        if wants("medications"), medicationsAllowed, let runtime = env.medicationsRuntime, runtime.databaseExists,
           await runtime.openIfNeeded(), let repository = runtime.repository {
            for medication in (try? await repository.medications(status: nil, search: query)) ?? [] {
                add("medications", medication.id, medication.displayName, medication.status.title)
            }
        }

        let results = Array(items.prefix(limit))
        let titles = results.prefix(3).compactMap { $0["title"]?.string }.joined(separator: ", ")
        let dialog = results.isEmpty
            ? String(localized: "Nothing in Ayuvo matches “\(v.string("query") ?? "")”.")
            : String(localized: "\(results.count) results: \(titles)\(results.count > 3 ? "…" : "").")
        return ActionResult(actionID: v.actionID, fields: ["count": .int(results.count)], items: results, dialog: dialog)
    }

    func openRoute(_ v: ActionValidation) -> ActionResult {
        switch v.actionID {
        case "open.metric":
            return ActionResult(actionID: v.actionID, dialog: String(localized: "Opening Ayuvo."), route: .target("metric:\(v.string("metric") ?? "")"))
        case "open.record":
            return ActionResult(actionID: v.actionID, dialog: String(localized: "Opening the record."), route: .target("record:\(v.string("record") ?? "")"))
        case "open.coach":
            return ActionResult(actionID: v.actionID, dialog: String(localized: "Opening Coach."), route: .coach(prompt: v.string("prompt")))
        default:
            let section = v.string("section") ?? "summary"
            return ActionResult(actionID: v.actionID, dialog: String(localized: "Opening Ayuvo."),
                                route: section == "coach" ? .coach(prompt: nil) : .target("section:\(section)"))
        }
    }
}
