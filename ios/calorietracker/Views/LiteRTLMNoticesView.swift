import SwiftUI
import UIKit

struct LiteRTLMNoticesView: View {
    var body: some View {
        BundledNoticesView(
            url: Self.noticesURL(in: .main),
            title: String(localized: "notices.title", defaultValue: "LiteRT-LM Notices", table: "LocalModels", comment: "Third-party notices screen"),
            accessibilityLabel: String(localized: "notices.accessibilityLabel", defaultValue: "LiteRT-LM third-party notices", table: "LocalModels", comment: "Third-party notices screen")
        )
    }

    static func noticesURL(in bundle: Bundle) -> URL? {
        bundle.url(
            forResource: "THIRD_PARTY_NOTICES_LiteRTLM_v0.16.0",
            withExtension: "txt"
        )
    }
}

struct WhisperBaseNoticesView: View {
    var body: some View {
        BundledNoticesView(
            url: Self.noticesURL(in: .main),
            title: "Whisper Base · MIT",
            accessibilityLabel: "Whisper Base · MIT"
        )
    }

    static func noticesURL(in bundle: Bundle) -> URL? {
        bundle.url(
            forResource: "THIRD_PARTY_NOTICES_WhisperBase",
            withExtension: "txt"
        )
    }
}

struct BundledNoticesView: View {
    private enum LoadState {
        case loading
        case loaded(String)
        case failed(String)
    }

    let url: URL?
    let title: String
    let accessibilityLabel: String

    @State private var loadState: LoadState = .loading

    var body: some View {
        Group {
            switch loadState {
            case .loading:
                ProgressView(String(localized: "notices.loading", defaultValue: "Loading notices…", table: "LocalModels", comment: "Third-party notices screen"))
            case .loaded(let text):
                SelectableNoticeTextView(text: text)
                    .accessibilityLabel(accessibilityLabel)
            case .failed(let message):
                ContentUnavailableView(
                    String(localized: "notices.unavailable", defaultValue: "Notices Unavailable", table: "LocalModels", comment: "Third-party notices screen"),
                    systemImage: "doc.text.magnifyingglass",
                    description: Text(message)
                )
            }
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .task { await loadNotices() }
    }

    private func loadNotices() async {
        guard case .loading = loadState else { return }
        guard let url else {
            loadState = .failed(String(localized: "notices.fileMissing", defaultValue: "The bundled notice file could not be found.", table: "LocalModels", comment: "Third-party notices screen"))
            return
        }

        do {
            let text = try await Task.detached(priority: .utility) {
                try String(contentsOf: url, encoding: .utf8)
            }.value
            loadState = .loaded(text)
        } catch {
            loadState = .failed(String(localized: "notices.fileOpenFailed", defaultValue: "The bundled notice file could not be opened.", table: "LocalModels", comment: "Third-party notices screen"))
        }
    }
}

private struct SelectableNoticeTextView: UIViewRepresentable {
    let text: String

    func makeUIView(context: Context) -> UITextView {
        let textView = UITextView(usingTextLayoutManager: true)
        textView.isEditable = false
        textView.isSelectable = true
        textView.alwaysBounceVertical = true
        textView.backgroundColor = .clear
        textView.font = .monospacedSystemFont(ofSize: 12, weight: .regular)
        textView.textColor = .label
        textView.textContainerInset = UIEdgeInsets(top: 16, left: 12, bottom: 24, right: 12)
        textView.accessibilityTraits = .staticText
        return textView
    }

    func updateUIView(_ textView: UITextView, context: Context) {
        guard textView.text != text else { return }
        textView.text = text
        textView.setContentOffset(.zero, animated: false)
    }
}
