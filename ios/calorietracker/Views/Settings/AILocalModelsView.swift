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

/// One catalogue model, laid out like Gemma's card: what it is, where it stands (not downloaded →
/// downloaded → ready), Download / Prepare / Delete, and its licence and source pages.
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

    private var modality: String {
        descriptor.supportsVision
            ? String(localized: "text and images") : String(localized: "text only")
    }

    private var blockedReason: String? {
        guard let manager else { return nil }
        if !manager.isEligible { return gateText }
        if descriptor.requiresAuth, !manager.isDownloaded, Gemma4LocalModelManager.huggingFaceToken == nil {
            return String(localized: "Gated — needs a Hugging Face token and accepted terms")
        }
        return nil
    }

    private var isDownloading: Bool {
        if let manager, case .downloading = manager.state { return true }
        return false
    }

    /// The message from a failed download or preparation, or nil.
    private var failure: String? {
        guard let manager, case .failed(let message) = manager.state else { return nil }
        return message
    }

    private var statusLabel: (text: String, color: Color)? {
        guard let manager, manager.isEligible else { return nil }
        switch manager.state {
        case .downloading(let progress): return ("\(Int(progress * 100))%", .secondary)
        case .verifying: return (String(localized: "Verifying"), .secondary)
        case .preparing: return (String(localized: "Preparing"), .secondary)
        case .generating: return (String(localized: "In use"), .green)
        case .failed: return (String(localized: "Needs attention"), .red)
        case .ready: return (String(localized: "Ready"), .green)
        case .downloaded:
            return manager.isSelectable
                ? (String(localized: "Ready"), .green) : (String(localized: "Downloaded"), .green)
        case .notDownloaded: return nil
        }
    }

    private var subtitle: String {
        if let blockedReason { return "\(sizeText) · \(blockedReason)" }
        guard let manager, manager.isDownloaded else { return "\(sizeText) · \(modality)" }
        if manager.isSelectable {
            return String(localized: "Ready for private, offline use. \(sizeText) · \(modality)")
        }
        return String(localized: "Verified and stored locally (\(sizeText)). Tap Prepare now, or it will load on first use.")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Label {
                    Text(descriptor.displayName)
                } icon: {
                    SettingsIcon("cpu", tint: SettingsTint.ai)
                }
                .font(.body.weight(.medium))
                Spacer()
                if let statusLabel {
                    Text(statusLabel.text)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(statusLabel.color)
                }
            }

            Text(subtitle)
                .font(.caption)
                .foregroundStyle(.secondary)

            // A failed download used to fall through to the Download button with nothing said,
            // which made a refusal from Hugging Face look like a button that did nothing at all.
            if let failure {
                Text(failure)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if let manager, let progress = manager.downloadProgress {
                ProgressView(value: progress).tint(AppColors.calorie)
            } else if let manager, manager.state == .verifying || manager.state == .preparing {
                ProgressView().tint(AppColors.calorie)
            }

            actions

            links
        }
        .padding(.vertical, 4)
        .onAppear {
            manager = Gemma4LocalModelManager.manager(for: descriptor)
            manager?.refresh()
        }
        .onChange(of: revision) { _, _ in manager = Gemma4LocalModelManager.manager(for: descriptor) }
        // Preparing writes the marker that makes the model selectable, so the provider and model
        // pickers have to re-read as soon as it flips.
        .onChange(of: manager?.isSelectable) { _, _ in onChange() }
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

    @ViewBuilder
    private var actions: some View {
        HStack {
            if isDownloading {
                Button("Cancel", role: .cancel) { manager?.cancelDownload() }
                    .buttonStyle(.bordered)
            } else if let manager, manager.isDownloaded {
                Button(manager.state == .ready || manager.isSelectable ? "Ready" : "Prepare") {
                    Task { await manager.prepare(); onChange() }
                }
                .buttonStyle(.borderedProminent)
                .tint(AppColors.calorie)
                .disabled(!manager.isEligible || manager.state.isBusy
                          || manager.state == .ready || manager.isSelectable)
                .accessibilityIdentifier("settings.localModel.\(descriptor.id).prepare")
            } else if blockedReason == nil {
                Button(failure == nil ? "Download" : "Try again") {
                    Task { await manager?.download(); onChange() }
                }
                .buttonStyle(.borderedProminent)
                .tint(AppColors.calorie)
                .disabled(manager?.state.isBusy ?? true)
                .accessibilityIdentifier("settings.localModel.\(descriptor.id).download")
            }

            if let manager, manager.hasStoredData, !isDownloading {
                Button("Delete", role: .destructive) { confirmingDelete = true }
                    .buttonStyle(.bordered)
                    .disabled(manager.state.isBusy)
                    .accessibilityIdentifier("settings.localModel.\(descriptor.id).delete")
            }
        }
    }

    /// Licence text, the model's own page, and — for a gated model — the page where its terms are
    /// accepted, each one tap away rather than something to go and find.
    @ViewBuilder
    private var links: some View {
        HStack(spacing: 12) {
            if let licenseURL = descriptor.licenseURL {
                Link(descriptor.licenseName.isEmpty ? String(localized: "License") : licenseTitle,
                     destination: licenseURL)
                    .font(.caption2)
                    .accessibilityIdentifier("settings.localModel.\(descriptor.id).license")
            }
            if let page = descriptor.repositoryURL ?? descriptor.sourceURL {
                Link(destination: page) {
                    Label("Model source", systemImage: "arrow.up.right.square")
                        .font(.caption2)
                }
                .accessibilityIdentifier("settings.localModel.\(descriptor.id).source")
            }
            if descriptor.requiresAuth, let page = descriptor.repositoryURL {
                Link(destination: page) {
                    Label("Accept terms", systemImage: "checkmark.seal")
                        .font(.caption2)
                }
                .accessibilityIdentifier("settings.localModel.\(descriptor.id).terms")
            }
        }
    }

    /// "Apache-2.0" reads as itself; an SPDX `LicenseRef-…` id is not something to show a person.
    private var licenseTitle: String {
        descriptor.licenseName.hasPrefix("LicenseRef-") ? String(localized: "License terms") : descriptor.licenseName
    }
}
