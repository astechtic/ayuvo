import SwiftUI

/// The catalogue models beyond Gemma, plus the Hugging Face token a gated one needs
/// (docs/ai-models.md §7).
///
/// Gemma keeps its own row, `Gemma4ModelSettingsView`, untouched: onboarding embeds that view, and
/// changing it would change onboarding. This list is new components beside it.
struct AILocalModelsSection: View {
    var onChange: () -> Void = {}

    @State private var revision = 0
    @State private var token = Gemma4LocalModelManager.huggingFaceToken ?? ""
    @State private var showToken = false

    private var extras: [LocalModelDescriptor] {
        LocalModelCatalog.chatModels.filter { $0.id != LocalModelCatalog.gemmaCatalogID }
    }

    private var needsToken: Bool { extras.contains(where: \.requiresAuth) }

    var body: some View {
        if !extras.isEmpty {
            ForEach(extras) { descriptor in
                AILocalModelRow(descriptor: descriptor, revision: revision) {
                    revision += 1
                    onChange()
                }
                .accessibilityIdentifier("settings.localModel.\(descriptor.id)")
            }

            if needsToken {
                HStack {
                    Label {
                        Text("Hugging Face token")
                    } icon: {
                        SettingsIcon("key.fill", tint: SettingsTint.keys)
                    }
                    Spacer()
                    Group {
                        if showToken {
                            TextField("hf_...", text: $token)
                        } else {
                            SecureField("hf_...", text: $token)
                        }
                    }
                    .textFieldStyle(.plain)
                    .multilineTextAlignment(.trailing)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onChange(of: token) { _, newValue in
                        Gemma4LocalModelManager.huggingFaceToken = newValue
                        revision += 1
                    }
                    Button { showToken.toggle() } label: {
                        Image(systemName: showToken ? "eye.fill" : "eye.slash.fill")
                            .foregroundStyle(.secondary)
                            .font(.subheadline)
                    }
                    .buttonStyle(.plain)
                }
                .accessibilityIdentifier("settings.hfToken")

                Text("Some models are gated on Hugging Face and will not download without a token. Accept the model's terms there first.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

/// One catalogue model: its size, what it can do, and why it cannot be downloaded when it cannot.
private struct AILocalModelRow: View {
    let descriptor: LocalModelDescriptor
    let revision: Int
    let onChange: () -> Void

    @State private var manager: Gemma4LocalModelManager?
    @State private var confirmingDelete = false

    private var sizeText: String {
        ByteCountFormatter.string(fromByteCount: descriptor.sizeBytes, countStyle: .file)
    }

    private var gateText: String {
        let gb = max(1, Int(descriptor.minimumMemoryBytes / (1024 * 1024 * 1024)))
        return String(localized: "Needs \(gb) GB of RAM")
    }

    private var blockedReason: String? {
        guard let manager else { return nil }
        if !manager.isEligible { return gateText }
        if descriptor.requiresAuth, Gemma4LocalModelManager.huggingFaceToken == nil {
            return String(localized: "Gated — needs a Hugging Face token and accepted terms")
        }
        return nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Label {
                    Text(descriptor.displayName)
                } icon: {
                    SettingsIcon("cpu", tint: SettingsTint.ai)
                }
                Spacer()
                trailing
            }
            Text(subtitle)
                .font(.footnote)
                .foregroundStyle(.secondary)
            // A failed download used to fall through to the Download button with nothing said,
            // which made a refusal from Hugging Face look like a button that did nothing at all.
            if let failure {
                Text(failure)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .fixedSize(horizontal: false, vertical: true)
            }
            // A gated model needs its terms accepted on its own page, so the page is one tap away
            // rather than something to go and find.
            if descriptor.requiresAuth, let page = descriptor.repositoryURL {
                Link(destination: page) {
                    Label("Accept terms on Hugging Face", systemImage: "arrow.up.right.square")
                        .font(.footnote)
                }
                .accessibilityIdentifier("settings.localModel.\(descriptor.id).terms")
            }
        }
        .onAppear { manager = Gemma4LocalModelManager.manager(for: descriptor) }
        .onChange(of: revision) { _, _ in manager = Gemma4LocalModelManager.manager(for: descriptor) }
        .confirmationDialog("Delete \(descriptor.displayName)?", isPresented: $confirmingDelete,
                            titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                try? manager?.delete()
                AIProviderSettings.replaceDeletedLocalGemmaSelections()
                onChange()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("The file is removed from this iPhone. Any role using it will need a model chosen again.")
        }
    }

    /// The message from a failed download, or nil.
    private var failure: String? {
        guard let manager, case .failed(let message) = manager.state else { return nil }
        return message
    }

    @ViewBuilder
    private var trailing: some View {
        if let manager, manager.isDownloaded {
            Button(role: .destructive) { confirmingDelete = true } label: { Text("Delete") }
                .buttonStyle(.plain)
                .foregroundStyle(.red)
        } else if let manager, case .downloading(let progress) = manager.state {
            HStack(spacing: 8) {
                Text("\(Int(progress * 100))%").foregroundStyle(.secondary)
                Button { manager.cancelDownload() } label: { Text("Cancel") }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
            }
        } else if let manager, manager.state == .verifying || manager.state == .preparing {
            ProgressView().controlSize(.small)
        } else if blockedReason == nil {
            Button {
                Task { await manager?.download(); onChange() }
            } label: {
                Text(failure == nil ? "Download" : "Try again")
            }
            .buttonStyle(.plain)
            .foregroundStyle(AppColors.calorie)
        }
    }

    private var subtitle: String {
        if let blockedReason { return "\(sizeText) · \(blockedReason)" }
        let modality = descriptor.supportsVision
            ? String(localized: "text and images") : String(localized: "text only")
        return "\(sizeText) · \(modality)"
    }
}
