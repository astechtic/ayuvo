import SwiftUI

struct Gemma4ModelSettingsView: View {
    let onAvailabilityChange: () -> Void

    @State private var modelManager = Gemma4LocalModelManager.shared
    @State private var showDeleteConfirmation = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                SettingsLabel(String(localized: "gemma.name", defaultValue: "Gemma 4 E2B", table: "LocalModels", comment: "Gemma 4 on-device model settings"), systemImage: "cpu", tint: SettingsTint.ai)
                    .font(.body.weight(.medium))
                Spacer()
                Text(statusLabel)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(statusColor)
            }

            Text(statusDescription)
                .font(.caption)
                .foregroundStyle(.secondary)

            if let progress = modelManager.downloadProgress {
                ProgressView(value: progress)
                    .tint(AppColors.calorie)
                    .accessibilityLabel(String(localized: "gemma.downloadProgress", defaultValue: "Gemma 4 download progress", table: "LocalModels", comment: "Gemma 4 on-device model settings"))
                    .accessibilityValue(Text(progress, format: .percent))
            } else if modelManager.state == .verifying {
                ProgressView()
                    .tint(AppColors.calorie)
                    .accessibilityLabel(String(localized: "gemma.verifyingDownload", defaultValue: "Verifying Gemma 4 download", table: "LocalModels", comment: "Gemma 4 on-device model settings"))
            }

            HStack {
                if isDownloading {
                    Button(String(localized: "common.cancel", defaultValue: "Cancel", table: "LocalModels", comment: "On-device model settings"), role: .cancel) {
                        modelManager.cancelDownload()
                    }
                    .buttonStyle(.bordered)
                } else {
                    Button(downloadButtonTitle) {
                        Task {
                            if modelManager.isDownloaded {
                                await modelManager.prepare()
                            } else {
                                await modelManager.download()
                            }
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AppColors.calorie)
                    .disabled(
                        !modelManager.isEligible
                            || modelManager.state.isBusy
                            || modelManager.state == .ready
                    )
                }

                if modelManager.hasStoredData {
                    Button(String(localized: "common.delete", defaultValue: "Delete", table: "LocalModels", comment: "On-device model settings"), role: .destructive) {
                        showDeleteConfirmation = true
                    }
                    .buttonStyle(.bordered)
                    .disabled(modelManager.state.isBusy)
                }
            }

            HStack(spacing: 12) {
                Link("Apache 2.0", destination: Gemma4LocalModelManager.licenseURL)
                    .font(.caption2)

                Link(destination: Gemma4LocalModelManager.sourceURL) {
                    Label(String(localized: "gemma.modelSource", defaultValue: "Model source", table: "LocalModels", comment: "Gemma 4 on-device model settings"), systemImage: "arrow.up.right.square")
                        .font(.caption2)
                }
            }
        }
        .padding(.vertical, 4)
        .onAppear { modelManager.refresh() }
        .onChange(of: modelManager.isSelectable) { _, _ in
            onAvailabilityChange()
        }
        .confirmationDialog(
            String(localized: "gemma.deleteTitle", defaultValue: "Delete Gemma 4?", table: "LocalModels", comment: "Gemma 4 on-device model settings"),
            isPresented: $showDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button(String(localized: "common.deleteModel", defaultValue: "Delete Model", table: "LocalModels", comment: "On-device model settings"), role: .destructive) {
                deleteModel()
            }
            Button(String(localized: "common.cancel", defaultValue: "Cancel", table: "LocalModels", comment: "On-device model settings"), role: .cancel) { }
        } message: {
            Text(String(localized: "gemma.deleteMessage", defaultValue: "The downloaded model and its compiled cache will be removed from this iPhone. You can download it again later.", table: "LocalModels", comment: "Gemma 4 on-device model settings"))
        }
    }

    private var statusLabel: String {
        guard modelManager.isEligible else {
            return String(localized: "gemma.requires8GB", defaultValue: "Requires an 8 GB RAM device", table: "LocalModels", comment: "Gemma 4 on-device model settings")
        }
        switch modelManager.state {
        case .notDownloaded:
            return String(localized: "common.notDownloaded", defaultValue: "Not downloaded", table: "LocalModels", comment: "On-device model settings")
        case .downloaded:
            return String(localized: "common.downloaded", defaultValue: "Downloaded", table: "LocalModels", comment: "On-device model settings")
        case .downloading(let progress):
            return progress.formatted(.percent.precision(.fractionLength(0)))
        case .verifying:
            return String(localized: "common.verifying", defaultValue: "Verifying", table: "LocalModels", comment: "On-device model settings")
        case .preparing:
            return String(localized: "common.preparing", defaultValue: "Preparing", table: "LocalModels", comment: "On-device model settings")
        case .ready:
            return String(localized: "common.ready", defaultValue: "Ready", table: "LocalModels", comment: "On-device model settings")
        case .generating:
            return String(localized: "common.inUse", defaultValue: "In use", table: "LocalModels", comment: "On-device model settings")
        case .failed:
            return String(localized: "common.needsAttention", defaultValue: "Needs attention", table: "LocalModels", comment: "On-device model settings")
        }
    }

    private var statusColor: Color {
        guard modelManager.isEligible else { return .secondary }
        switch modelManager.state {
        case .downloaded, .ready:
            return .green
        case .failed:
            return .red
        case .notDownloaded, .downloading, .verifying, .preparing, .generating:
            return .secondary
        }
    }

    private var statusDescription: String {
        guard modelManager.isEligible else {
            return String(localized: "gemma.unsupportedDescription", defaultValue: "This on-device image and text model is available on iPhones with at least 8 GB of physical memory.", table: "LocalModels", comment: "Gemma 4 on-device model settings")
        }

        switch modelManager.state {
        case .notDownloaded:
            return String(localized: "gemma.downloadDescription", defaultValue: "2.59 GB download. Ayuvo checks for an additional 1 GB of free installation headroom.", table: "LocalModels", comment: "Gemma 4 on-device model settings")
        case .downloaded:
            if let size = modelManager.installedSizeDescription {
                return String(localized: "gemma.storedWithSize", defaultValue: "Verified and stored locally (\(size)). Tap Prepare now, or it will load on first use.", table: "LocalModels", comment: "Gemma 4 on-device model settings")
            }
            return String(localized: "gemma.stored", defaultValue: "Verified and stored locally. Tap Prepare now, or it will load on first use.", table: "LocalModels", comment: "Gemma 4 on-device model settings")
        case .downloading:
            return String(localized: "gemma.downloading", defaultValue: "Downloading the pinned Gemma 4 model… Keep Ayuvo open until it finishes.", table: "LocalModels", comment: "Gemma 4 on-device model settings")
        case .verifying:
            return String(localized: "gemma.checking", defaultValue: "Checking the exact file size and SHA-256 before installation…", table: "LocalModels", comment: "Gemma 4 on-device model settings")
        case .preparing:
            return String(localized: "gemma.compiling", defaultValue: "Compiling and loading the LiteRT-LM Metal runtime…", table: "LocalModels", comment: "Gemma 4 on-device model settings")
        case .ready:
            return String(localized: "gemma.readyDescription", defaultValue: "Ready for private, offline food images and text.", table: "LocalModels", comment: "Gemma 4 on-device model settings")
        case .generating:
            return String(localized: "gemma.generating", defaultValue: "Generating locally on this iPhone…", table: "LocalModels", comment: "Gemma 4 on-device model settings")
        case .failed(let message):
            return message
        }
    }

    private var downloadButtonTitle: String {
        switch modelManager.state {
        case .ready:
            return String(localized: "common.ready", defaultValue: "Ready", table: "LocalModels", comment: "On-device model settings")
        default:
            return modelManager.isDownloaded
                ? String(localized: "common.prepare", defaultValue: "Prepare", table: "LocalModels", comment: "On-device model settings")
                : String(localized: "common.download", defaultValue: "Download", table: "LocalModels", comment: "On-device model settings")
        }
    }

    private var isDownloading: Bool {
        if case .downloading = modelManager.state { return true }
        return false
    }

    private func deleteModel() {
        do {
            try modelManager.delete()
            AIProviderSettings.replaceDeletedLocalGemmaSelections()
            onAvailabilityChange()
        } catch {
            modelManager.refresh()
        }
    }
}
