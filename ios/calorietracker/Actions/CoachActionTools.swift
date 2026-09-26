import SwiftUI

/// Coach side of the action catalog (docs/actions.md §Coach): `coach_mode: read` actions become extra
/// read-only Coach tools, and `propose_action` lets Coach suggest a `coach_mode: propose` write that
/// only runs when the user taps Confirm on the card under the reply. Coach never writes by itself and
/// can never propose medication or goal changes (the catalog marks those `coach_mode: none`).
nonisolated struct CoachActionProposal: Identifiable, Equatable, Sendable {
    let id = UUID()
    let validation: ActionValidation
    let title: String
    let summary: String
}

/// Collects proposals made during one Coach turn.
@MainActor
final class CoachActionProposalSink {
    private(set) var proposals: [CoachActionProposal] = []

    func add(_ proposal: CoachActionProposal) {
        // A model that repeats the same proposal in one turn gets one card.
        guard !proposals.contains(where: { $0.validation == proposal.validation }) else { return }
        proposals.append(proposal)
    }
}

extension CoachTools {
    static let proposeToolName = "propose_action"

    /// Catalog read tools (`coach_tool` names), in catalog order.
    static var actionReadToolNames: [String] {
        ActionCatalog.shared.actions.filter { $0.coachMode == "read" }.compactMap(\.coachTool)
    }

    static var proposableActionIDs: [String] {
        ActionCatalog.shared.actions.filter { $0.coachMode == "propose" }.map(\.id)
    }

    /// Local diary tools follow the food source (no extra consent); proposals also need a UI to confirm.
    var actionToolNames: [String] {
        guard availableToolNames.contains("get_data_summary") else { return [] }
        return Self.actionReadToolNames + (actionProposals == nil ? [] : [Self.proposeToolName])
    }

    /// Everything advertised to the provider this turn.
    /// Catalog tools go after the diary / health tools and before the records and medications
    /// contract tools, so the existing tool order is unchanged.
    var allToolNames: [String] {
        let base = availableToolNames
        let contract = Set(Self.recordsToolNames + Self.medicationToolNames)
        let split = base.firstIndex { contract.contains($0) } ?? base.count
        return Array(base[..<split]) + actionToolNames + Array(base[split...])
    }

    static func isActionTool(_ name: String) -> Bool {
        name == proposeToolName || actionReadToolNames.contains(name)
    }

    static func actionFor(tool name: String) -> ActionCatalog.Action? {
        ActionCatalog.shared.actions.first { $0.coachTool == name }
    }

    static func description(for name: String) -> String {
        if name == proposeToolName {
            let actions = ActionCatalog.shared.actions.filter { $0.coachMode == "propose" }.map { action in
                let params = action.params.map { param -> String in
                    var text = param.name
                    if let name = param.enumName, let values = ActionCatalog.shared.enums[name] { text += " (\(values.joined(separator: "|")))" }
                    return param.required ? text + "*" : text
                }.joined(separator: ", ")
                return "\(action.id): \(action.summary) Params: \(params.isEmpty ? "none" : params)."
            }.joined(separator: " ")
            return "Suggest a change to the user's Ayuvo log. Nothing is saved: the user sees a card with Confirm and decides. "
                + "Only propose when the user asked to log or start/stop something. * = required. Actions: \(actions)"
        }
        if let action = actionFor(tool: name) {
            return "\(action.summary) (Ayuvo action \(action.id); read-only, local data.)"
        }
        return toolDescriptions[name] ?? ""
    }

    static func schema(for name: String) -> [String: Any] {
        if name == proposeToolName {
            return [
                "type": "object",
                "properties": [
                    "action_id": ["type": "string", "enum": proposableActionIDs, "description": "Which Ayuvo action to propose"],
                    "params_json": ["type": "string", "description": "The action's parameters as a JSON object string, e.g. {\"amount\": 500, \"unit\": \"ml\"}"],
                ],
                "required": ["action_id", "params_json"],
            ]
        }
        if let action = actionFor(tool: name) {
            var properties: [String: Any] = [:]
            for param in action.params {
                var property: [String: Any] = ["description": param.summary]
                switch param.type {
                case .number: property["type"] = "number"
                case .integer: property["type"] = "integer"
                case .enum:
                    property["type"] = "string"
                    if let name = param.enumName, let values = ActionCatalog.shared.enums[name] { property["enum"] = values }
                case .string, .metric, .entity: property["type"] = "string"
                }
                properties[param.name] = property
            }
            var schema: [String: Any] = ["type": "object", "properties": properties]
            let required = action.params.filter(\.required).map(\.name)
            if !required.isEmpty { schema["required"] = required }
            return schema
        }
        return parameterSchema(for: name)
    }

    func executeActionTool(name: String, arguments: [String: Any]) async -> String {
        let executor = ActionExecutor.shared
        if name == Self.proposeToolName {
            guard let actionID = arguments["action_id"] as? String,
                  executor.catalog.action(actionID)?.coachMode == "propose" else {
                return ActionJSON.error(.invalid(code: "not_allowed", param: "action_id"))
            }
            var params: [String: Any] = [:]
            let rawParams = arguments["params_json"] ?? arguments["params"]
            if let object = rawParams as? [String: Any] {
                params = object
            } else if let text = rawParams as? String, let data = text.data(using: .utf8),
                      let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
                params = object
            }
            switch executor.validate(actionID, ActionRawValue.params(params), source: .coach) {
            case .failure(let error):
                return ActionJSON.error(error, actionID: actionID)
            case .success(let validation):
                let summary = executor.summary(of: validation)
                actionProposals?.add(CoachActionProposal(validation: validation, title: executor.catalog.action(actionID)?.title ?? actionID, summary: summary))
                return ActionJSON.text([
                    "proposed": true, "action": actionID, "summary": summary,
                    "note": "Shown to the user as a card with a Confirm button. Nothing is saved until they tap Confirm; tell them to confirm below.",
                ])
            }
        }
        guard let action = Self.actionFor(tool: name) else { return ActionJSON.error(.invalid(code: "unknown_action", param: nil)) }
        do {
            return try await executor.run(action.id, ActionRawValue.params(arguments), source: .coach).jsonText
        } catch let error as ActionError {
            return ActionJSON.error(error, actionID: action.id)
        } catch {
            return ActionJSON.error(.unavailable(error.localizedDescription), actionID: action.id)
        }
    }
}

/// Confirm card under a Coach reply for one proposed action.
struct CoachActionProposalCard: View {
    let proposal: CoachActionProposal
    let outcome: String?
    let isRunning: Bool
    let onConfirm: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label(proposal.title, systemImage: "checkmark.seal")
                .font(.system(.footnote, design: .rounded, weight: .semibold))
                .foregroundStyle(.secondary)
            Text(proposal.summary)
                .font(.system(.subheadline, design: .rounded))
            if let outcome {
                Text(outcome)
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("coach.proposal.outcome")
            } else {
                HStack(spacing: 10) {
                    Button {
                        onConfirm()
                    } label: {
                        Text("Confirm").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(isRunning)
                    .accessibilityIdentifier("coach.proposal.confirm")
                    Button(role: .cancel) {
                        onDismiss()
                    } label: {
                        Text("Not now").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(isRunning)
                }
                .font(.system(.footnote, design: .rounded, weight: .semibold))
            }
        }
        .padding(12)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("coach.proposal")
    }
}
