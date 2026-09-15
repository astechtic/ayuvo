import SwiftUI

/// Settings › Legal › Licenses: the MIT notice of the base project (retained as the licence
/// requires) plus every bundled third-party notice.
struct LicensesView: View {
    var body: some View {
        List {
            Section {
                NavigationLink {
                    BundledNoticesView(
                        url: Bundle.main.url(forResource: "BASE_PROJECT_LICENSE", withExtension: "txt"),
                        title: String(localized: "Base project — MIT"),
                        accessibilityLabel: String(localized: "Base project MIT licence")
                    )
                } label: {
                    Label(String(localized: "Base project — MIT"), systemImage: "doc.text")
                }
            } footer: {
                Text("Ayuvo is built on an MIT-licensed open-source project. The original notice is retained as required by the licence.")
            }

            Section(String(localized: "Third-party notices")) {
                NavigationLink {
                    BundledNoticesView(
                        url: Bundle.main.url(forResource: "THIRD_PARTY_NOTICES", withExtension: "txt"),
                        title: String(localized: "Third-party notices"),
                        accessibilityLabel: String(localized: "Third-party notices")
                    )
                } label: {
                    Label(String(localized: "Exercise library, Open Food Facts, muscle glyphs, provider logos"), systemImage: "list.bullet.rectangle")
                }
                NavigationLink {
                    LiteRTLMNoticesView()
                } label: {
                    Label(LocalModelStrings.text("notices.title", defaultValue: "LiteRT-LM Notices"), systemImage: "cpu")
                }
                NavigationLink {
                    WhisperBaseNoticesView()
                } label: {
                    Label("Whisper Base · MIT", systemImage: "waveform")
                }
            }
        }
        .navigationTitle(String(localized: "Licenses"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
