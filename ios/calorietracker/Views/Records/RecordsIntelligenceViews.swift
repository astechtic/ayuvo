import SwiftUI

/// Card container used by Phase 2 sections.
struct RecordsCard<Content: View>: View {
    var padding: CGFloat = 14
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) { content }
            .ayuvoCard(padding: padding)
    }
}

struct RecordsSectionTitle: View {
    let title: LocalizedStringKey
    var systemImage: String?
    var trailing: String?

    var body: some View {
        HStack(spacing: 6) {
            if let systemImage {
                Image(systemName: systemImage).foregroundStyle(AppColors.calorie)
            }
            Text(title)
                .font(.system(.headline, design: .rounded))
            if let trailing {
                Text(trailing)
                    .font(.system(.caption, design: .rounded, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 2)
                    .background(AppColors.calorie, in: Capsule())
            }
            Spacer(minLength: 0)
        }
    }
}

// MARK: - Processing strip

struct RecordsProcessingStrip: View {
    @Environment(RecordsStore.self) private var store

    var body: some View {
        let summary = store.processingSummary
        if summary.activeRecords > 0 {
            HStack(spacing: 10) {
                ProgressView()
                    .controlSize(.small)
                VStack(alignment: .leading, spacing: 2) {
                    Text(summary.activeRecords == 1 ? String(localized: "Processing 1 record") : String(localized: "Processing \(summary.activeRecords) records"))
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    if let progress = store.processingProgress, progress.total > 1 {
                        Text("Reading page \(progress.page) of \(progress.total)")
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                    } else {
                        Text("Finding details in the background. You can keep using Ayuvo.")
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(12)
            .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("records.processingStrip")
        }
    }
}

// MARK: - AI mode

struct RecordsAIModeOptionRow: View {
    let mode: RecordsAIMode
    let isSelected: Bool
    var note: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: mode.systemImage)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(isSelected ? .white : AppColors.calorie)
                    .frame(width: 32, height: 32)
                    .background(isSelected ? AppColors.calorie : AppColors.calorie.opacity(0.12), in: Circle())
                VStack(alignment: .leading, spacing: 2) {
                    Text(mode.title)
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        .foregroundStyle(.primary)
                    Text(mode.subtitle)
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let note {
                        Text(note)
                            .font(.system(.caption2, design: .rounded, weight: .medium))
                            .foregroundStyle(.orange)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 0)
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(isSelected ? AppColors.calorie : Color.secondary.opacity(0.5))
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
        .accessibilityIdentifier("records.aiMode.\(mode.rawValue)")
    }
}

enum RecordsAIModeNotes {
    /// Availability note per option (§16), nil when the option works as described.
    static func note(for mode: RecordsAIMode, environment: RecordsAIEnvironment) -> String? {
        switch mode {
        case .local:
            environment.localAvailable ? nil : String(localized: "On-device AI isn't set up on this iPhone yet.")
        case .cloud:
            environment.cloudProviderName == nil ? String(localized: "Add an AI provider to use online AI.") : nil
        case .ask, .off:
            nil
        }
    }
}

/// One-time chooser on the Records tab while `healthRecordsAiMode` is unset.
struct RecordsAIChooserCard: View {
    @Environment(RecordsStore.self) private var store
    @State private var selection: RecordsAIMode = .ask

    var body: some View {
        RecordsCard {
            RecordsSectionTitle(title: "How would you like Ayuvo AI to process your health records?", systemImage: "sparkles")
            Text("Details are always found on this iPhone with rules first. AI can fill in what's missing.")
                .font(.system(.caption, design: .rounded))
                .foregroundStyle(.secondary)
            ForEach(RecordsAIMode.allCases) { mode in
                RecordsAIModeOptionRow(mode: mode, isSelected: selection == mode, note: RecordsAIModeNotes.note(for: mode, environment: store.aiEnvironment)) {
                    selection = mode
                }
            }
            Button {
                store.setAIMode(selection)
            } label: {
                Text("Save choice").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(AppColors.calorie)
            .accessibilityIdentifier("records.aiChooser.save")
            Text("You can change this anytime in Settings › Health Records.")
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
        }
        .onAppear {
            store.refreshAIEnvironment()
            selection = store.aiEnvironment.localAvailable ? .local : .ask
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("records.aiChooser")
    }
}

/// "Use AI to find more details?" (§16 ask flow).
struct RecordAIConsentBanner: View {
    let recordIDs: [String]
    var waitingCount: Int
    @Environment(RecordsStore.self) private var store

    var body: some View {
        RecordsCard {
            RecordsSectionTitle(title: "Use AI to find more details?", systemImage: "sparkles")
            Text("Some details weren't found with on-device rules. AI reads the page text of this record only.")
                .font(.system(.caption, design: .rounded))
                .foregroundStyle(.secondary)
            let environment = store.aiEnvironment
            VStack(spacing: 8) {
                if environment.localAvailable {
                    Button {
                        Task { await store.decideAI(ids: recordIDs, request: .local) }
                    } label: {
                        Label("On this device", systemImage: "iphone").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AppColors.calorie)
                    .accessibilityIdentifier("records.consent.local")
                }
                if let provider = environment.cloudProviderName {
                    Button {
                        Task { await store.decideAI(ids: recordIDs, request: .cloud) }
                    } label: {
                        Label("Online AI · \(provider)", systemImage: "cloud").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .tint(AppColors.calorie)
                    .accessibilityIdentifier("records.consent.cloud")
                }
                if !environment.localAvailable && environment.cloudProviderName == nil {
                    Text("No AI is set up yet. Add an AI provider or set up on-device AI in Settings.")
                        .font(.system(.caption, design: .rounded, weight: .medium))
                        .foregroundStyle(.orange)
                }
                Button("Not now") {
                    Task { await store.decideAI(ids: recordIDs, request: .none) }
                }
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .accessibilityIdentifier("records.consent.notNow")
                if waitingCount > 1 {
                    Button {
                        Task {
                            let ids = await store.awaitingConsentIDs()
                            let request: RecordsAIRequest = environment.localAvailable ? .local : .cloud
                            await store.decideAI(ids: ids, request: request)
                        }
                    } label: {
                        Text("Apply to all waiting records (\(waitingCount))")
                    }
                    .font(.system(.footnote, design: .rounded, weight: .semibold))
                    .disabled(!environment.localAvailable && environment.cloudProviderName == nil)
                    .accessibilityIdentifier("records.consent.applyAll")
                }
            }
            .controlSize(.regular)
        }
        .onAppear { store.refreshAIEnvironment() }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("records.consentBanner")
    }
}

// MARK: - Badges

struct RecordMethodBadge: View {
    let method: RecordFieldMethod

    var body: some View {
        Text(method.badgeTitle)
            .font(.system(.caption2, design: .rounded, weight: .semibold))
            .foregroundStyle(method.isAI ? Color.purple : Color.secondary)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background((method.isAI ? Color.purple : Color.secondary).opacity(0.12), in: Capsule())
    }
}

/// Small per-row processing / review indicator.
struct RecordStatusIndicator: View {
    let record: HealthRecord

    var body: some View {
        switch record.processingStatus {
        case .saved, .queued, .extractingText, .analyzing:
            ProgressView()
                .controlSize(.mini)
                .accessibilityLabel(Text("Processing"))
        case .aiPendingConsent:
            Image(systemName: "sparkles")
                .font(.caption)
                .foregroundStyle(.purple)
                .accessibilityLabel(Text("Waiting for your AI choice"))
        default:
            if record.reviewStatus == .needsReview {
                Image(systemName: "exclamationmark.circle.fill")
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .accessibilityLabel(Text("Needs review"))
            }
        }
    }
}

enum RecordStatusText {
    /// §16 detail status label.
    static func aiLabel(_ record: HealthRecord) -> String {
        switch record.aiModeUsed {
        case .local: String(localized: "Processed on this device")
        case .cloud: String(localized: "Processed using online AI · \(record.aiProvider ?? String(localized: "AI provider"))")
        case .none: String(localized: "Not processed by AI")
        }
    }

    static func processing(_ record: HealthRecord) -> String? {
        switch record.processingStatus {
        case .saved, .queued: String(localized: "Waiting to process…")
        case .extractingText: String(localized: "Reading text…")
        case .analyzing: String(localized: "Finding details…")
        case .aiPendingConsent: String(localized: "Waiting for your AI choice")
        case .ready: nil
        case .failedPartial: failure(record.processingError)
        }
    }

    static func failure(_ error: String?) -> String? {
        switch error.flatMap(RecordProcessingError.init(rawValue:)) {
        case .textUnavailable: String(localized: "No readable text was found. You can add details yourself.")
        case .ocrFailed: String(localized: "Text couldn't be recognised on this file. You can add details yourself.")
        case .aiFailed: String(localized: "AI couldn't finish. Details were found without AI.")
        case .aiUnavailable: String(localized: "On-device AI isn't set up — details found without AI")
        case .protectedPDF: String(localized: "Protected PDF: open it in another app and add the title and date yourself.")
        case .unsupported: String(localized: "This file type can't be read. The original is saved.")
        case .none: error == nil ? nil : String(localized: "Some details couldn't be extracted. You can add them manually.")
        }
    }
}

// MARK: - Search

struct RecordsSearchChipsRow: View {
    let chips: [ParsedRecordQuery.Chip]
    let onRemove: (ParsedRecordQuery.Chip) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ForEach(chips) { chip in
                    Button {
                        onRemove(chip)
                    } label: {
                        HStack(spacing: 4) {
                            Text(chip.label)
                            Image(systemName: "xmark").font(.system(size: 9, weight: .bold))
                        }
                        .font(.system(.caption, design: .rounded, weight: .semibold))
                        .foregroundStyle(AppColors.calorie)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(AppColors.calorie.opacity(0.12), in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Remove filter \(chip.label)"))
                    .accessibilityIdentifier("records.searchChip.\(chip.id)")
                }
            }
        }
    }
}

struct RecordSearchResultRow: View {
    let hit: RecordSearchHit

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            RecordRow(record: hit.record)
            if let snippet = hit.snippet, !snippet.isEmpty {
                Self.highlighted(snippet)
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .padding(.leading, 60)
                    .padding(.bottom, 6)
            }
        }
    }

    /// Bolds the `[` `]` spans FTS `snippet()` marks.
    static func highlighted(_ snippet: String) -> Text {
        var result = Text("")
        var bold = false
        var buffer = ""
        for character in snippet {
            if character == "[" || character == "]" {
                if !buffer.isEmpty {
                    result = result + (bold ? Text(buffer).bold().foregroundColor(.primary) : Text(buffer))
                }
                buffer = ""
                bold = character == "["
            } else {
                buffer.append(character)
            }
        }
        if !buffer.isEmpty { result = result + (bold ? Text(buffer).bold() : Text(buffer)) }
        return result
    }
}

// MARK: - Highlights

struct RecordHighlightRow: View {
    let text: String
    var systemImage = "exclamationmark.triangle.fill"
    var tint: Color = .orange

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: systemImage)
                .font(.caption)
                .foregroundStyle(tint)
            Text(text)
                .font(.system(.subheadline, design: .rounded))
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
    }
}
