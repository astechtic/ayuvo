import SwiftUI
import Photos
import PhotosUI
import PDFKit
import UIKit
import HealthKit
import StoreKit
import WidgetKit
import AVFoundation
import Speech
import UniformTypeIdentifiers

struct SettingsKeyboardDismissalModifier: ViewModifier {
    func body(content: Content) -> some View {
        content
            .scrollDismissesKeyboard(.interactively)
            .onDisappear {
                dismissKeyboard()
            }
    }

    private func dismissKeyboard() {
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
    }
}

enum AISettingsInfoTopic {
    case primaryAI
    case textAI
    case textFallback
    case imageFallback
    case speechToText
    case speechFallback

    var title: String {
        switch self {
        case .primaryAI: "Primary AI"
        case .textAI: "Text AI"
        case .textFallback: "Text AI Fallback"
        case .imageFallback: "Image AI Fallback"
        case .speechToText: "Speech-to-Text"
        case .speechFallback: "STT Fallback"
        }
    }

    var message: String {
        switch self {
        case .primaryAI:
            "Handles every request that includes a photo. It also handles text-only requests when Use Separate Text Provider is off. When that switch is on, Text AI handles text-only work instead."
        case .textAI:
            "An optional provider for requests without photos, including typed food, Coach chat, voice transcripts, goals, and advice. When Use Separate Text Provider is off, Primary AI handles these requests."
        case .textFallback:
            "Retries a failed text-only request once. It backs up Text AI when the separate provider is enabled; otherwise it backs up Primary AI for text-only work. It never receives photos."
        case .imageFallback:
            "Retries a failed request containing one or more photos. The first attempt always uses Primary AI. This fallback is never used for text-only requests. You may use the same provider with a different model."
        case .speechToText:
            "Converts microphone audio into text only. The transcript then follows the normal text route: Text AI when enabled, otherwise Primary AI. Matching provider API keys are reused unless you save a separate STT key."
        case .speechFallback:
            "Retries transcription when the selected remote STT provider fails. It only produces a transcript; that transcript still follows the normal text AI route. Native iOS speech already uses Apple's offline and online recognition recovery, so a separate STT fallback is available only for remote providers."
        }
    }
}

struct AISettingsSubsectionHeader: View {
    let title: String
    let systemImage: String
    let infoTopic: AISettingsInfoTopic

    @State private var isShowingInfo = false

    var body: some View {
        HStack(spacing: 8) {
            Label {
                Text(title)
                    .textCase(.uppercase)
            } icon: {
                Image(systemName: systemImage)
            }
            .accessibilityAddTraits(.isHeader)

            Spacer(minLength: 8)

            Button {
                isShowingInfo = true
            } label: {
                Image(systemName: "info.circle")
                    .font(.body.weight(.semibold))
                    .frame(width: 44, height: 44)
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("About \(title)")
            .accessibilityHint("Shows how this provider is used")
        }
        .font(.system(.subheadline, design: .rounded, weight: .bold))
        .foregroundStyle(AppColors.calorie)
        .alert(infoTopic.title, isPresented: $isShowingInfo) {
            Button("Got it", role: .cancel) { }
        } message: {
            Text(infoTopic.message)
        }
    }
}
