import SwiftUI

/// Settings › AI & Speech › AI Providers › **Models** (docs/ai-models.md §3).
///
/// The list of saved configurations. The role sections below it still edit the model they run, and
/// they write through the same store, so what this list shows is always what the app would send.
struct AIModelProfilesSection: View {
    /// Bumped by the caller when a local model finishes installing, so the rows re-read.
    var revision: Int = 0
    var onChange: () -> Void = {}

    @State private var profiles: [AIModelProfile] = []
    @State private var editing: AIModelProfile?
    @State private var adding = false
    @State private var pendingDelete: AIModelProfile?

    var body: some View {
        Section {
            AISettingsSubsectionHeader(
                title: "Models",
                systemImage: "square.stack.3d.up",
                infoTopic: .primaryAI
            )

            ForEach(profiles) { profile in
                Button {
                    editing = profile
                } label: {
                    AIModelProfileRow(profile: profile)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("settings.model.\(profile.id)")
                .swipeActions(edge: .trailing) {
                    Button(role: .destructive) { pendingDelete = profile } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
            }

            Button {
                adding = true
            } label: {
                Label {
                    Text("Add model")
                } icon: {
                    SettingsIcon("plus.circle.fill", tint: SettingsTint.ai)
                }
            }
            .accessibilityIdentifier("settings.model.add")
        } footer: {
            Text("Each model keeps its own provider, model name and key. Pick which one answers below, or from the menu in a chat.")
        }
        .accessibilityIdentifier("settings.models")
        .onAppear(perform: reload)
        .onChange(of: revision) { _, _ in reload() }
        .sheet(item: $editing) { profile in
            AIModelProfileEditor(profile: profile) { reload(); onChange() }
        }
        .sheet(isPresented: $adding) {
            AIModelProfileEditor(profile: nil) { reload(); onChange() }
        }
        .confirmationDialog(
            pendingDelete.map { "Delete “\($0.displayName)”?" } ?? "",
            isPresented: Binding(get: { pendingDelete != nil },
                                 set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) {
                if let pendingDelete {
                    AIProviderSettings.deleteProfile(id: pendingDelete.id)
                }
                pendingDelete = nil
                reload()
                onChange()
            }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        } message: {
            // Rule 3: only this profile's own key goes. The provider key has other owners.
            Text("Any role using it will need a model chosen again. The provider's API key is kept.")
        }
    }

    private func reload() { profiles = AIProviderSettings.profiles }
}

private struct AIModelProfileRow: View {
    let profile: AIModelProfile

    private var usedBy: String? {
        let roles = AIProviderSettings.roles(using: profile.id)
        guard !roles.isEmpty else { return nil }
        return roles.map(\.settingsName).joined(separator: ", ")
    }

    var body: some View {
        HStack(spacing: 12) {
            if let provider = profile.provider {
                AIProviderBrandIcon(provider: provider, style: .settings)
            } else {
                SettingsIcon("questionmark.circle", tint: SettingsTint.ai)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(profile.displayName)
                    .foregroundStyle(.primary)
                Text(profile.modelID.isEmpty ? profile.providerToken
                     : "\(profile.providerToken) · \(profile.modelID)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if let usedBy {
                    Text(usedBy)
                        .font(.caption2)
                        .foregroundStyle(AppColors.calorie)
                }
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.tertiary)
        }
    }
}

extension AIRole {
    /// How this role is named on the Settings screen.
    var settingsName: String {
        switch self {
        case .image: String(localized: "Primary")
        case .text: String(localized: "Text")
        case .imageFallback: String(localized: "Image fallback")
        case .textFallback: String(localized: "Text fallback")
        }
    }
}

/// Add or edit one saved model.
struct AIModelProfileEditor: View {
    let profile: AIModelProfile?
    let onSave: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var provider: AIProvider = .gemini
    @State private var model = ""
    @State private var nickname = ""
    @State private var baseURL = ""
    @State private var apiKey = ""
    @State private var showKey = false
    @State private var vertexProject = ""
    @State private var vertexLocation = "global"

    private var isNew: Bool { profile == nil }
    private var needsURL: Bool { provider.requiresCustomEndpoint || provider == .ollama }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker(selection: $provider) {
                        ForEach(AIProvider.allCases.filter(\.isAvailableOnCurrentDevice)) { row in
                            Label {
                                Text(row.displayName)
                            } icon: {
                                AIProviderBrandIcon(provider: row)
                            }
                            .tag(row)
                        }
                    } label: {
                        Text("Provider")
                    }
                    .onChange(of: provider) { _, new in
                        if model.isEmpty || !new.models.contains(model) {
                            model = new.defaultModel
                        }
                    }

                    if provider.models.isEmpty || provider.supportsCustomModelName {
                        HStack {
                            Text("Model")
                            Spacer()
                            TextField(provider.defaultModel.isEmpty ? "model-name" : provider.defaultModel,
                                      text: $model)
                                .multilineTextAlignment(.trailing)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                            if !provider.models.isEmpty {
                                Menu {
                                    ForEach(provider.models, id: \.self) { preset in
                                        Button(preset) { model = preset }
                                    }
                                } label: {
                                    Image(systemName: "list.bullet.circle")
                                        .foregroundStyle(AppColors.calorie)
                                }
                            }
                        }
                    } else {
                        Picker("Model", selection: $model) {
                            ForEach(provider.models, id: \.self) { Text(provider.modelDisplayName($0)).tag($0) }
                        }
                    }

                    if provider == .vertexAI {
                        HStack {
                            Text("Project")
                            Spacer()
                            TextField("my-gcp-project", text: $vertexProject)
                                .multilineTextAlignment(.trailing)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                        }
                        HStack {
                            Text("Location")
                            Spacer()
                            TextField("global", text: $vertexLocation)
                                .multilineTextAlignment(.trailing)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                        }
                    }

                    if needsURL {
                        HStack {
                            Text(provider.requiresCustomEndpoint ? "Base URL" : "Server URL")
                            Spacer()
                            TextField(provider.requiresCustomEndpoint
                                      ? "https://your-endpoint.com/v1" : provider.baseURL,
                                      text: $baseURL)
                                .multilineTextAlignment(.trailing)
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                                .keyboardType(.URL)
                        }
                    }
                }

                if provider.requiresAPIKey {
                    Section {
                        HStack {
                            Group {
                                if showKey {
                                    TextField(provider.apiKeyPlaceholder, text: $apiKey)
                                } else {
                                    SecureField(provider.apiKeyPlaceholder, text: $apiKey)
                                }
                            }
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            Button { showKey.toggle() } label: {
                                Image(systemName: showKey ? "eye.fill" : "eye.slash.fill")
                                    .foregroundStyle(.secondary)
                            }
                            .buttonStyle(.plain)
                        }
                    } header: {
                        Text("API key")
                    } footer: {
                        // The store keeps the provider-level reference when the value matches, so a
                        // rotation on the per-provider screen still reaches this model.
                        Text("Leave this as the provider's key, or type a different one to use only for this model.")
                    }
                }

                Section {
                    TextField("Optional name", text: $nickname)
                } header: {
                    Text("Name")
                }
            }
            .navigationTitle(isNew ? Text("Add model") : Text("Edit model"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(model.trimmingCharacters(in: .whitespaces).isEmpty
                                  && !provider.models.isEmpty)
                }
            }
            .onAppear(perform: load)
        }
    }

    private func load() {
        guard let profile else {
            provider = AIProviderSettings.selectedProvider
            model = provider.defaultModel
            return
        }
        provider = profile.provider ?? .gemini
        model = profile.modelID
        nickname = profile.nickname
        baseURL = profile.baseURL ?? ""
        vertexProject = profile.vertexProjectID ?? ""
        vertexLocation = profile.vertexLocation ?? "global"
        apiKey = AIProviderSettings.apiKey(for: profile) ?? ""
    }

    private func save() {
        let url = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        if var existing = profile {
            existing.providerToken = provider.rawValue
            existing.modelID = AIProvider.normalizedModelID(model)
            existing.baseURL = url.isEmpty ? nil : url
            if provider == .vertexAI {
                let project = vertexProject.trimmingCharacters(in: .whitespacesAndNewlines)
                existing.vertexProjectID = project.isEmpty ? nil : project
                let location = vertexLocation.trimmingCharacters(in: .whitespacesAndNewlines)
                existing.vertexLocation = location.isEmpty ? "global" : location
            } else {
                existing.vertexProjectID = nil
                existing.vertexLocation = nil
            }
            if !name.isEmpty { existing.nickname = name }
            existing.updatedMs = Int(Date().timeIntervalSince1970 * 1000)
            AIProviderSettings.upsert(existing)
            if provider.requiresAPIKey {
                AIProviderSettings.setAPIKey(apiKey, for: existing)
            }
        } else if let id = AIProviderSettings.addProfile(
            provider: provider, model: AIProvider.normalizedModelID(model),
            baseURL: url.isEmpty ? nil : url, nickname: name.isEmpty ? nil : name
        ), var created = AIProviderSettings.profile(id: id) {
            if provider == .vertexAI {
                let project = vertexProject.trimmingCharacters(in: .whitespacesAndNewlines)
                created.vertexProjectID = project.isEmpty ? nil : project
                let location = vertexLocation.trimmingCharacters(in: .whitespacesAndNewlines)
                created.vertexLocation = location.isEmpty ? "global" : location
                AIProviderSettings.upsert(created)
            }
            if provider.requiresAPIKey { AIProviderSettings.setAPIKey(apiKey, for: created) }
        }
        onSave()
        dismiss()
    }
}
