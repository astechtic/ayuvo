import SwiftUI

/// AI & Speech › AI Providers and Custom Instructions.
extension SettingsPaneView {
    @ViewBuilder
    var aiProvidersPane: some View {
        Section {
                AISettingsSubsectionHeader(
                    title: "Primary AI",
                    systemImage: "sparkles",
                    infoTopic: .primaryAI
                )

                Picker(selection: $selectedProvider) {
                    ForEach(AIProvider.visionProviders) { provider in
                        Label {
                            Text(provider.displayName)
                        } icon: {
                            AIProviderBrandIcon(provider: provider)
                        }
                        .tag(provider)
                    }
                } label: {
                    Label {
                        Text("Provider")
                    } icon: {
                        AIProviderBrandIcon(provider: selectedProvider)
                    }
                }
                .pickerStyle(.menu)
                .tint(.secondary)
                .id("primary-provider-\(localModelAvailabilityRevision)")
                .onChange(of: selectedProvider) { _, newProvider in
                    AIProviderSettings.selectedProvider = newProvider
                    selectedModel = newProvider.defaultModel
                    AIProviderSettings.selectedModel = newProvider.defaultModel
                    apiKeyText = AIProviderSettings.apiKey(for: newProvider) ?? ""
                    customBaseURL = AIProviderSettings.customBaseURL(for: newProvider) ?? ""
                }

                if selectedProvider.supportsCustomModelName {
                    // Free-form TextField for any model ID, with optional preset suggestions menu
                    // (e.g., OpenRouter has presets but lets user type any of openrouter.ai/models).
                    HStack {
                        Label {
                            Text("Model")
                        } icon: {
                            Image(systemName: "brain")
                                .foregroundStyle(AppColors.calorie)
                        }
                        Spacer()
                        TextField(
                            primaryModelPlaceholder,
                            text: $selectedModel
                        )
                            .textFieldStyle(.plain)
                            .multilineTextAlignment(.trailing)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .onChange(of: selectedModel) { _, newModel in
                                AIProviderSettings.selectedModel = newModel
                            }
                        if !selectedProvider.models.isEmpty {
                            Menu {
                                ForEach(selectedProvider.models, id: \.self) { model in
                                    Button(model) {
                                        selectedModel = model
                                        AIProviderSettings.selectedModel = model
                                    }
                                }
                            } label: {
                                Image(systemName: "list.bullet.circle")
                                    .foregroundStyle(AppColors.calorie)
                            }
                        }
                    }
                } else {
                    Picker(selection: $selectedModel) {
                        ForEach(selectedProvider.models, id: \.self) { model in
                            Text(model).tag(model)
                        }
                    } label: {
                        Label {
                            Text("Model")
                        } icon: {
                            Image(systemName: "brain")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(.secondary)
                    .onAppear {
                        if !selectedProvider.models.contains(selectedModel) {
                            selectedModel = selectedProvider.defaultModel
                            AIProviderSettings.selectedModel = selectedModel
                        }
                    }
                    .onChange(of: selectedModel) { _, newModel in
                        AIProviderSettings.selectedModel = newModel
                    }
                }

                if selectedProvider.requiresAPIKey {
                    HStack {
                        Label {
                            Text("API Key")
                        } icon: {
                            Image(systemName: "key.fill")
                                .foregroundStyle(AppColors.calorie)
                        }
                        Spacer()
                        Group {
                            if showAPIKey {
                                TextField(selectedProvider.apiKeyPlaceholder, text: $apiKeyText)
                            } else {
                                SecureField(selectedProvider.apiKeyPlaceholder, text: $apiKeyText)
                            }
                        }
                        .textFieldStyle(.plain)
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onChange(of: apiKeyText) { _, newValue in
                            let t = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
                            AIProviderSettings.setAPIKey(t.isEmpty ? nil : t, for: selectedProvider)
                        }
                        Button {
                            showAPIKey.toggle()
                        } label: {
                            Image(systemName: showAPIKey ? "eye.fill" : "eye.slash.fill")
                                .foregroundStyle(.secondary)
                                .font(.subheadline)
                        }
                        .buttonStyle(.plain)
                    }
                }

                if selectedProvider == .ollama || selectedProvider.requiresCustomEndpoint {
                    HStack {
                        Label {
                            Text(selectedProvider.requiresCustomEndpoint ? "Base URL" : "Server URL")
                        } icon: {
                            Image(systemName: "link")
                                .foregroundStyle(AppColors.calorie)
                        }
                        Spacer()
                        TextField(
                            selectedProvider.requiresCustomEndpoint
                                ? "https://your-endpoint.com/v1"
                                : selectedProvider.baseURL,
                            text: $customBaseURL
                        )
                            .textFieldStyle(.plain)
                            .multilineTextAlignment(.trailing)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .keyboardType(.URL)
                            .onChange(of: customBaseURL) { _, newValue in
                                let t = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
                                AIProviderSettings.setCustomBaseURL(t.isEmpty ? nil : t, for: selectedProvider)
                                reconcileImageFallbackModelIfDuplicate()
                                reconcileTextFallbackModelIfDuplicate()
                            }
                    }

                    HStack {
                        Label {
                            Text("Request Timeout")
                        } icon: {
                            Image(systemName: "timer")
                                .foregroundStyle(AppColors.calorie)
                        }
                        Spacer()
                        requestTimeoutInput
                        Text("sec")
                            .foregroundStyle(.secondary)
                    }
                }

                if selectedProvider == .openrouter
                    || (separateTextProviderEnabled && selectedTextProvider == .openrouter)
                    || (fallbackEnabled && selectedFallbackProvider == .openrouter)
                    || (textFallbackEnabled && selectedTextFallbackProvider == .openrouter) {
                    Picker("OpenRouter Reasoning Effort", selection: $openRouterReasoningEffort) {
                        ForEach(OpenRouterReasoningEffort.allCases) { effort in
                            Text(effort.title).tag(effort)
                        }
                    }
                    Text("Applies to all OpenRouter requests. Supported levels vary by model. Higher effort may take longer and cost more. Auto keeps the model default; incomplete responses retry with Low.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                // Only OpenAI-compatible + Anthropic send a token cap; Gemini is
                // left uncapped, so hide this for Gemini.
                if selectedProvider.apiFormat != .gemini {
                    HStack {
                        Label {
                            Text("Max Response Tokens")
                        } icon: {
                            Image(systemName: "text.append")
                                .foregroundStyle(AppColors.calorie)
                        }
                        Spacer()
                        maxResponseTokensInput
                    }
                }

                Label(
                    LocalModelStrings.text(
                        "settings.onDeviceModel",
                        defaultValue: "On-Device Model"
                    ),
                    systemImage: "iphone.gen3.radiowaves.left.and.right"
                )
                    .font(.system(.subheadline, design: .rounded, weight: .bold))
                    .foregroundStyle(AppColors.calorie)
                    .textCase(.uppercase)
                    .accessibilityAddTraits(.isHeader)

                Gemma4ModelSettingsView {
                    selectedProvider = AIProviderSettings.selectedProvider
                    selectedModel = AIProviderSettings.selectedModel
                    selectedTextProvider = AIProviderSettings.selectedTextProvider
                    selectedTextModel = AIProviderSettings.selectedTextModel
                    separateTextProviderEnabled = AIProviderSettings.separateTextProviderEnabled
                    selectedFallbackProvider = AIProviderSettings.selectedFallbackProvider
                    selectedFallbackModel = AIProviderSettings.selectedFallbackModel
                    fallbackEnabled = AIProviderSettings.fallbackEnabled
                    selectedTextFallbackProvider = AIProviderSettings.selectedTextFallbackProvider
                    selectedTextFallbackModel = AIProviderSettings.selectedTextFallbackModel
                    textFallbackEnabled = AIProviderSettings.textFallbackEnabled
                    localModelAvailabilityRevision += 1
                }

                AISettingsSubsectionHeader(
                    title: "Text AI",
                    systemImage: "text.bubble.fill",
                    infoTopic: .textAI
                )

            Toggle(isOn: $separateTextProviderEnabled) {
                Label {
                    Text("Use Separate Text Provider")
                } icon: {
                    Image(systemName: "text.bubble.fill")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .tint(AppColors.calorie)
            .onChange(of: separateTextProviderEnabled) { _, isEnabled in
                AIProviderSettings.separateTextProviderEnabled = isEnabled
            }

            if separateTextProviderEnabled {
                Picker(selection: $selectedTextProvider) {
                    ForEach(AIProvider.textProviders) { provider in
                        Label {
                            Text(provider.displayName)
                        } icon: {
                            AIProviderBrandIcon(provider: provider)
                        }
                        .tag(provider)
                    }
                } label: {
                    Label {
                        Text("Provider")
                    } icon: {
                        AIProviderBrandIcon(provider: selectedTextProvider)
                    }
                }
                .pickerStyle(.menu)
                .tint(.secondary)
                .id("text-provider-\(localModelAvailabilityRevision)")
                .onChange(of: selectedTextProvider) { _, newProvider in
                    AIProviderSettings.selectedTextProvider = newProvider
                    selectedTextModel = newProvider.defaultTextModel
                    AIProviderSettings.selectedTextModel = selectedTextModel
                    textApiKeyText = AIProviderSettings.apiKey(for: newProvider) ?? ""
                    textBaseURL = AIProviderSettings.customBaseURL(for: newProvider) ?? ""
                }

                if selectedTextProvider == .appleIntelligence {
                    HStack {
                        Label("Model", systemImage: "brain")
                        Spacer()
                        Text(selectedTextProvider.defaultTextModel)
                            .foregroundStyle(.secondary)
                    }

                    appleIntelligenceAvailabilityRow
                } else if selectedTextProvider.supportsCustomModelName {
                    HStack {
                        Label("Model", systemImage: "brain")
                        Spacer()
                        TextField(textModelPlaceholder, text: $selectedTextModel)
                            .textFieldStyle(.plain)
                            .multilineTextAlignment(.trailing)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .onChange(of: selectedTextModel) { _, newModel in
                                AIProviderSettings.selectedTextModel = newModel
                            }
                        if !selectedTextProvider.textModels.isEmpty {
                            Menu {
                                ForEach(selectedTextProvider.textModels, id: \.self) { model in
                                    Button(model) {
                                        selectedTextModel = model
                                        AIProviderSettings.selectedTextModel = model
                                    }
                                }
                            } label: {
                                Image(systemName: "list.bullet.circle")
                                    .foregroundStyle(AppColors.calorie)
                            }
                        }
                    }
                } else {
                    Picker(selection: $selectedTextModel) {
                        ForEach(selectedTextProvider.textModels, id: \.self) { model in
                            Text(model).tag(model)
                        }
                    } label: {
                        Label("Model", systemImage: "brain")
                    }
                    .pickerStyle(.menu)
                    .tint(.secondary)
                    .onAppear {
                        if !selectedTextProvider.textModels.contains(selectedTextModel) {
                            selectedTextModel = selectedTextProvider.defaultTextModel
                            AIProviderSettings.selectedTextModel = selectedTextModel
                        }
                    }
                    .onChange(of: selectedTextModel) { _, newModel in
                        AIProviderSettings.selectedTextModel = newModel
                    }
                }

                if selectedTextProvider.requiresAPIKey {
                    HStack {
                        Label("API Key", systemImage: "key.fill")
                        Spacer()
                        Group {
                            if showTextAPIKey {
                                TextField(selectedTextProvider.apiKeyPlaceholder, text: $textApiKeyText)
                            } else {
                                SecureField(selectedTextProvider.apiKeyPlaceholder, text: $textApiKeyText)
                            }
                        }
                        .textFieldStyle(.plain)
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onChange(of: textApiKeyText) { _, newValue in
                            let trimmed = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
                            AIProviderSettings.setAPIKey(trimmed.isEmpty ? nil : trimmed, for: selectedTextProvider)
                        }
                        Button {
                            showTextAPIKey.toggle()
                        } label: {
                            Image(systemName: showTextAPIKey ? "eye.fill" : "eye.slash.fill")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                }

                if selectedTextProvider == .ollama || selectedTextProvider.requiresCustomEndpoint {
                    HStack {
                        Label(
                            selectedTextProvider.requiresCustomEndpoint ? "Base URL" : "Server URL",
                            systemImage: "link"
                        )
                        Spacer()
                        TextField(
                            selectedTextProvider.requiresCustomEndpoint
                                ? "https://your-endpoint.com/v1"
                                : selectedTextProvider.baseURL,
                            text: $textBaseURL
                        )
                        .textFieldStyle(.plain)
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                        .onChange(of: textBaseURL) { _, newValue in
                            let trimmed = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
                            AIProviderSettings.setCustomBaseURL(trimmed.isEmpty ? nil : trimmed, for: selectedTextProvider)
                            reconcileTextFallbackModelIfDuplicate()
                        }
                    }

                    if !selectedProvider.usesConfigurableRequestTimeout {
                        HStack {
                            Label("Request Timeout", systemImage: "timer")
                            Spacer()
                            requestTimeoutInput
                            Text("sec").foregroundStyle(.secondary)
                        }
                    }
                }
            }

                AISettingsSubsectionHeader(
                    title: "Text AI Fallback",
                    systemImage: "text.bubble.fill",
                    infoTopic: .textFallback
                )
                textFallbackSettingsRows

                AISettingsSubsectionHeader(
                    title: "Image AI Fallback",
                    systemImage: "photo.badge.arrow.down",
                    infoTopic: .imageFallback
                )

                Toggle(isOn: $fallbackEnabled) {
                    Label {
                        Text("Enable Image Fallback")
                    } icon: {
                        Image(systemName: "arrow.triangle.2.circlepath")
                            .foregroundStyle(AppColors.calorie)
                    }
                }
                .tint(AppColors.calorie)
                .onChange(of: fallbackEnabled) { _, newValue in
                    AIProviderSettings.fallbackEnabled = newValue
                }

                if fallbackEnabled {
                    // Fallback provider list shows all currently available vision providers;
                    // the same provider as primary IS allowed
                    // (so e.g. Gemini Pro primary + Gemini Flash fallback works for capacity diversity).
                    // The collision is handled at the model layer below + at the runtime check in
                    // AIProviderSettings.currentImageFallbackConfig.
                    Picker(selection: $selectedFallbackProvider) {
                        ForEach(AIProvider.visionProviders) { provider in
                            Label {
                                Text(provider.displayName)
                            } icon: {
                                AIProviderBrandIcon(provider: provider)
                            }
                            .tag(provider)
                        }
                    } label: {
                        Label {
                            Text("Provider")
                        } icon: {
                            AIProviderBrandIcon(provider: selectedFallbackProvider)
                        }
                    }
                    .pickerStyle(.menu)
                    .tint(.secondary)
                    .id("image-fallback-provider-\(localModelAvailabilityRevision)")
                    .onChange(of: selectedFallbackProvider) { _, newProvider in
                        selectFallbackProvider(newProvider)
                    }

                    if selectedFallbackProvider.supportsCustomModelName {
                        // Free-form TextField + preset Menu, mirrors primary AI Provider section.
                        // When fallback provider == primary, the preset menu hides the primary's model.
                        HStack {
                            Label {
                                Text("Model")
                            } icon: {
                                Image(systemName: "brain")
                                    .foregroundStyle(AppColors.calorie)
                            }
                            Spacer()
                            TextField(
                                fallbackModelPlaceholder,
                                text: $selectedFallbackModel
                            )
                            .textFieldStyle(.plain)
                            .multilineTextAlignment(.trailing)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .onChange(of: selectedFallbackModel) { _, newModel in
                                AIProviderSettings.selectedFallbackModel = newModel
                            }
                            if !fallbackModelPresetOptions.isEmpty {
                                Menu {
                                    ForEach(fallbackModelPresetOptions, id: \.self) { model in
                                        Button(model) {
                                            selectedFallbackModel = model
                                            AIProviderSettings.selectedFallbackModel = model
                                        }
                                    }
                                } label: {
                                    Image(systemName: "list.bullet.circle")
                                        .foregroundStyle(AppColors.calorie)
                                }
                            }
                        }
                    } else {
                        // Same provider + same server → exclude the primary's model so
                        // user can't accidentally pick an identical config.
                        let modelOptions: [String] = {
                            if fallbackSharesPrimaryServer {
                                return selectedFallbackProvider.models.filter { $0 != selectedModel }
                            }
                            return selectedFallbackProvider.models
                        }()
                        if !modelOptions.isEmpty {
                            Picker(selection: $selectedFallbackModel) {
                                ForEach(modelOptions, id: \.self) { model in
                                    Text(model).tag(model)
                                }
                            } label: {
                                Label {
                                    Text("Model")
                                } icon: {
                                    Image(systemName: "brain")
                                        .foregroundStyle(AppColors.calorie)
                                }
                            }
                            .pickerStyle(.menu)
                            .tint(.secondary)
                            .onChange(of: selectedFallbackModel) { _, newModel in
                                AIProviderSettings.selectedFallbackModel = newModel
                            }
                            .onAppear {
                                if !modelOptions.contains(selectedFallbackModel),
                                   let first = modelOptions.first {
                                    selectedFallbackModel = first
                                    AIProviderSettings.selectedFallbackModel = first
                                }
                            }
                        }
                    }

                    if selectedFallbackProvider.requiresAPIKey {
                        HStack {
                            Label {
                                Text("API Key")
                            } icon: {
                                Image(systemName: "key.fill")
                                    .foregroundStyle(AppColors.calorie)
                            }
                            Spacer()
                            Group {
                                if showFallbackAPIKey {
                                    TextField(selectedFallbackProvider.apiKeyPlaceholder, text: $fallbackApiKeyText)
                                } else {
                                    SecureField(selectedFallbackProvider.apiKeyPlaceholder, text: $fallbackApiKeyText)
                                }
                            }
                            .textFieldStyle(.plain)
                            .multilineTextAlignment(.trailing)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .onChange(of: fallbackApiKeyText) { _, newValue in
                                AIProviderSettings.setAPIKey(newValue.isEmpty ? nil : newValue, for: selectedFallbackProvider)
                            }
                            Button {
                                showFallbackAPIKey.toggle()
                            } label: {
                                Image(systemName: showFallbackAPIKey ? "eye.fill" : "eye.slash.fill")
                                    .foregroundStyle(.secondary)
                                    .font(.subheadline)
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    if selectedFallbackProvider == .ollama || selectedFallbackProvider.requiresCustomEndpoint {
                        HStack {
                            Label {
                                Text(selectedFallbackProvider.requiresCustomEndpoint ? "Base URL" : "Server URL")
                            } icon: {
                                Image(systemName: "link")
                                    .foregroundStyle(AppColors.calorie)
                            }
                            Spacer()
                            TextField(
                                selectedFallbackProvider.requiresCustomEndpoint
                                    ? "https://your-endpoint.com/v1"
                                    : selectedFallbackProvider.baseURL,
                                text: $fallbackBaseURL
                            )
                            .textFieldStyle(.plain)
                            .multilineTextAlignment(.trailing)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .keyboardType(.URL)
                            .onChange(of: fallbackBaseURL) { _, newValue in
                                let t = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
                                AIProviderSettings.setFallbackCustomBaseURL(t.isEmpty ? nil : t, for: selectedFallbackProvider)
                                reconcileImageFallbackModelIfDuplicate()
                            }
                        }

                        if !selectedProvider.usesConfigurableRequestTimeout {
                            HStack {
                                Label {
                                    Text("Request Timeout")
                                } icon: {
                                    Image(systemName: "timer")
                                        .foregroundStyle(AppColors.calorie)
                                }
                                Spacer()
                                requestTimeoutInput
                                Text("sec")
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
        }
        .listRowBackground(AppColors.appCard)
    }

    @ViewBuilder
    var customInstructionsPane: some View {
        Section {
            TextField(
                "I live in Germany, assume European portion sizes. I'm on a bodybuilding cut.",
                text: $customAIInstructions,
                axis: .vertical
            )
            .lineLimit(3...6)
            .autocorrectionDisabled(false)
            .focused($customInstructionsFocused)

            Button {
                AIProviderSettings.userContext = customAIInstructions
                let canonical = AIProviderSettings.userContext
                customAIInstructions = canonical
                savedAIInstructions = canonical
                customInstructionsFocused = false
            } label: {
                HStack {
                    Spacer()
                    Label("Save", systemImage: "checkmark.circle.fill")
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .foregroundStyle(customAIInstructions == savedAIInstructions ? .secondary : AppColors.calorie)
                    Spacer()
                }
            }
            .disabled(customAIInstructions == savedAIInstructions)
        } header: {
            Text("Custom AI Instructions")
        } footer: {
            Text("Optional context sent with every AI request — region, diet, athletic goals, anything you'd otherwise repeat each time. Leave empty to disable.")
        }
        .listRowBackground(AppColors.appCard)
    }

    var requestTimeoutInput: some View {
        EndEditingDecimalTextField(
            text: $requestTimeoutSecondsText,
            focusRequest: 0,
            onEditingChanged: { _ in },
            keyboardType: .numberPad,
            placeholder: "180",
            accessibilityLabel: "Request Timeout"
        )
            .frame(width: 70)
            .onChange(of: requestTimeoutSecondsText) { _, newValue in
                let digits = newValue.filter(\.isNumber)
                if digits != newValue { requestTimeoutSecondsText = digits }
                if let seconds = Int(digits), seconds > 0 {
                    AIProviderSettings.requestTimeoutSeconds = seconds
                }
            }
    }

    @ViewBuilder
    var textFallbackSettingsRows: some View {
        Toggle(isOn: $textFallbackEnabled) {
            Label("Enable Text Fallback", systemImage: "arrow.triangle.2.circlepath")
        }
        .tint(AppColors.calorie)
        .onChange(of: textFallbackEnabled) { _, newValue in
            AIProviderSettings.textFallbackEnabled = newValue
        }

        if textFallbackEnabled {
            Picker(selection: $selectedTextFallbackProvider) {
                ForEach(AIProvider.textProviders) { provider in
                    Label {
                        Text(provider.displayName)
                    } icon: {
                        AIProviderBrandIcon(provider: provider)
                    }
                    .tag(provider)
                }
            } label: {
                Label {
                    Text("Provider")
                } icon: {
                    AIProviderBrandIcon(provider: selectedTextFallbackProvider)
                }
            }
            .pickerStyle(.menu)
            .tint(.secondary)
            .id("text-fallback-provider-\(localModelAvailabilityRevision)")
            .onChange(of: selectedTextFallbackProvider) { _, newProvider in
                selectTextFallbackProvider(newProvider)
            }

            if selectedTextFallbackProvider == .appleIntelligence {
                HStack {
                    Label("Model", systemImage: "brain")
                    Spacer()
                    Text(selectedTextFallbackProvider.defaultTextModel)
                        .foregroundStyle(.secondary)
                }
                appleIntelligenceAvailabilityRow
            } else if selectedTextFallbackProvider.supportsCustomModelName {
                HStack {
                    Label("Model", systemImage: "brain")
                    Spacer()
                    TextField(textFallbackModelPlaceholder, text: $selectedTextFallbackModel)
                        .textFieldStyle(.plain)
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .onChange(of: selectedTextFallbackModel) { _, newModel in
                            AIProviderSettings.selectedTextFallbackModel = newModel
                        }
                    if !textFallbackModelPresetOptions.isEmpty {
                        Menu {
                            ForEach(textFallbackModelPresetOptions, id: \.self) { model in
                                Button(model) {
                                    selectedTextFallbackModel = model
                                    AIProviderSettings.selectedTextFallbackModel = model
                                }
                            }
                        } label: {
                            Image(systemName: "list.bullet.circle")
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                }
            } else if !textFallbackModelPresetOptions.isEmpty {
                Picker(selection: $selectedTextFallbackModel) {
                    ForEach(textFallbackModelPresetOptions, id: \.self) { model in
                        Text(model).tag(model)
                    }
                } label: {
                    Label("Model", systemImage: "brain")
                }
                .pickerStyle(.menu)
                .tint(.secondary)
                .onChange(of: selectedTextFallbackModel) { _, newModel in
                    AIProviderSettings.selectedTextFallbackModel = newModel
                }
                .onAppear {
                    if !textFallbackModelPresetOptions.contains(selectedTextFallbackModel),
                       let first = textFallbackModelPresetOptions.first {
                        selectedTextFallbackModel = first
                        AIProviderSettings.selectedTextFallbackModel = first
                    }
                }
            }

            if selectedTextFallbackProvider.requiresAPIKey {
                HStack {
                    Label("API Key", systemImage: "key.fill")
                    Spacer()
                    Group {
                        if showTextFallbackAPIKey {
                            TextField(selectedTextFallbackProvider.apiKeyPlaceholder, text: $textFallbackApiKeyText)
                        } else {
                            SecureField(selectedTextFallbackProvider.apiKeyPlaceholder, text: $textFallbackApiKeyText)
                        }
                    }
                    .textFieldStyle(.plain)
                    .multilineTextAlignment(.trailing)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .onChange(of: textFallbackApiKeyText) { _, newValue in
                        AIProviderSettings.setAPIKey(newValue.isEmpty ? nil : newValue, for: selectedTextFallbackProvider)
                    }
                    Button {
                        showTextFallbackAPIKey.toggle()
                    } label: {
                        Image(systemName: showTextFallbackAPIKey ? "eye.fill" : "eye.slash.fill")
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                }
            }

            if selectedTextFallbackProvider == .ollama || selectedTextFallbackProvider.requiresCustomEndpoint {
                HStack {
                    Label(selectedTextFallbackProvider.requiresCustomEndpoint ? "Base URL" : "Server URL", systemImage: "link")
                    Spacer()
                    TextField(
                        selectedTextFallbackProvider.requiresCustomEndpoint
                            ? "https://your-endpoint.com/v1"
                            : selectedTextFallbackProvider.baseURL,
                        text: $textFallbackBaseURL
                    )
                    .textFieldStyle(.plain)
                    .multilineTextAlignment(.trailing)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .keyboardType(.URL)
                    .onChange(of: textFallbackBaseURL) { _, newValue in
                        let t = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
                        AIProviderSettings.setFallbackCustomBaseURL(t.isEmpty ? nil : t, for: selectedTextFallbackProvider)
                        reconcileTextFallbackModelIfDuplicate()
                    }
                }
            }
        }
    }

    @ViewBuilder
    var appleIntelligenceAvailabilityRow: some View {
        if #available(iOS 26.0, *) {
            #if canImport(FoundationModels)
            let available = OnDeviceAIService.isAvailable
            Label {
                Text(OnDeviceAIService.availabilityDescription)
                    .foregroundStyle(.secondary)
            } icon: {
                Image(systemName: available ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                    .foregroundStyle(available ? Color.green : Color.orange)
            }
            #else
            Label("Apple Intelligence is unavailable in this build", systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.secondary)
            #endif
        } else {
            Label("Requires iOS 26 or later", systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.secondary)
        }
    }

    var maxResponseTokensInput: some View {
        EndEditingDecimalTextField(
            text: $maxResponseTokensText,
            focusRequest: 0,
            onEditingChanged: { _ in },
            keyboardType: .numberPad,
            placeholder: "1024",
            accessibilityLabel: "Max Response Tokens"
        )
            .frame(width: 90)
            .onChange(of: maxResponseTokensText) { _, newValue in
                let digits = newValue.filter(\.isNumber)
                if digits != newValue { maxResponseTokensText = digits }
                if let tokens = Int(digits), tokens > 0 {
                    AIProviderSettings.maxResponseTokens = tokens
                }
            }
    }

    var resolvedPrimaryBaseURL: String {
        let trimmed = customBaseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? selectedProvider.baseURL : trimmed
    }

    var resolvedFallbackBaseURL: String {
        let trimmed = fallbackBaseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? selectedFallbackProvider.baseURL : trimmed
    }

    /// True when fallback would hit the same provider, model, and server as primary.
    var fallbackSharesPrimaryServer: Bool {
        selectedFallbackProvider == selectedProvider && resolvedPrimaryBaseURL == resolvedFallbackBaseURL
    }

    var fallbackModelPresetOptions: [String] {
        guard fallbackSharesPrimaryServer else {
            return selectedFallbackProvider.models
        }
        return selectedFallbackProvider.models.filter { $0 != selectedModel }
    }

    var resolvedTextPrimaryBaseURL: String {
        let provider = separateTextProviderEnabled ? selectedTextProvider : selectedProvider
        let url = separateTextProviderEnabled ? textBaseURL : customBaseURL
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? provider.baseURL : trimmed
    }

    var resolvedTextFallbackBaseURL: String {
        let trimmed = textFallbackBaseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? selectedTextFallbackProvider.baseURL : trimmed
    }

    var textFallbackSharesPrimaryServer: Bool {
        let primaryProvider = separateTextProviderEnabled ? selectedTextProvider : selectedProvider
        return selectedTextFallbackProvider == primaryProvider &&
            resolvedTextPrimaryBaseURL == resolvedTextFallbackBaseURL
    }

    var textFallbackModelPresetOptions: [String] {
        guard textFallbackSharesPrimaryServer else {
            return selectedTextFallbackProvider.textModels
        }
        let primaryModel = separateTextProviderEnabled ? selectedTextModel : selectedModel
        return selectedTextFallbackProvider.textModels.filter { $0 != primaryModel }
    }

    var primaryModelPlaceholder: String {
        selectedProvider == .openrouter
            ? "e.g. anthropic/claude-sonnet-4"
            : "e.g. gpt-4o-mini"
    }

    var textModelPlaceholder: String {
        selectedTextProvider == .openrouter
            ? "e.g. openai/gpt-oss-120b"
            : "e.g. llama3.2"
    }

    var fallbackModelPlaceholder: String {
        selectedFallbackProvider == .openrouter
            ? "e.g. anthropic/claude-sonnet-4"
            : "e.g. gpt-4o-mini"
    }

    var textFallbackModelPlaceholder: String {
        selectedTextFallbackProvider == .openrouter
            ? "e.g. openai/gpt-oss-120b"
            : "e.g. llama3.2"
    }

    func selectFallbackProvider(_ newProvider: AIProvider) {
        AIProviderSettings.selectedFallbackProvider = newProvider
        if !newProvider.supportsCustomModelName,
           !newProvider.models.contains(selectedFallbackModel) {
            selectedFallbackModel = newProvider.defaultModel
            AIProviderSettings.selectedFallbackModel = selectedFallbackModel
        }
        // Same provider + same server + same model would be a pointless retry — pick an alternate.
        let sharesServer = newProvider == selectedProvider &&
            (AIProviderSettings.fallbackCustomBaseURL(for: newProvider) ?? newProvider.baseURL) ==
            (AIProviderSettings.customBaseURL(for: selectedProvider) ?? selectedProvider.baseURL)
        if sharesServer,
           selectedFallbackModel == selectedModel,
           let alternateModel = newProvider.models.first(where: { $0 != selectedModel }) {
            selectedFallbackModel = alternateModel
            AIProviderSettings.selectedFallbackModel = alternateModel
        }
        fallbackApiKeyText = AIProviderSettings.apiKey(for: newProvider) ?? ""
        fallbackBaseURL = AIProviderSettings.fallbackCustomBaseURL(for: newProvider) ?? ""
    }

    func selectTextFallbackProvider(_ newProvider: AIProvider) {
        AIProviderSettings.selectedTextFallbackProvider = newProvider
        let options = newProvider.textModels
        if !newProvider.supportsCustomModelName,
           !options.contains(selectedTextFallbackModel) {
            selectedTextFallbackModel = newProvider.defaultTextModel
            AIProviderSettings.selectedTextFallbackModel = selectedTextFallbackModel
        }
        let primaryProvider = separateTextProviderEnabled ? selectedTextProvider : selectedProvider
        let primaryModel = separateTextProviderEnabled ? selectedTextModel : selectedModel
        let sharesServer = newProvider == primaryProvider &&
            (AIProviderSettings.fallbackCustomBaseURL(for: newProvider) ?? newProvider.baseURL) ==
            (AIProviderSettings.customBaseURL(for: primaryProvider) ?? primaryProvider.baseURL)
        if sharesServer,
           selectedTextFallbackModel == primaryModel,
           let alternate = options.first(where: { $0 != primaryModel }) {
            selectedTextFallbackModel = alternate
            AIProviderSettings.selectedTextFallbackModel = alternate
        }
        textFallbackApiKeyText = AIProviderSettings.apiKey(for: newProvider) ?? ""
        textFallbackBaseURL = AIProviderSettings.fallbackCustomBaseURL(for: newProvider) ?? ""
    }

    func reconcileImageFallbackModelIfDuplicate() {
        guard fallbackEnabled else { return }
        guard selectedFallbackProvider == selectedProvider,
              selectedFallbackModel == selectedModel,
              resolvedPrimaryBaseURL == resolvedFallbackBaseURL else { return }
        guard let alternate = selectedFallbackProvider.models.first(where: { $0 != selectedModel }) else { return }
        selectedFallbackModel = alternate
        AIProviderSettings.selectedFallbackModel = alternate
    }

    func reconcileTextFallbackModelIfDuplicate() {
        guard textFallbackEnabled else { return }
        let primaryProvider = separateTextProviderEnabled ? selectedTextProvider : selectedProvider
        let primaryModel = separateTextProviderEnabled ? selectedTextModel : selectedModel
        guard selectedTextFallbackProvider == primaryProvider,
              selectedTextFallbackModel == primaryModel,
              resolvedTextPrimaryBaseURL == resolvedTextFallbackBaseURL else { return }
        guard let alternate = selectedTextFallbackProvider.textModels.first(where: { $0 != primaryModel }) else { return }
        selectedTextFallbackModel = alternate
        AIProviderSettings.selectedTextFallbackModel = alternate
    }
}
