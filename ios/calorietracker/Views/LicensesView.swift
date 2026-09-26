import SwiftUI

/// Settings › Legal › Licenses: the MIT notice of the base project (retained as the licence
/// requires) plus every bundled third-party notice.
struct LicensesView: View {
    var body: some View {
        List {
            Section {
                Link(destination: AppLinks.githubURL) {
                    SettingsLabel(String(localized: "View source on GitHub"), systemImage: "chevron.left.forwardslash.chevron.right", tint: SettingsTint.legal)
                }
                .tint(.primary)
            } footer: {
                Text("Ayuvo is open source under the MIT licence. Read the code, report issues or contribute.")
            }

            Section {
                NavigationLink {
                    BundledNoticesView(
                        url: Bundle.main.url(forResource: "BASE_PROJECT_LICENSE", withExtension: "txt"),
                        title: String(localized: "Base project — MIT"),
                        accessibilityLabel: String(localized: "Base project MIT licence")
                    )
                } label: {
                    SettingsLabel(String(localized: "Base project — MIT"), systemImage: "doc.text.fill", tint: SettingsTint.legal)
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
                    SettingsLabel(String(localized: "Exercise library, Open Food Facts, muscle glyphs, provider logos"), systemImage: "list.bullet.rectangle.fill", tint: SettingsTint.legal)
                }
                NavigationLink {
                    LiteRTLMNoticesView()
                } label: {
                    SettingsLabel(LocalModelStrings.text("notices.title", defaultValue: "LiteRT-LM Notices"), systemImage: "cpu", tint: SettingsTint.ai)
                }
                NavigationLink {
                    WhisperBaseNoticesView()
                } label: {
                    SettingsLabel("Whisper Base · MIT", systemImage: "waveform", tint: SettingsTint.speech)
                }
            }
        }
        .navigationTitle(String(localized: "Licenses"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
