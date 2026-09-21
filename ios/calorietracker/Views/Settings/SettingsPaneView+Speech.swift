import SwiftUI

/// AI & Speech › Speech-to-Text.
extension SettingsPaneView {
    @ViewBuilder
    var speechPane: some View {
        Section {
                AISettingsSubsectionHeader(
                    title: "Speech-to-Text",
                    systemImage: "waveform",
                    infoTopic: .speechToText
                )

                Picker(selection: $selectedSpeechProvider) {
                    ForEach(SpeechProvider.availableProviders) { provider in
                        Label {
                            Text(provider.displayName)
                        } icon: {
                            SpeechProviderBrandIcon(provider: provider)
                        }
                        .tag(provider)
                    }
                } label: {
                    Label {
                        Text("Provider")
                    } icon: {
                        SpeechProviderBrandIcon(provider: selectedSpeechProvider)
                    }
                }
                .pickerStyle(.menu)
                .accessibilityIdentifier("settings.speech.provider")
                .tint(.secondary)
                .id("speech-provider-\(localModelAvailabilityRevision)")
                .onChange(of: selectedSpeechProvider) { _, newProvider in
                    SpeechSettings.selectedProvider = newProvider
                    speechApiKeyText = SpeechSettings.apiKey(for: newProvider) ?? ""
                    selectedSpeechLanguage = SpeechSettings.selectedLanguage(for: newProvider)
                    if newProvider == selectedSpeechFallbackProvider,
                       let alternate = SpeechProvider.remoteProviders.first(where: { $0 != newProvider }) {
                        selectSpeechFallbackProvider(alternate)
                    }
                }

                WhisperBaseModelSettingsView(selectedProvider: $selectedSpeechProvider) {
                    selectedSpeechProvider = SpeechSettings.selectedProvider
                    selectedSpeechFallbackProvider = SpeechSettings.selectedFallbackProvider
                    speechFallbackEnabled = SpeechSettings.fallbackEnabled
                    selectedSpeechLanguage = SpeechSettings.selectedLanguage(for: selectedSpeechProvider)
                    selectedSpeechFallbackLanguage = SpeechSettings.selectedLanguage(for: selectedSpeechFallbackProvider)
                    localModelAvailabilityRevision += 1
                }

                Picker(selection: $selectedSpeechLanguage) {
                    ForEach(SpeechLanguage.allCases) { language in
                        Text(language.displayName).tag(language)
                    }
                } label: {
                    Label {
                        Text("Language")
                    } icon: {
                        Image(systemName: "globe")
                            .foregroundStyle(AppColors.calorie)
                    }
                }
                .pickerStyle(.menu)
                .tint(.secondary)
                .onChange(of: selectedSpeechLanguage) { _, newLanguage in
                    SpeechSettings.setLanguage(newLanguage, for: selectedSpeechProvider)
                }

                if selectedSpeechProvider.requiresAPIKey {
                    HStack {
                        Label {
                            Text("API Key")
                        } icon: {
                            Image(systemName: "key.fill")
                                .foregroundStyle(AppColors.calorie)
                        }
                        Spacer()
                        Group {
                            if showSpeechAPIKey {
                                TextField(selectedSpeechProvider.apiKeyPlaceholder, text: $speechApiKeyText)
                            } else {
                                SecureField(selectedSpeechProvider.apiKeyPlaceholder, text: $speechApiKeyText)
                            }
                        }
                        .textFieldStyle(.plain)
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onChange(of: speechApiKeyText) { _, newValue in
                            SpeechSettings.setAPIKey(newValue.isEmpty ? nil : newValue, for: selectedSpeechProvider)
                        }
                        Button {
                            showSpeechAPIKey.toggle()
                        } label: {
                            Image(systemName: showSpeechAPIKey ? "eye.fill" : "eye.slash.fill")
                                .foregroundStyle(.secondary)
                                .font(.subheadline)
                        }
                        .buttonStyle(.plain)
                    }
                }

                speechFallbackSettingsRows
        }
        .listRowBackground(AppColors.appCard)
    }

