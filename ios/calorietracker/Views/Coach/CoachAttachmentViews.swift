import SwiftUI

nonisolated enum CoachComposerLimits {
    /// Images per turn (docs/coach.md §6).
    static let maxImages = 4
    /// Documents per turn; the per-turn character budget still applies on top.
    static let maxDocuments = 3
}

/// Rule 4: the user sees exactly what the model sees. The chip opens this sheet, which shows the
/// redacted excerpt verbatim — identity lines, phone numbers and long ID runs already removed by the
/// records rule (docs/health-records.md §28).
struct CoachAttachmentExcerptSheet: View {
    let attachment: ChatAttachment
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let excerpt = attachment.excerpt, !excerpt.isEmpty {
                        Text(excerpt)
                            .font(.system(.footnote, design: .monospaced))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    } else {
                        Text("No readable text was found in this file, so nothing from it will be sent.")
                            .font(.system(.subheadline, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(16)
            }
            .background(AppColors.appBackground)
            .navigationTitle(attachment.filename)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                Text("This is exactly what Coach will send. Names, phone numbers and ID numbers are removed before sending.")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.bar)
                    .overlay(alignment: .top) { Divider() }
            }
        }
        .presentationDetents([.medium, .large])
        .accessibilityIdentifier("coach.attachment.excerpt")
    }
}

/// A typed or pasted note. Redacted like any other text before it is attached.
struct CoachNoteSheet: View {
    @Binding var text: String
    var onSave: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 0) {
                TextEditor(text: $text)
                    .font(.system(.body, design: .rounded))
                    .scrollContentBackground(.hidden)
                    .padding(12)
                    .focused($focused)
            }
            .background(AppColors.appBackground)
            .navigationTitle("Note")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Attach") {
                        onSave(text)
                        dismiss()
                    }
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .task { focused = true }
        }
        .presentationDetents([.medium, .large])
    }
}

/// The row of pending attachments above the composer. Tapping a document opens its excerpt (rule 4);
/// the ✕ removes it before sending.
struct CoachPendingAttachmentsRow: View {
    var attachments: [ChatAttachment]
    var onOpen: (ChatAttachment) -> Void
    var onRemove: (ChatAttachment) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(attachments) { attachment in
                    HStack(spacing: 9) {
                        Image(systemName: attachment.kind.systemImage)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(AppColors.calorie)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(attachment.filename)
                                .font(.system(.caption, design: .rounded, weight: .semibold))
                                .lineLimit(1)
                            if !attachment.subtitle.isEmpty {
                                Text(attachment.subtitle)
                                    .font(.system(.caption2, design: .rounded))
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                        }
                        Button {
                            onRemove(attachment)
                        } label: {
                            Image(systemName: "xmark")
                                .font(.system(size: 10, weight: .bold))
                                .foregroundStyle(.secondary)
                                .frame(width: 24, height: 24)
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.leading, 11)
                    .padding(.vertical, 6)
                    .background(.ultraThinMaterial, in: Capsule())
                    .overlay(Capsule().stroke(AppColors.calorie.opacity(0.18), lineWidth: 0.7))
                    .contentShape(Capsule())
                    .onTapGesture { onOpen(attachment) }
                    .accessibilityIdentifier("coach.attachment.\(attachment.id)")
                }
            }
            .padding(.horizontal, 12)
        }
    }
}

/// The one-time confirmation before Coach may read the user's medicines (docs/coach.md §3).
/// Turning the switch on is the affirmative act; this states plainly what leaves the device.
struct CoachMedicationsConsentSheet: View {
    var providerName: String
    var onDevice: Bool
    var onAllow: () -> Void
    var onNotNow: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "pills.fill")
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(AppColors.calorie)
                .padding(.top, 28)
            Text("Let Coach use your medicines?")
                .font(.system(.title3, design: .rounded, weight: .semibold))
                .multilineTextAlignment(.center)
            Text(body(for: providerName, onDevice: onDevice))
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 26)
            Text("Coach never tells you to start, stop or change a medicine.")
                .font(.system(.footnote, design: .rounded, weight: .medium))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 26)
            Spacer(minLength: 0)
            VStack(spacing: 10) {
                Button(action: onAllow) {
                    Text("Allow")
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(AppColors.calorie)
                .controlSize(.large)
                .accessibilityIdentifier("coach.medications.allow")

                Button(action: onNotNow) {
                    Text("Not now")
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.plain)
                .foregroundStyle(AppColors.calorie)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 12)
        }
        .background(AppColors.appBackground)
        .presentationDetents([.medium])
        .accessibilityIdentifier("coach.medications.consent")
    }

    private func body(for provider: String, onDevice: Bool) -> String {
        onDevice
            ? String(localized: "Coach reads your medicines on this device; nothing is sent online.")
            : String(localized: "Coach sends the medicines it reads — names, strengths and dose times — to \(provider) to answer you. They stay stored on this device.")
    }
}
