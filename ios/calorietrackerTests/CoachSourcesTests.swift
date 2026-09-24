import Foundation
import Testing
@testable import calorietracker

/// The data switches and the medication tools (docs/coach.md §3, §6, §8; docs/medications.md §20).
/// The rule these all protect: a switch narrows what consent already permits, and never grants it.
struct CoachSourcesTests {

    private static func tools(
        health: Bool = false,
        medications: Bool = false,
        records: Bool = false,
        workouts: Bool = true,
        switches: CoachDataSwitches = .allOn
    ) -> CoachTools {
        CoachTools(
            weights: [], bodyFats: [], foods: [],
            workoutAccessEnabled: workouts,
            health: nil,
            healthAccessEnabled: health,
            records: nil,
            medications: medications ? Self.medicationsContext() : nil,
            sources: switches
        )
    }

    private static func medicationsContext(count: Int = 1) -> CoachMedicationsContext {
        let rows = (0..<count).map { index in
            RJ.obj([
                "id": .str("m\(index)"), "name": .str("Metformin"), "strength": .str("500 mg"),
                "form": .str("tablet"), "dose_quantity": .int(1), "dose_unit": .str("tablet"),
                "food_relation": .str("with"), "status": .str("active"), "is_prn": .int(0),
                "start_date": .str("2026-09-01"),
            ])
        }
        return CoachMedicationsContext(
            snapshot: .obj(["medications": .arr(rows), "schedules": .arr([]), "dose_logs": .arr([])]),
            timeZone: "Asia/Kolkata",
            nowMs: 1_789_900_000_000,
            count: count,
            activeCount: count
        )
    }

    // MARK: - §8 gating

    @Test func nutritionToolsAreAlwaysThereAndWorkoutsRideWithThem() {
        let names = Self.tools().availableToolNames
        for tool in CoachTools.nutritionToolNames { #expect(names.contains(tool)) }
        for tool in CoachTools.workoutToolNames { #expect(names.contains(tool)) }
        #expect(!names.contains(where: CoachTools.medicationToolNames.contains))
    }

    @Test func medicationToolsNeedBothTheDataAndTheConsent() {
        #expect(!Self.tools(medications: false).availableToolNames
            .contains(where: CoachTools.medicationToolNames.contains))
        let names = Self.tools(medications: true).availableToolNames
        for tool in CoachTools.medicationToolNames { #expect(names.contains(tool)) }
    }

    /// Rule 3: the switch only narrows.
    @Test func aSwitchRemovesToolsButNeverAddsThem() {
        var off = CoachDataSwitches.allOn
        off.set(.medications, false)
        #expect(!Self.tools(medications: true, switches: off).availableToolNames
            .contains(where: CoachTools.medicationToolNames.contains))

        // Health has no context and no consent here; switching it "on" changes nothing.
        var on = CoachDataSwitches.allOn
        on.set(.health, true)
        #expect(!Self.tools(health: false, switches: on).availableToolNames
            .contains(where: CoachTools.healthToolNames.contains))
    }

    @Test func turningFoodOffDropsTheWorkoutToolsToo() {
        var off = CoachDataSwitches.allOn
        off.set(.food, false)
        let names = Self.tools(switches: off).availableToolNames
        #expect(!names.contains(where: CoachTools.nutritionToolNames.contains))
        #expect(!names.contains(where: CoachTools.workoutToolNames.contains))
    }

    @Test func effectiveSourcesMatchTheAdvertisedTools() {
        var off = CoachDataSwitches.allOn
        off.set(.medications, false)
        #expect(Self.tools(medications: true).effectiveSources.contains(.medications))
        #expect(!Self.tools(medications: true, switches: off).effectiveSources.contains(.medications))
    }

    @Test func switchesRoundTripThroughTheirStoredJSON() {
        var switches = CoachDataSwitches.allOn
        switches.set(.records, false)
        let restored = CoachDataSwitches.fromJSON(switches.json)
        #expect(restored.isOn(.records) == false)
        #expect(restored.isOn(.health) == true)
        // Everything on stores nothing, so a source added later defaults to on.
        #expect(CoachDataSwitches.allOn.json == nil)
        #expect(CoachDataSwitches.fromJSON(nil).isOn(.food))
    }

    // MARK: - §20 medication tools

