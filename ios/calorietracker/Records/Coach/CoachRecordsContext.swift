import Foundation

/// Answer to the §30 prompt "Coach uses <provider>. Send the selected records' details online?".
nonisolated enum CoachRecordsOnlineDecision: Sendable, Equatable {
    case send
    case cancel
    case useOnDevice
}

/// Per-message state shared by the tool executor and the chat screen: the records read in this turn
/// (`record_refs`) and the §30 online approval.
actor CoachRecordsSession {
    private(set) var refs: [ChatRecordRef] = []
    /// nil = not asked yet in this conversation (only consulted when `needsOnlineApproval`).
    private(set) var decision: CoachRecordsOnlineDecision?
    let needsOnlineApproval: Bool
    private let requestApproval: (@Sendable () async -> CoachRecordsOnlineDecision)?

    init(needsOnlineApproval: Bool = false, decision: CoachRecordsOnlineDecision? = nil, requestApproval: (@Sendable () async -> CoachRecordsOnlineDecision)? = nil) {
        self.needsOnlineApproval = needsOnlineApproval
        self.decision = decision
        self.requestApproval = requestApproval
    }

    func add(_ added: [ChatRecordRef]) {
        refs = RecordsCoach.mergeRefs(refs, added)
    }

    /// True when records may be sent to the provider for this call.
    func approve() async -> Bool {
        guard needsOnlineApproval else { return true }
        if decision == nil {
            decision = await requestApproval?() ?? .cancel
        }
        return decision == .send
    }

    var switchToOnDevice: Bool { decision == .useOnDevice }
}

/// Everything Coach needs about Health Records for one message (the records analogue of
/// `CoachHealthContext`). `enabled` mirrors `healthRecordsCoachAccessEnabled`.
nonisolated struct CoachRecordsContext: Sendable {
    var enabled: Bool
    /// The conversation's selected records (§27), 0–10, refreshed from the store.
    var selected: [ChatRecordRef] = []
    var database: RecordsDatabase?
    var today: String = RecordDates.dayString(from: Date())
    var dateOrder: String = RecordDateOrder.device.rawValue
    /// Reference `coach_prompt_lines` output (§26).
    var prompt: RJ = .null
    /// Reference `pack_coach_records` output (§29) for tool-less on-device providers.
    var packed: RJ = .null
    var session = CoachRecordsSession()

    static let disabled = CoachRecordsContext(
        enabled: false,
        prompt: RR.coachPromptLines(.obj([:]), accessEnabled: false, selectedIDs: [], typeLabels: RecordsCoach.typeLabels)
    )

    /// Tools are advertised only when access is on and at least one non-archived record exists.
    var toolsAvailable: Bool { enabled && database != nil && prompt["advertise_tools"].truthy }

    var selectedIDs: [String] { selected.map(\.recordID) }

    /// §29 block text ("" without a selection).
    var onDeviceBlock: String { enabled ? (packed["text"].string ?? "") : "" }

    /// Refs of the packed block (§26: they come first in `record_refs`).
    var packedRefs: [ChatRecordRef] {
        let ids = (packed["record_ids"].array ?? []).compactMap(\.string)
        return ids.compactMap { id in selected.first { $0.recordID == id }.map { ChatRecordRef(recordID: $0.recordID, title: $0.title, date: $0.date) } }
    }
}

/// Executes the three records tools (§28) through the reference port, with the selection restriction
/// (§27), and records the refs.
nonisolated enum RecordsCoachToolExecutor {
    static func execute(name: String, arguments: [String: Any], context: CoachRecordsContext?) async -> String {
        guard let context, context.toolsAvailable, let database = context.database else {
            return RR.coachError("unavailable").jsonText
        }
        guard await context.session.approve() else {
            return RR.coachError("unavailable").jsonText
        }
        let args = RJ.from(arguments)
        let payload = (try? await database.coachToolPayload(
            name: name, args: args.object == nil ? .obj([:]) : args, selectedIDs: context.selectedIDs,
            today: context.today, dateOrder: context.dateOrder
        )) ?? RR.coachError("unavailable")
        await context.session.add(RecordsCoach.refs(tool: name, payload: payload))
        return payload.jsonText
    }
}
