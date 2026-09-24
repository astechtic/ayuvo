import SwiftUI

/// Coach › ⋯ › **Model** (docs/ai-models.md §8).
///
/// Picks which saved model answers *this* conversation. The choice belongs to the chat: making it
/// the app default is the separate, labelled row at the bottom.
struct CoachModelPickerSheet: View {
    let selectedProfileID: String?
    /// The bare provider an older row may still carry (the §30 "Use on-device Coach" flow).
    let selectedProvider: AIProvider?
    let onPick: (String?, AIProvider?) -> Void
    let onSetDefault: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var profiles: [AIModelProfile] = []

    private var defaultProfileID: String? {
        AIProviderSettings.rolePointers[.image]?.profileID
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        onPick(nil, nil)
                        dismiss()
                    } label: {
                        row(title: String(localized: "Default for new chats"),
                            subtitle: defaultProfileSubtitle,
                            icon: "checkmark.seal",
                            checked: selectedProfileID == nil && selectedProvider == nil)
                    }
                    .accessibilityIdentifier("coach.model.default")
                } footer: {
                    Text("Whatever Settings says answers this chat.")
                }

                Section {
                    ForEach(profiles) { profile in
                        Button {
                            onPick(profile.id, profile.provider)
                            dismiss()
                        } label: {
                            row(title: profile.displayName,
                                subtitle: subtitle(for: profile),
                                icon: nil,
                                provider: profile.provider,
                                checked: profile.id == selectedProfileID)
                        }
                        .accessibilityIdentifier("coach.model.\(profile.id)")
                        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                            Button {
                                onSetDefault(profile.id)
                            } label: {
                                Label("Use for new chats", systemImage: "pin")
                            }
                            .tint(AppColors.calorie)
                        }
                    }
                } header: {
                    Text("Saved models")
                } footer: {
                    Text("Swipe a model to use it for new chats too. Add or edit models in Settings → AI & Speech.")
                }

                // A conversation the records flow pinned to a bare provider keeps showing what it
                // actually uses, even though no saved model names it.
                if selectedProfileID == nil, let selectedProvider {
                    Section {
                        row(title: selectedProvider.displayName,
                            subtitle: String(localized: "Chosen for this chat"),
                            icon: nil,
                            provider: selectedProvider,
                            checked: true)
                    }
                }
            }
            .navigationTitle(Text("Model"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .onAppear { profiles = AIProviderSettings.profiles }
        }
        .accessibilityIdentifier("coach.model.picker")
    }

    private var defaultProfileSubtitle: String {
        guard let profile = AIProviderSettings.profile(id: defaultProfileID) else {
            return AIProviderSettings.selectedProvider.displayName
        }
        return profile.displayName
    }

    private func subtitle(for profile: AIModelProfile) -> String {
        profile.modelID.isEmpty ? profile.providerToken
            : "\(profile.providerToken) · \(profile.modelID)"
    }

    @ViewBuilder
    private func row(title: String, subtitle: String, icon: String?,
                     provider: AIProvider? = nil, checked: Bool) -> some View {
        HStack(spacing: 12) {
            if let provider {
                AIProviderBrandIcon(provider: provider, style: .settings)
            } else if let icon {
                SettingsIcon(icon, tint: SettingsTint.ai)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(.primary)
                Text(subtitle).font(.footnote).foregroundStyle(.secondary)
            }
            Spacer()
            if checked {
                Image(systemName: "checkmark")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AppColors.calorie)
            }
        }
    }
}