    @Test func medicationToolNamesComeFromTheSharedContract() {
        #expect(CoachTools.medicationToolNames ==
                ["get_medications", "get_dose_history", "get_medication_adherence"])
        for name in CoachTools.medicationToolNames {
            #expect(CoachTools.toolDescriptions[name]?.isEmpty == false, "\(name) has no description")
            #expect(MedicationsCoachContract.shared.parameterSchema(for: name) != nil)
        }
    }

    @Test func aMedicationToolReturnsItsPayload() {
        let tools = Self.tools(medications: true)
        let raw = tools.executeMedicationTool(name: "get_medications", arguments: [:])
        let payload = try? #require(RJ.parse(raw))
        #expect(payload?["count"].double == 1)
        #expect(payload?["medications"].array?.first?["name"].string == "Metformin")
    }

    /// The user turned the source off while a call was in flight.
    @Test func aMedicationToolFailsClosedWithoutContext() {
        let raw = Self.tools(medications: false).executeMedicationTool(name: "get_medications", arguments: [:])
        #expect(RJ.parse(raw)?["error"].string == "medication data is not available")
    }

    @Test func badArgumentsComeBackAsContractErrors() {
        let tools = Self.tools(medications: true)
        let badDate = RJ.parse(tools.executeMedicationTool(
            name: "get_dose_history", arguments: ["from": "14-09-2026", "to": "2026-09-20"]))
        #expect(badDate?["error"].string == "invalid date '14-09-2026' (expected yyyy-MM-dd)")
        let reversed = RJ.parse(tools.executeMedicationTool(
            name: "get_medication_adherence", arguments: ["from": "2026-09-20", "to": "2026-09-14"]))
        #expect(reversed?["error"].string == "from must be on or before to")
    }

    /// The whole reason Coach may read this data at all.
    @Test func theGuardrailsForbidDosingAdvice() {
        let guardrails = MedicationsCoachContract.shared.prompt["guardrails"] ?? ""
        #expect(guardrails.contains("Never tell the user to start, stop, change"))
        let lines = ChatService.medicationsPromptLines(Self.medicationsContext(), newUserMessage: "hi")
        #expect(lines.contains { $0.contains("Never tell the user to start, stop, change") })
    }

    /// The "no medication data" line is only worth the tokens when the user actually asked.
    @Test func theNotAvailableLineOnlyAppearsWhenAsked() {
        #expect(ChatService.medicationsPromptLines(nil, newUserMessage: "how did I sleep?").isEmpty)
        let asked = ChatService.medicationsPromptLines(nil, newUserMessage: "am I taking my pills on time?")
        #expect(asked.count == 1)
        #expect(asked[0].contains("No medication data is available"))
    }

    @Test func mentionWordsComeFromTheSharedContract() {
        #expect(!MedicationsCoachContract.shared.mentionsWords.isEmpty)
        #expect(CoachMedicationsContext.mentionsMedicines("Which MEDICINES am I on?"))
        #expect(CoachMedicationsContext.mentionsMedicines("my dosage changed"))
        #expect(!CoachMedicationsContext.mentionsMedicines("how many steps yesterday"))
    }

    /// Providers without tool calling get names and schedules — never a dose recommendation.
    @Test func theOnDeviceBlockListsMedicinesWithoutAdvice() throws {
        let block = try #require(Self.medicationsContext().onDeviceBlock())
        #expect(block.hasPrefix("## Medications"))
        #expect(block.contains("Metformin 500 mg"))
        #expect(!block.lowercased().contains("should take"))
    }

    // MARK: - §6 attachments in the outgoing message

    @Test func documentExcerptsAreAppendedToTheMessage() {
        let attachment = ChatAttachment(
            kind: .pdf, filename: "report.pdf", excerpt: "Hemoglobin 11.2 g/dL", createdMs: 1)
        let text = CoachAttachmentComposer.messageWithAttachments("What does this say?", [attachment])
        #expect(text.hasPrefix("What does this say?"))
        #expect(text.contains("--- Attached file: report.pdf ---"))
        #expect(text.contains("Hemoglobin 11.2 g/dL"))
    }

    @Test func imagesAreNotInlinedAsText() {
        let image = ChatAttachment(kind: .image, filename: "photo.jpg", createdMs: 1)
        #expect(CoachAttachmentComposer.messageWithAttachments("look", [image]) == "look")
    }

    @Test func aDocumentWithNoReadableTextSaysSoRatherThanVanishing() {
        let empty = ChatAttachment(kind: .pdf, filename: "scan.pdf", excerpt: nil, createdMs: 1)
        let text = CoachAttachmentComposer.messageWithAttachments("read this", [empty])
        #expect(text.contains("scan.pdf"))
        #expect(text.contains("(no readable text)"))
    }

    /// Three long documents must not blow the context window.
    @Test func theTurnBudgetTruncatesRatherThanOverflowing() {
        let long = String(repeating: "a", count: 30_000)
        let attachments = (0..<3).map {
            ChatAttachment(kind: .pdf, filename: "doc\($0).pdf", excerpt: long, createdMs: 1)
        }
        let text = CoachAttachmentComposer.messageWithAttachments("summarise", attachments)
        // The excerpts together cannot exceed the shared per-turn cap.
        let body = text.replacingOccurrences(of: "summarise", with: "")
        #expect(body.unicodeScalars.count <= CR.maxTurnChars + 500)
        #expect(text.contains("(truncated)"))
    }

    @Test func aNoteIsRedactedLikeAnyOtherText() {
        let store = CoachFileStore(root: FileManager.default.temporaryDirectory
            .appendingPathComponent("coach-note-\(UUID().uuidString)", isDirectory: true))
        let outcome = CoachAttachmentProcessor(files: store)
            .processNote("Patient Name: Ravi Kumar\nBP was 120/80")
        #expect(outcome.attachment.kind == .note)
        let excerpt = outcome.attachment.excerpt ?? ""
        #expect(!excerpt.contains("Ravi"))
        #expect(excerpt.contains("BP was 120/80"))
    }
}