    @ViewBuilder
    var speechFallbackSettingsRows: some View {
        AISettingsSubsectionHeader(
            title: "STT Fallback",
            systemImage: "waveform.badge.plus",
            infoTopic: .speechFallback
        )

        if selectedSpeechProvider != .nativeIOS {
            Toggle(isOn: $speechFallbackEnabled) {
                Label("Enable STT Fallback", systemImage: "arrow.triangle.2.circlepath")
            }
            .tint(AppColors.calorie)
            .onChange(of: speechFallbackEnabled) { _, newValue in
                SpeechSettings.fallbackEnabled = newValue
                if newValue, selectedSpeechFallbackProvider == selectedSpeechProvider,
                   let alternate = speechFallbackProviderOptions.first {
                    selectSpeechFallbackProvider(alternate)
                }
            }

            if speechFallbackEnabled {
                Picker(selection: $selectedSpeechFallbackProvider) {
                    ForEach(speechFallbackProviderOptions) { provider in
                        Label {
                            Text(provider.displayName)
                        } icon: {
                            SpeechProviderBrandIcon(provider: provider)
                        }
                        .tag(provider)
                    }
                } label: {
                    Label {
                        Text("Provider")
                    } icon: {
                        SpeechProviderBrandIcon(provider: selectedSpeechFallbackProvider)
                    }
                }
                .pickerStyle(.menu)
                .tint(.secondary)
                .id("speech-fallback-provider-\(localModelAvailabilityRevision)")
                .onAppear {
                    if !speechFallbackProviderOptions.contains(selectedSpeechFallbackProvider),
                       let alternate = speechFallbackProviderOptions.first {
                        selectSpeechFallbackProvider(alternate)
                    }
                }
                .onChange(of: selectedSpeechFallbackProvider) { _, newProvider in
                    selectSpeechFallbackProvider(newProvider)
                }

                Picker(selection: $selectedSpeechFallbackLanguage) {
                    ForEach(SpeechLanguage.allCases) { language in
                        Text(language.displayName).tag(language)
                    }
                } label: {
                    Label("Language", systemImage: "globe")
                }
                .pickerStyle(.menu)
                .tint(.secondary)
                .onChange(of: selectedSpeechFallbackLanguage) { _, newLanguage in
                    SpeechSettings.setLanguage(newLanguage, for: selectedSpeechFallbackProvider)
                }

                if selectedSpeechFallbackProvider.requiresAPIKey {
                    HStack {
                        Label("API Key", systemImage: "key.fill")
                        Spacer()
                        Group {
                            if showSpeechFallbackAPIKey {
                                TextField(selectedSpeechFallbackProvider.apiKeyPlaceholder, text: $speechFallbackApiKeyText)
                            } else {
                                SecureField(selectedSpeechFallbackProvider.apiKeyPlaceholder, text: $speechFallbackApiKeyText)
                            }
                        }
                        .textFieldStyle(.plain)
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onChange(of: speechFallbackApiKeyText) { _, newValue in
                            SpeechSettings.setAPIKey(newValue.isEmpty ? nil : newValue, for: selectedSpeechFallbackProvider)
                        }
                        Button {
                            showSpeechFallbackAPIKey.toggle()
                        } label: {
                            Image(systemName: showSpeechFallbackAPIKey ? "eye.fill" : "eye.slash.fill")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    var speechFallbackProviderOptions: [SpeechProvider] {
        SpeechProvider.availableBatchProviders.filter { $0 != selectedSpeechProvider }
    }

    func selectSpeechFallbackProvider(_ newProvider: SpeechProvider) {
        SpeechSettings.selectedFallbackProvider = newProvider
        selectedSpeechFallbackLanguage = SpeechSettings.selectedLanguage(for: newProvider)
        speechFallbackApiKeyText = SpeechSettings.apiKey(for: newProvider) ?? ""
    }
}
