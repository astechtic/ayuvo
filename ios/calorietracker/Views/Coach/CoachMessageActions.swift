import SwiftUI

/// Copy, regenerate and share, under each assistant reply (docs/coach.md §10).
///
/// Regenerate never deletes: the new reply keeps the seq it replaces and becomes another version, so
/// the `‹ 1/2 ›` stepper can walk back to the answer the user preferred.
struct CoachMessageActions: View {
    let message: ChatMessage
    /// Every stored version of this reply, oldest first. One version means no stepper.
    var variants: [ChatMessage]
    var isBusy: Bool
    var onCopy: () -> Void
    var onRegenerate: () -> Void
    var onShare: () -> Void
    var onShowVariant: (ChatMessage) -> Void

    @State private var copied = false

    private var index: Int {
        variants.firstIndex(where: { $0.id == message.id }) ?? max(0, variants.count - 1)
    }

    var body: some View {
        HStack(spacing: 4) {
            if variants.count > 1 {
                stepper
                Divider().frame(height: 14)
            }
            button(copied ? "checkmark" : "doc.on.doc", label: copied ? "Copied" : "Copy") {
                onCopy()
                copied = true
                Task {
                    try? await Task.sleep(for: .seconds(1.6))
                    copied = false
                }
            }
            .accessibilityIdentifier("coach.message.copy")

            button("arrow.clockwise", label: "Regenerate", action: onRegenerate)
                .disabled(isBusy)
                .accessibilityIdentifier("coach.message.regenerate")

            button("square.and.arrow.up", label: "Share", action: onShare)
                .accessibilityIdentifier("coach.message.share")

            Spacer(minLength: 0)
        }
        .font(.system(size: 12, weight: .medium))
        .foregroundStyle(.secondary)
        .padding(.top, 2)
    }

    private var stepper: some View {
        HStack(spacing: 2) {
            Button {
                if index > 0 { onShowVariant(variants[index - 1]) }
            } label: {
                Image(systemName: "chevron.left").frame(width: 22, height: 26)
            }
            .buttonStyle(.plain)
            .disabled(index == 0)
            .accessibilityLabel(Text("Previous version"))

            Text("\(index + 1)/\(variants.count)")
                .font(.system(size: 11, weight: .semibold, design: .rounded))
                .monospacedDigit()

            Button {
                if index + 1 < variants.count { onShowVariant(variants[index + 1]) }
            } label: {
                Image(systemName: "chevron.right").frame(width: 22, height: 26)
            }
            .buttonStyle(.plain)
            .disabled(index + 1 >= variants.count)
            .accessibilityLabel(Text("Next version"))
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("coach.message.variant")
    }

    private func button(_ systemImage: String, label: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label(label, systemImage: systemImage)
                .labelStyle(.iconOnly)
                .frame(width: 30, height: 26)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
    }
}

/// The share sheet for one exported file or one message's text.
struct CoachShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
