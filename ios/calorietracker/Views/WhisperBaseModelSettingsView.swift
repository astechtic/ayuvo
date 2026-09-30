import SwiftUI

struct WhisperBaseModelSettingsView: View {
    @Binding var selectedProvider: SpeechProvider
    let onAvailabilityChange: () -> Void

    @State private var modelManager = WhisperBaseModelManager.shared
    @State private var showDeleteConfirmation = false

    init(
        selectedProvider: Binding<SpeechProvider>,
        onAvailabilityChange: @escaping () -> Void = { }
    ) {
        self._selectedProvider = selectedProvider
        self.onAvailabilityChange = onAvailabilityChange
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                SettingsLabel(String(localized: "whisper.name", defaultValue: "Whisper Base", table: "LocalModels", comment: "Whisper Base on-device speech model settings"), systemImage: "waveform.badge.mic", tint: SettingsTint.speech)
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
                    .accessibilityLabel(String(localized: "whisper.downloadProgress", defaultValue: "Whisper Base download progress", table: "LocalModels", comment: "Whisper Base on-device speech model settings"))
                    .accessibilityValue(Text(progress, format: .percent))
            }

            HStack {
                if isDownloading {
                    Button(String(localized: "common.cancel", defaultValue: "Cancel", table: "LocalModels", comment: "On-device model settings"), role: .cancel) {
                        modelManager.cancelDownload()
                    }
                    .buttonStyle(.bordered)
                } else {
                    Button(downloadButtonTitle) {
                        Task { await modelManager.download() }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(AppColors.calorie)
                    .disabled(modelManager.state.isBusy || modelManager.isDownloaded)
                }

                if modelManager.hasStoredData {
                    Button(String(localized: "common.delete", defaultValue: "Delete", table: "LocalModels", comment: "On-device model settings"), role: .destructive) {
                        showDeleteConfirmation = true
                    }
                    .buttonStyle(.bordered)
                    .disabled(modelManager.state.isBusy)
                }
            }

            ViewThatFits(in: .horizontal) {
                HStack(spacing: 14) {
                    attributionLinks
                }
                VStack(alignment: .leading, spacing: 6) {
                    attributionLinks
                }
            }
            .font(.caption)
        }
        .padding(.vertical, 4)
        .onAppear { modelManager.refresh() }
        .onChange(of: modelManager.isDownloaded) { _, _ in
            onAvailabilityChange()
        }
        .confirmationDialog(
            String(localized: "whisper.deleteTitle", defaultValue: "Delete Whisper Base?", table: "LocalModels", comment: "Whisper Base on-device speech model settings"),
            isPresented: $showDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button(String(localized: "common.deleteModel", defaultValue: "Delete Model", table: "LocalModels", comment: "On-device model settings"), role: .destructive) {
                deleteModel()
            }
            Button(String(localized: "common.cancel", defaultValue: "Cancel", table: "LocalModels", comment: "On-device model settings"), role: .cancel) { }
        } message: {
            Text(String(localized: "whisper.deleteMessage", defaultValue: "The downloaded model will be removed from this iPhone. You can download it again later.", table: "LocalModels", comment: "Whisper Base on-device speech model settings"))
        }
    }

    private var statusLabel: String {
        switch modelManager.state {
        case .notDownloaded:
            String(localized: "common.notDownloaded", defaultValue: "Not downloaded", table: "LocalModels", comment: "On-device model settings")
        case .downloaded:
            String(localized: "common.downloaded", defaultValue: "Downloaded", table: "LocalModels", comment: "On-device model settings")
        case .downloading(let progress):
            progress.formatted(.percent.precision(.fractionLength(0)))
        case .preparing:
            String(localized: "common.preparing", defaultValue: "Preparing", table: "LocalModels", comment: "On-device model settings")
        case .ready:
            String(localized: "common.ready", defaultValue: "Ready", table: "LocalModels", comment: "On-device model settings")
        case .transcribing:
            String(localized: "common.inUse", defaultValue: "In use", table: "LocalModels", comment: "On-device model settings")
        case .failed:
            String(localized: "common.needsAttention", defaultValue: "Needs attention", table: "LocalModels", comment: "On-device model settings")
        }
    }

    private var statusColor: Color {
        switch modelManager.state {
        case .downloaded, .ready:
            .green
        case .failed:
            .red
        case .notDownloaded, .downloading, .preparing, .transcribing:
            .secondary
        }
    }

    private var statusDescription: String {
        switch modelManager.state {
        case .notDownloaded:
            String(localized: "whisper.about", defaultValue: "About 147 MB. Runs fully on-device after download.", table: "LocalModels", comment: "Whisper Base on-device speech model settings")
        case .downloaded, .ready:
            if let size = modelManager.installedSizeDescription {
                String(localized: "whisper.storedWithSize", defaultValue: "Stored locally (\(size)). No audio leaves this iPhone.", table: "LocalModels", comment: "Whisper Base on-device speech model settings")
            } else {
                String(localized: "whisper.stored", defaultValue: "Stored locally. No audio leaves this iPhone.", table: "LocalModels", comment: "Whisper Base on-device speech model settings")
            }
        case .downloading:
            String(localized: "whisper.downloading", defaultValue: "Downloading the multilingual Core ML model…", table: "LocalModels", comment: "Whisper Base on-device speech model settings")
        case .preparing:
            String(localized: "whisper.optimizing", defaultValue: "Optimizing the model for this iPhone…", table: "LocalModels", comment: "Whisper Base on-device speech model settings")
        case .transcribing:
            String(localized: "whisper.transcribing", defaultValue: "Transcribing locally…", table: "LocalModels", comment: "Whisper Base on-device speech model settings")
        case .failed(let message):
            message
        }
    }

    private var downloadButtonTitle: String {
        modelManager.isDownloaded
            ? String(localized: "common.downloaded", defaultValue: "Downloaded", table: "LocalModels", comment: "On-device model settings")
            : String(localized: "common.download", defaultValue: "Download", table: "LocalModels", comment: "On-device model settings")
    }

    private var isDownloading: Bool {
        if case .downloading = modelManager.state { return true }
        return false
    }

    @ViewBuilder
    private var attributionLinks: some View {
        Link(destination: WhisperBaseModelManager.modelSourceURL) {
            Label(
                String(localized: "gemma.modelSource", defaultValue: "Model Source", table: "LocalModels", comment: "Gemma 4 on-device model settings"),
                systemImage: "shippingbox"
            )
        }
        Link("Whisper Base · MIT", destination: WhisperBaseModelManager.modelLicenseURL)
        Link("WhisperKit 1.1.0 · MIT", destination: WhisperBaseModelManager.whisperKitLicenseURL)
    }

    private func deleteModel() {
        do {
            try modelManager.delete()
            SpeechSettings.replaceDeletedWhisperSelections()
            if selectedProvider == .whisperBase {
                selectedProvider = .nativeIOS
                SpeechSettings.selectedProvider = .nativeIOS
            }
            onAvailabilityChange()
        } catch {
            modelManager.refresh()
        }
    }
}
