import MessageUI
import SwiftUI

/// Copy, regenerate, share and report, under each assistant reply (docs/coach.md §10).
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
    @State private var showReport = false
    /// Message ids the user reported (device-local; reports themselves are emailed only if the user chooses).
    @AppStorage(CoachReport.reportedKey) private var reportedRaw = ""

    private var isReported: Bool { reportedRaw.split(separator: ",").contains(Substring(message.id)) }

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

            button(isReported ? "flag.fill" : "flag", label: isReported ? "Response reported" : "Report response") { showReport = true }
                .accessibilityIdentifier("coach.message.report")

            Spacer(minLength: 0)
        }
        .font(.system(size: 12, weight: .medium))
        .foregroundStyle(.secondary)
        .padding(.top, 2)
        .sheet(isPresented: $showReport) {
            CoachReportSheet(message: message) {
                if !isReported { reportedRaw = (reportedRaw.isEmpty ? "" : reportedRaw + ",") + message.id }
            }
        }
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

// MARK: - Reporting an AI response

/// Reporting an AI reply (Google Play AI-generated content policy, App Review 1.2): the user picks a reason,
/// the reply is marked "Reported" on this device, and — only if the user keeps the switch on — the reply and the
/// reason are emailed to Ayuvo through the in-app mail sheet. Nothing is sent automatically; there is no server.
enum CoachReport {
    static let reportedKey = "coachReportedMessageIDs"

    enum Reason: String, CaseIterable, Identifiable {
        case offensive, harmful, incorrect, other
        var id: String { rawValue }
        var title: LocalizedStringKey {
            switch self {
            case .offensive: "Offensive or inappropriate"
            case .harmful: "Harmful or unsafe advice"
            case .incorrect: "Incorrect information"
            case .other: "Something else"
            }
        }
    }

    static func emailBody(_ message: ChatMessage, reason: Reason) -> String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        return """
        AI response report
        Reason: \(reason.rawValue)
        App: Ayuvo iOS \(version) (\(build))

        Reported response:
        \(String(message.content.prefix(6000)))
        """
    }
}

struct CoachReportSheet: View {
    let message: ChatMessage
    var onReported: () -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var reason: CoachReport.Reason?
    @State private var sendEmail = true
    @State private var showMail = false
    @State private var done = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(CoachReport.Reason.allCases) { r in
                        Button {
                            reason = r
                        } label: {
                            HStack {
                                Text(r.title).foregroundStyle(.primary)
                                Spacer()
                                if reason == r { Image(systemName: "checkmark").foregroundStyle(.tint) }
                            }
                        }
                        .accessibilityIdentifier("coach.report.reason.\(r.rawValue)")
                    }
                } footer: {
                    Text("Tell us what's wrong with this AI response. Reports help us improve the Coach's safety instructions.")
                }
                Section {
                    Toggle("Email the response to Ayuvo", isOn: $sendEmail)
                        .accessibilityIdentifier("coach.report.email")
                } footer: {
                    Text("Only this response and the reason are included. Nothing is sent unless you send the email.")
                }
                if done {
                    Section { Label("Thanks. The response was reported.", systemImage: "checkmark.circle.fill") }
                }
            }
            .navigationTitle("Report this response")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Send report") { submit() }
                        .disabled(reason == nil || done)
                        .accessibilityIdentifier("coach.report.send")
                }
            }
            .sheet(isPresented: $showMail, onDismiss: { dismiss() }) {
                if let reason {
                    CoachReportMailView(subject: "Ayuvo AI response report", body: CoachReport.emailBody(message, reason: reason))
                        .ignoresSafeArea()
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func submit() {
        guard let reason else { return }
        onReported()
        done = true
        guard sendEmail else {
            Task { try? await Task.sleep(for: .seconds(1.2)); dismiss() }
            return
        }
        if MFMailComposeViewController.canSendMail() {
            showMail = true
        } else {
            var c = URLComponents()
            c.scheme = "mailto"
            c.path = AppLinks.supportEmail
            c.queryItems = [URLQueryItem(name: "subject", value: "Ayuvo AI response report"),
                            URLQueryItem(name: "body", value: CoachReport.emailBody(message, reason: reason))]
            if let url = c.url { openURL(url) }
            dismiss()
        }
    }
}

/// The system mail sheet, shown inside the app.
struct CoachReportMailView: UIViewControllerRepresentable {
    let subject: String
    let body: String

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIViewController(context: Context) -> MFMailComposeViewController {
        let vc = MFMailComposeViewController()
        vc.setToRecipients([AppLinks.supportEmail])
        vc.setSubject(subject)
        vc.setMessageBody(body, isHTML: false)
        vc.mailComposeDelegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ controller: MFMailComposeViewController, context: Context) {}

    final class Coordinator: NSObject, MFMailComposeViewControllerDelegate {
        func mailComposeController(_ controller: MFMailComposeViewController, didFinishWith result: MFMailComposeResult, error: Error?) {
            controller.dismiss(animated: true)
        }
    }
}
