import PhotosUI
import SwiftUI
import UIKit

/// "Coach" tab — a persistent AI conversation that has access to the user's profile,
/// weight history, food log, computed forecast, and workout diary. Handles multi-turn
/// chat with memory, a reset button, and prompt chips.
struct ChatView: View {
    @Environment(CoachStore.self) private var chatStore
    @Environment(ProfileStore.self) private var profileStore
    @Environment(WeightStore.self) private var weightStore
    @Environment(BodyFatStore.self) private var bodyFatStore
    @Environment(BodyMeasurementStore.self) private var bodyMeasurementStore
    @Environment(FoodStore.self) private var foodStore
    @Environment(FastingStore.self) private var fastingStore
    @Environment(StrengthWorkoutStore.self) private var strengthWorkoutStore
    @Environment(HealthDataStore.self) private var healthDataStore
    @Environment(RecordsStore.self) private var recordsStore
    @Environment(MedicationStore.self) private var medicationStore
    @AppStorage("heightUnit") private var heightUnitRaw = "ftin"
    @AppStorage("weightUnit") private var weightUnitRaw = "lbs"
    /// Settings › AI › Coach › Suggested Prompts (docs/coach.md §9).
    @AppStorage("coachPromptSuggestions") private var coachPromptSuggestions = true

    @State private var draft = ""
    @State private var attachedImages: [UIImage] = []
    @State private var capturedImage: UIImage?
    @State private var selectedPhotoItems: [PhotosPickerItem] = []
    @State private var isSending = false
    @State private var errorMessage: String?
    @State private var showConversations = false
    @State private var showPromptGallery = false
    @State private var showModelPicker = false
    @State private var showCamera = false
    @State private var showPhotoPicker = false
    @State private var showComposerSheet = false
    @State private var showFileImporter = false
    @State private var showNoteEditor = false
    @State private var noteDraft = ""
    @State private var pendingAttachments: [ChatAttachment] = []
    @State private var excerptPreview: ChatAttachment?
    @State private var isProcessingAttachment = false
    @State private var variantsBySeq: [Int: [ChatMessage]] = [:]
    @State private var shareItems: [Any]?
    @State private var exportError: String?
    @State private var voice = CoachVoiceRecorder()
    @State private var voicePressStart: Date?
    @State private var voicePulse = false
    @FocusState private var isInputFocused: Bool
    // Health Records (§26–§30)
    @State private var showRecordsConsent = false
    @State private var showMedicationsConsent = false
    @State private var pendingHandoff: CoachRecordsHandoff?
    @State private var showRecordsPicker = false
    @State private var recordsSuggestions = CoachRecordsSuggestions()
    @State private var approvalBox = CoachRecordsApprovalBox()
    @State private var showPreSendApproval = false
    /// Coach `propose_action` cards per assistant message (in memory; never stored in the transcript).
    @State private var actionProposals: [String: [CoachActionProposal]] = [:]
    @State private var proposalOutcomes: [UUID: String] = [:]
    @State private var runningProposal: UUID?
    @ScaledMetric(relativeTo: .title2) private var coachHeroSize = 92.0
    @ScaledMetric(relativeTo: .title2) private var coachHeroIconSize = 38.0
    @ScaledMetric(relativeTo: .body) private var composerControlSize = 40.0

    private var userProfile: UserProfile { profileStore.profile }
    private var messages: [ChatMessage] { chatStore.messages }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Group {
                    if messages.isEmpty {
                        emptyState
                    } else {
                        messageList
                    }
                }
                .contentShape(Rectangle())
                .simultaneousGesture(
                    TapGesture().onEnded { isInputFocused = false }
                )

                if !messages.isEmpty, coachPromptSuggestions {
                    promptChips
                }

                if !chatStore.selectedRecords.isEmpty {
                    CoachRecordsChipBar(
                        records: chatStore.selectedRecords,
                        onChange: { showRecordsPicker = true },
                        onClear: { chatStore.setSelectedRecords([]) }
                    )
                    .padding(.top, 4)
                }

                inputArea
            }
            .background(AppColors.appBackground)
            .navigationTitle("Coach")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        showConversations = true
                    } label: {
                        Image(systemName: "list.bullet")
                            .foregroundStyle(AppColors.calorie)
                    }
                    .accessibilityLabel(Text("Chats"))
                    .accessibilityIdentifier("coach.conversations")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button {
                            showModelPicker = true
                        } label: {
                            Label("Model", systemImage: "cpu")
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel(Text("More"))
                    .accessibilityIdentifier("coach.menu")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showPromptGallery = true
                    } label: {
                        Image(systemName: "square.grid.2x2")
                            .foregroundStyle(AppColors.calorie)
                    }
                    .accessibilityLabel(Text("Prompt gallery"))
                    .accessibilityIdentifier("coach.prompts.open")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        Task {
                            await chatStore.startNewConversation()
                            errorMessage = nil
                        }
                    } label: {
                        Image(systemName: "square.and.pencil")
                            .foregroundStyle(messages.isEmpty ? Color.secondary : AppColors.calorie)
                    }
                    // A fresh, empty conversation is already a new chat.
                    .disabled(messages.isEmpty)
                    .accessibilityLabel(Text("New chat"))
                    .accessibilityIdentifier("coach.newChat")
                }
            }
            .sheet(isPresented: $showConversations) {
                ConversationListView()
            }
            .sheet(isPresented: $showModelPicker) {
                CoachModelPickerSheet(
                    selectedProfileID: chatStore.modelOverride.profileID,
                    selectedProvider: chatStore.modelOverride.provider,
                    onPick: { profileID, provider in
                        chatStore.setModelOverride(profileID: profileID, provider: provider)
                    },
                    onSetDefault: { profileID in
                        AIProviderSettings.setRole(.image, profileID: profileID, enabled: true)
                    }
                )
            }
            .sheet(isPresented: $showPromptGallery) {
                // Picking a prompt fills the composer; the user still decides when to send it (§9).
                PromptGalleryView(sections: gallerySections) { prompt in
                    draft = prompt
                    isInputFocused = true
                }
            }
            .sheet(isPresented: Binding(get: { shareItems != nil }, set: { if !$0 { shareItems = nil } })) {
                if let shareItems {
                    CoachShareSheet(items: shareItems)
                }
            }
            .task(id: messages.count) { await loadVariants() }
            .sheet(isPresented: $showComposerSheet) {
                CoachComposerSheet(
                    states: sourceStates,
                    onAttach: handleAttachment,
                    onToggle: { source, on in setSource(source, on) },
                    onConnect: connectSource
                )
            }
            .sheet(item: $excerptPreview) { attachment in
                CoachAttachmentExcerptSheet(attachment: attachment)
            }
            .sheet(isPresented: $showMedicationsConsent) {
                let provider = CoachRecordsFormatting.coachProvider(override: chatStore.providerOverride)
                CoachMedicationsConsentSheet(
                    providerName: provider.name,
                    onDevice: provider.onDevice,
                    onAllow: {
                        medicationStore.setCoachAccess(true)
                        setSource(.medications, true)
                        showMedicationsConsent = false
                    },
                    onNotNow: { showMedicationsConsent = false }
                )
            }
            .sheet(isPresented: $showNoteEditor) {
                CoachNoteSheet(text: $noteDraft) { text in
                    Task { await attachNote(text) }
                }
            }
            .fileImporter(
                isPresented: $showFileImporter,
                allowedContentTypes: [.pdf, .plainText, .text, .commaSeparatedText],
                allowsMultipleSelection: true
            ) { result in
                Task { await importDocuments(result) }
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraView(image: $capturedImage)
                    .ignoresSafeArea()
            }
            .photosPicker(isPresented: $showPhotoPicker, selection: $selectedPhotoItems,
                          maxSelectionCount: CoachComposerLimits.maxImages, matching: .images)
            .sheet(isPresented: $showRecordsConsent) {
                let provider = CoachRecordsFormatting.coachProvider(override: chatStore.providerOverride)
                CoachRecordsConsentSheet(providerName: provider.name, onDevice: provider.onDevice, onAllow: {
                    recordsStore.setCoachAccess(true)
                    showRecordsConsent = false
                    applyHandoff(pendingHandoff, allowed: true)
                }, onNotNow: {
                    showRecordsConsent = false
                    applyHandoff(pendingHandoff, allowed: false)
                })
            }
            .sheet(isPresented: $showRecordsPicker) {
                CoachRecordsPickerSheet(initial: chatStore.selectedRecords) { refs in
                    chatStore.setSelectedRecords(refs)
                }
            }
            .confirmationDialog(
                String(localized: "Coach uses \(approvalBox.providerName). Send the selected records' details online?"),
                isPresented: Binding(get: { approvalBox.isPresented }, set: { if !$0 { approvalBox.dismissed() } }),
                titleVisibility: .visible
            ) {
                Button("Send") { approvalBox.answer(.send) }
                    .accessibilityIdentifier("coach.recordsOnline.send")
                if approvalBox.offersOnDevice {
                    Button("Use on-device Coach") { approvalBox.answer(.useOnDevice) }
                }
                Button("Cancel", role: .cancel) { approvalBox.answer(.cancel) }
            }
            .task { await consumeHandoffIfNeeded() }
            .onChange(of: chatStore.handoffRequest) { _, _ in
                Task { await consumeHandoffIfNeeded() }
            }
            .task(id: recordsSuggestionsKey) {
                recordsSuggestions = await recordsStore.coachSuggestions()
            }
            .onChange(of: capturedImage) { _, newValue in
                guard let image = newValue else { return }
                capturedImage = nil
                appendImage(image)
                errorMessage = nil
            }
            .onChange(of: selectedPhotoItems) { _, newValue in
                guard !newValue.isEmpty else { return }
                let items = newValue
                selectedPhotoItems = []
                Task {
                    do {
                        for item in items {
                            guard let data = try await item.loadTransferable(type: Data.self),
                                  let image = UIImage(data: data) else {
                                await MainActor.run { errorMessage = "Could not load that photo." }
                                continue
                            }
                            await MainActor.run {
                                appendImage(image)
                                errorMessage = nil
                            }
                        }
                    } catch {
                        await MainActor.run {
                            errorMessage = "Could not load that photo."
                        }
                    }
                }
            }
        }
    }

    // MARK: - Sections

    private var emptyState: some View {
        ScrollView {
            VStack(spacing: 0) {
                Spacer()
                    .frame(height: 54)
                ZStack {
                    Circle()
                        .fill(.ultraThinMaterial)
                        .frame(width: coachHeroSize, height: coachHeroSize)
                        .overlay(
                            Circle().stroke(
                                LinearGradient(
                                    colors: [Color.white.opacity(0.35), Color.white.opacity(0.05)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ),
                                lineWidth: 0.8
                            )
                        )
                    Image(systemName: "bubble.left.and.bubble.right.fill")
                        .font(.system(size: coachHeroIconSize, weight: .medium))
                        .foregroundStyle(
                            LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
                        )
                }
                .accessibilityHidden(true)
                .padding(.bottom, 18)
                VStack(spacing: 8) {
                    Text("Ask Ayuvo")
                        .font(.system(.title2, design: .rounded, weight: .semibold))
                    Text("Ayuvo can see your nutrition, goals, workouts and — if you allow it — your Health data. Ask about food, sleep, activity, recovery or your plan.")
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .lineSpacing(2)
                        .padding(.horizontal, 24)
                }
                .accessibilityElement(children: .combine)

                if coachPromptSuggestions {
                    emptyPromptGrid
                        .padding(.top, 26)
                }

                // Everything else the gallery holds, one tap away (docs/coach.md §9).
                Button {
                    showPromptGallery = true
                } label: {
                    Label("Browse all prompts", systemImage: "sparkles")
                        .font(.system(.footnote, design: .rounded, weight: .semibold))
                        .foregroundStyle(AppColors.calorie)
                }
                .buttonStyle(.plain)
                .padding(.top, 14)
                .accessibilityIdentifier("coach.prompts.browse")
                if coachPromptSuggestions, !recordsPromptChips.isEmpty {
                    VStack(spacing: 8) {
                        ForEach(recordsPromptChips, id: \.0) { chip in
                            Button {
                                sendRecordsChip(chip)
                            } label: {
                                Label(chip.0, systemImage: "list.clipboard")
                                    .font(.system(.footnote, design: .rounded, weight: .semibold))
                                    .frame(maxWidth: .infinity, minHeight: 44)
                                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                            }
                            .buttonStyle(.plain)
                            .disabled(isSending)
                            .accessibilityIdentifier("coach.recordsChip.\(chip.0)")
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                }
                Spacer(minLength: 24)
            }
            .padding(.horizontal, 8)
        }
        .scrollDismissesKeyboard(.interactively)
        .scrollIndicators(.hidden)
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(spacing: 14) {
                    ForEach(messages) { msg in
                        VStack(alignment: .leading, spacing: 2) {
                            MessageBubble(
                                attachmentImages: msg.attachmentIDs.compactMap { chatStore.image(for: $0) },
                                message: msg,
                                onOpenRecord: { ref in recordsStore.openRecordFromCoach(ref.recordID) }
                            )
                            if msg.role == .assistant {
                                CoachMessageActions(
                                    message: msg,
                                    variants: variantsBySeq[msg.seq] ?? [],
                                    isBusy: isSending,
                                    onCopy: { UIPasteboard.general.string = msg.content },
                                    onRegenerate: { Task { await regenerate(msg) } },
                                    onShare: { shareItems = [msg.content] },
                                    onShowVariant: { chatStore.showVariant($0) }
                                )
                                .padding(.leading, 52)
                                .padding(.trailing, 48)
                                ForEach(actionProposals[msg.id] ?? []) { proposal in
                                    CoachActionProposalCard(
                                        proposal: proposal,
                                        outcome: proposalOutcomes[proposal.id],
                                        isRunning: runningProposal == proposal.id,
                                        onConfirm: { confirmProposal(proposal) },
                                        onDismiss: { proposalOutcomes[proposal.id] = String(localized: "Not saved.") }
                                    )
                                    .padding(.leading, 52)
                                    .padding(.trailing, 48)
                                    .padding(.top, 6)
                                }
                            }
                        }
                        .id(msg.id)
                    }
                    if isSending {
                        HStack(alignment: .top, spacing: 8) {
                            CoachAssistantBadge()

                            TypingIndicator()
                                .padding(.horizontal, 14)
                                .padding(.vertical, 12)
                                .background(.ultraThinMaterial, in: UnevenRoundedRectangle(
                                    cornerRadii: .init(topLeading: 8, bottomLeading: 18, bottomTrailing: 18, topTrailing: 18),
                                    style: .continuous
                                ))
                                .overlay(
                                    UnevenRoundedRectangle(
                                        cornerRadii: .init(topLeading: 8, bottomLeading: 18, bottomTrailing: 18, topTrailing: 18),
                                        style: .continuous
                                    )
                                        .stroke(Color.white.opacity(0.15), lineWidth: 0.5)
                                )
                            Spacer(minLength: 48)
                        }
                        .padding(.horizontal)
                        .id("typing")
                    }
                    if let err = errorMessage {
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .accessibilityHidden(true)
                            Text(err)
                        }
                        .font(.system(.caption, design: .rounded, weight: .medium))
                        .foregroundStyle(.red)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .stroke(Color.red.opacity(0.25), lineWidth: 0.6)
                        )
                        .padding(.horizontal)
                    }
                }
                .padding(.top, 14)
                .padding(.bottom, 16)
            }
            .scrollDismissesKeyboard(.immediately)
            .onAppear {
                guard let lastID = messages.last?.id else { return }
                DispatchQueue.main.async {
                    proxy.scrollTo(lastID, anchor: .bottom)
                }
            }
            .onChange(of: messages.count) { _, _ in
                withAnimation { proxy.scrollTo(messages.last?.id, anchor: .bottom) }
            }
            .onChange(of: isSending) { _, sending in
                if sending { withAnimation { proxy.scrollTo("typing", anchor: .bottom) } }
            }
            .onChange(of: isInputFocused) { _, focused in
                guard focused, let lastID = messages.last?.id else { return }
                // Animate alongside the keyboard for responsiveness.
                withAnimation(.easeOut(duration: 0.25)) {
                    proxy.scrollTo(lastID, anchor: .bottom)
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidShowNotification)) { _ in
                // Fires *after* the keyboard is fully shown — by now the ScrollView's
                // safe-area inset is definitely applied, so this re-anchor catches the
                // case where the initial scroll ran against the pre-keyboard viewport
                // (bubble was hidden until the user typed and forced a re-layout).
                guard isInputFocused, let lastID = messages.last?.id else { return }
                withAnimation(.easeOut(duration: 0.2)) {
                    proxy.scrollTo(lastID, anchor: .bottom)
                }
            }
        }
    }

    // MARK: - Prompt chips and gallery (docs/coach.md §9)

    /// Effective sources in the shape ``CR.galleryFor`` and ``CR.chipsFor`` expect.
    private var sourcesValue: RJ {
        .obj(Dictionary(uniqueKeysWithValues: CoachSource.allCases.map {
            ($0.rawValue, RJ.bool(effectiveSources.contains($0)))
        }))
    }

    private var hasSleepData: Bool {
        healthDataStore.typeSummaries.contains { $0.typeID.lowercased().contains("sleep") && $0.count > 0 }
    }

    /// The chips above the composer: gallery entries, gated by the same rule as the gallery cards,
    /// so a chip can never offer what the gallery hides.
    private var suggestedPrompts: [GalleryPrompt] {
        let ids = CR.chipsFor(goal: userProfile.goal.rawValue,
                              hasWorkouts: !strengthWorkoutStore.completedSessions.isEmpty,
                              hasSleep: hasSleepData,
                              sources: sourcesValue,
                              catalog: CoachCatalog.gallery)["ids"].array ?? []
        return ids.compactMap { entry in
            guard let id = entry.string,
                  let title = PromptGalleryText.title(id),
                  let prompt = PromptGalleryText.prompt(id) else { return nil }
            return GalleryPrompt(id: id, title: title, prompt: prompt)
        }
    }

    /// The whole gallery, grouped by category, for what this device can actually answer from.
    private var gallerySections: [(String, [GalleryPrompt])] {
        let groups = CR.galleryFor(sourcesValue, catalog: CoachCatalog.gallery)["categories"].array ?? []
        return groups.compactMap { group in
            guard let name = group["category"].string,
                  let label = PromptGalleryText.category(name) else { return nil }
            let entries: [GalleryPrompt] = (group["ids"].array ?? []).compactMap { value in
                guard let id = value.string,
                      let title = PromptGalleryText.title(id),
                      let prompt = PromptGalleryText.prompt(id) else { return nil }
                return GalleryPrompt(id: id, title: title, prompt: prompt)
            }
            return entries.isEmpty ? nil : (label, entries)
        }
    }

    private var emptyPromptGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
            ForEach(Array(suggestedPrompts.prefix(4))) { entry in
                Button {
                    draft = entry.prompt
                    send()
                } label: {
                    HStack(alignment: .top, spacing: 9) {
                        Image(systemName: "arrow.up.right")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(AppColors.calorie)
                            .padding(.top, 2)
                            .accessibilityHidden(true)
                        Text(entry.title)
                            .font(.system(.footnote, design: .rounded, weight: .medium))
                            .foregroundStyle(.primary)
                            .multilineTextAlignment(.leading)
                            .lineLimit(3)
                        Spacer(minLength: 0)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, minHeight: 70, alignment: .topLeading)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .fill(AppColors.calorie.opacity(0.045))
                    )
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .stroke(AppColors.calorie.opacity(0.18), lineWidth: 0.6)
                    )
                }
                .buttonStyle(.plain)
                .disabled(isSending)
            }
        }
        .padding(.horizontal, 16)
    }

    /// §27 records chips, shown when Coach access is on and at least one lab record exists.
    private var recordsPromptChips: [(String, [ChatRecordRef])] {
        guard recordsStore.coachAccessEnabled, !recordsSuggestions.isEmpty else { return [] }
        var chips: [(String, [ChatRecordRef])] = [
            (CoachRecordsPrompts.analyzeLatest, recordsSuggestions.latestLabs),
            (CoachRecordsPrompts.findAbnormal, []),
        ]
        if recordsSuggestions.comparePair.count == 2 {
            chips.append((CoachRecordsPrompts.compareChip, recordsSuggestions.comparePair))
        }
        return chips
    }

    private var recordsSuggestionsKey: String {
        "\(recordsStore.coachAccessEnabled)-\(recordsStore.revision)"
    }

    private func sendRecordsChip(_ chip: (String, [ChatRecordRef])) {
        if !chip.1.isEmpty { chatStore.setSelectedRecords(chip.1) }
        draft = chip.0
        send()
    }

    /// Context-aware suggested prompts — pick a different set based on goal to keep them relevant.
    private var promptChips: some View {

        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Image(systemName: "sparkles")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(AppColors.calorie)
                    .frame(width: 32, height: 44)
                    .background(AppColors.calorie.opacity(0.08), in: Circle())
                    .accessibilityHidden(true)

                ForEach(recordsPromptChips, id: \.0) { chip in
                    Button {
                        sendRecordsChip(chip)
                    } label: {
                        Label(chip.0, systemImage: "list.clipboard")
                            .font(.system(.footnote, design: .rounded, weight: .medium))
                            .padding(.horizontal, 14)
                            .frame(minHeight: 44)
                            .foregroundStyle(AppColors.calorie)
                            .background(Capsule().fill(.ultraThinMaterial))
                            .overlay(Capsule().fill(AppColors.calorie.opacity(0.10)))
                    }
                    .buttonStyle(.plain)
                    .disabled(isSending)
                    .accessibilityIdentifier("coach.recordsChip.\(chip.0)")
                }
                ForEach(suggestedPrompts) { chip in
                    Button {
                        draft = chip.prompt
                        send()
                    } label: {
                        Text(chip.title)
                            .font(.system(.footnote, design: .rounded, weight: .medium))
                            .padding(.horizontal, 14)
                            .frame(minHeight: 44)
                            .foregroundStyle(AppColors.calorie)
                            .background(
                                Capsule().fill(.ultraThinMaterial)
                            )
                            .overlay(
                                Capsule()
                                    .fill(AppColors.calorie.opacity(0.10))
                            )
                            .overlay(
                                Capsule()
                                    .stroke(
                                        LinearGradient(
                                            colors: [AppColors.calorie.opacity(0.35), AppColors.calorie.opacity(0.10)],
                                            startPoint: .topLeading,
                                            endPoint: .bottomTrailing
                                        ),
                                        lineWidth: 0.6
                                    )
                            )
                    }
                    .buttonStyle(.plain)
                    .disabled(isSending)
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 6)
        }
    }

    private var inputArea: some View {
        VStack(spacing: 8) {
            if !pendingAttachments.isEmpty {
                CoachPendingAttachmentsRow(
                    attachments: pendingAttachments,
                    onOpen: { excerptPreview = $0 },
                    onRemove: { attachment in
                        pendingAttachments.removeAll { $0.id == attachment.id }
                    }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if !attachedImages.isEmpty {
                attachmentPreview(attachedImages)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if !offSources.isEmpty {
                CoachSourceSummaryRow(effective: effectiveSources) { showComposerSheet = true }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
            }

            inputBar
        }
        .animation(.easeInOut(duration: 0.18), value: attachedImages.count)
        .onChange(of: voice.submittedTranscript) { _, newValue in
            guard let text = newValue, !text.isEmpty else { return }
            draft = text
            send()
            voice.submittedTranscript = nil
        }
    }

    private func attachmentPreview(_ images: [UIImage]) -> some View {
        HStack(spacing: 10) {
            Image(uiImage: images[0])
                .resizable()
                .scaledToFill()
                .frame(width: 62, height: 62)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(Color.white.opacity(0.18), lineWidth: 0.6)
                )

            VStack(alignment: .leading, spacing: 3) {
                Text(images.count == 1 ? String(localized: "Image attached")
                                        : String(localized: "\(images.count) images attached"))
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                Text("Send with your message")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Button {
                attachedImages = []
            } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(.secondary)
                    .frame(width: 36, height: 36)
                    .background(.ultraThinMaterial, in: Circle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(AppColors.calorie.opacity(0.18), lineWidth: 0.7)
        )
        .padding(.horizontal, 12)
    }

    private var inputBar: some View {
        HStack(spacing: 8) {
            // Left region: attach + text field, or the live recording indicator.
            Group {
                if voice.phase == .idle {
                    HStack(spacing: 8) {
                        attachMenu
                        TextField("Message Ayuvo…", text: $draft, axis: .vertical)
                            .font(.system(.body, design: .rounded))
                            .lineLimit(1...5)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 12)
                            .focused($isInputFocused)
                    }
                } else {
                    recordingIndicator
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            // Trailing control (kept as the stable last child).
            trailingControl
        }
        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(.ultraThinMaterial))
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(
                    LinearGradient(
                        colors: [Color.white.opacity(0.25), Color.white.opacity(0.05)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: 0.8
                )
        )
        .shadow(color: Color.black.opacity(0.18), radius: 14, x: 0, y: 6)
        .padding(.horizontal, 12)
        .padding(.bottom, 10)
        .padding(.top, 4)
    }

    private var attachMenu: some View {
        Button {
            isInputFocused = false
            showComposerSheet = true
        } label: {
            Image(systemName: hasAttachment ? "paperclip.circle.fill" : "plus.circle.fill")
                .font(.system(size: 24, weight: .semibold))
                .foregroundStyle(AppColors.calorie)
                .frame(width: composerControlSize, height: composerControlSize)
        }
        .disabled(isSending || isProcessingAttachment)
        .padding(.leading, 6)
        .accessibilityLabel(Text("Add"))
        .accessibilityIdentifier("coach.attach")
    }

    private var hasAttachment: Bool { !attachedImages.isEmpty || !pendingAttachments.isEmpty }

    /// Pictures are capped per turn (docs/coach.md §6); the sheet says so before the user picks.
    private func appendImage(_ image: UIImage) {
        guard attachedImages.count < CoachComposerLimits.maxImages else {
            errorMessage = String(localized: "You can attach up to \(CoachComposerLimits.maxImages) pictures.")
            return
        }
        attachedImages.append(image)
    }

    /// Sources the user switched off or has not connected — the summary row only appears when one is.
    private var offSources: [CoachSource] {
        CoachSource.allCases.filter { (sourceStates[$0] ?? .unavailable) != .on }
    }

    private var effectiveSources: Set<CoachSource> {
        Set(CoachSource.allCases.filter { (sourceStates[$0] ?? .unavailable) == .on })
    }

    @ViewBuilder private var trailingControl: some View {
        switch voice.phase {
        case .locked:
            HStack(spacing: 8) {
                voiceCancelButton
                voiceSendButton
            }
            .padding(.trailing, 5)
        case .transcribing:
            ProgressView()
                .frame(width: composerControlSize, height: composerControlSize)
                .padding(.trailing, 5)
        case .idle where canSend:
            sendButton
                .padding(.trailing, 5)
                .animation(.easeInOut(duration: 0.15), value: canSend)
        default: // idle-empty or holding — keep the mic mounted through the press
            micButton
                .padding(.trailing, 5)
        }
    }

    private var sendButton: some View {
        Button {
            send()
        } label: {
            Image(systemName: "arrow.up")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: composerControlSize, height: composerControlSize)
                .background(
                    canSend
                        ? AnyShapeStyle(LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing))
                        : AnyShapeStyle(Color.secondary.opacity(0.35)),
                    in: Circle()
                )
                .overlay(Circle().stroke(Color.white.opacity(canSend ? 0.25 : 0.10), lineWidth: 0.6))
                .shadow(color: canSend ? AppColors.calorie.opacity(0.35) : .clear, radius: 8, x: 0, y: 4)
        }
        .disabled(!canSend)
    }

    private var micButton: some View {
        let holding = voice.phase == .holding
        return Image(systemName: "mic.fill")
            .font(.system(size: 16, weight: .bold))
            .foregroundStyle(holding ? .white : AppColors.calorie)
            .frame(width: composerControlSize, height: composerControlSize)
            .background(
                holding ? AnyShapeStyle(Color.red) : AnyShapeStyle(AppColors.calorie.opacity(0.14)),
                in: Circle()
            )
            .scaleEffect(holding ? 1.25 : 1.0)
            .animation(.easeInOut(duration: 0.15), value: holding)
            .contentShape(Circle())
            .gesture(micGesture)
            .id("coachMic")
    }

    private var micGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                if voice.phase == .idle {
                    isInputFocused = false
                    voicePressStart = Date()
                    voice.begin()
                }
                voice.updateDrag(value.translation.width, threshold: 90)
            }
            .onEnded { value in
                let held = voicePressStart.map { Date().timeIntervalSince($0) } ?? 0
                if value.translation.width < -90 {
                    voice.cancel()
                } else if held < 0.35 && abs(value.translation.width) < 24 {
                    voice.lock()
                } else {
                    voice.stopAndSend()
                }
                voicePressStart = nil
            }
    }

    private var voiceSendButton: some View {
        Button {
            voice.stopAndSend()
        } label: {
            Image(systemName: "arrow.up")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: composerControlSize, height: composerControlSize)
                .background(
                    LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing),
                    in: Circle()
                )
                .shadow(color: AppColors.calorie.opacity(0.35), radius: 8, x: 0, y: 4)
        }
    }

    private var voiceCancelButton: some View {
        Button {
            voice.cancel()
        } label: {
            Image(systemName: "trash")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.red)
                .frame(width: composerControlSize, height: composerControlSize)
                .background(Color.secondary.opacity(0.14), in: Circle())
        }
        .buttonStyle(.plain)
    }

    private var recordingIndicator: some View {
        HStack(spacing: 8) {
            if voice.phase == .transcribing {
                ProgressView().controlSize(.small)
                Text("Transcribing…")
                    .font(.system(.callout, design: .rounded))
                    .foregroundStyle(.secondary)
            } else {
                Circle()
                    .fill(Color.red)
                    .frame(width: 9, height: 9)
                    .opacity(voicePulse ? 0.3 : 1.0)
                    .onAppear {
                        withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) {
                            voicePulse = true
                        }
                    }
                    .onDisappear { voicePulse = false }
                Text(formatVoiceElapsed(voice.elapsed))
                    .font(.system(.callout, design: .rounded, weight: .medium))
                    .monospacedDigit()
                Text(voiceHint)
                    .font(.system(.callout, design: .rounded))
                    .foregroundStyle(voice.cancelArmed ? .red : .secondary)
                    .lineLimit(1)
            }
        }
        .padding(.leading, 14)
        .padding(.vertical, 12)
    }

    private var voiceHint: String {
        if voice.phase == .holding {
            return voice.cancelArmed ? "Release to cancel" : "‹ slide to cancel"
        }
        return voice.liveText.isEmpty ? "Listening…" : voice.liveText
    }

    private var canSend: Bool {
        !isSending && !isProcessingAttachment
            && (!draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || hasAttachment)
    }

    // MARK: - Send

    // MARK: - Health Records hand-off & approval

    private func consumeHandoffIfNeeded() async {
        guard chatStore.pendingHandoff != nil, let handoff = chatStore.consumeHandoff() else { return }
        if recordsStore.coachAccessEnabled || handoff.records.isEmpty {
            applyHandoff(handoff, allowed: true)
        } else {
            pendingHandoff = handoff
            showRecordsConsent = true
        }
    }

    private func applyHandoff(_ handoff: CoachRecordsHandoff?, allowed: Bool) {
        pendingHandoff = nil
        guard let handoff else { return }
        if allowed, !handoff.records.isEmpty { chatStore.setSelectedRecords(handoff.records) }
        if !handoff.prompt.isEmpty { draft = handoff.prompt }
        isInputFocused = !handoff.prompt.isEmpty
    }

    /// §30: an online provider while the records AI mode is local / off needs a per-conversation OK.
    private func recordsNeedOnlineApproval() -> Bool {
        guard recordsStore.coachAccessEnabled, chatStore.recordsOnlineDecision != .send else { return false }
        guard recordsStore.aiMode == .local || recordsStore.aiMode == .off else { return false }
        return !CoachRecordsFormatting.coachProvider(override: chatStore.providerOverride).onDevice
    }

    private func send() {
        let typedText = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        let images = attachedImages
        guard (!typedText.isEmpty || !images.isEmpty || !pendingAttachments.isEmpty), !isSending else { return }
        if !chatStore.selectedRecords.isEmpty, chatStore.recordsOnlineDecision == nil, recordsNeedOnlineApproval() {
            Task {
                let provider = CoachRecordsFormatting.coachProvider(override: chatStore.providerOverride)
                let decision = await approvalBox.ask(providerName: provider.name, offersOnDevice: CoachRecordsFormatting.onDeviceProvider() != nil)
                applyOnlineDecision(decision)
                send()
            }
            return
        }

        let text = typedText.isEmpty && !images.isEmpty ? "Analyze this image." : typedText
        let imagesForAI = images.compactMap {
            resizedJPEGData(from: $0, maxDimension: 1600, compressionQuality: 0.78)
        }
        let thumbnails = images.compactMap {
            resizedJPEGData(from: $0, maxDimension: 700, compressionQuality: 0.68)
        }
        if !images.isEmpty, imagesForAI.count != images.count {
            errorMessage = "Failed to process the image."
            return
        }

        draft = ""
        attachedImages = []
        errorMessage = nil
        isSending = true

        Task {
            defer { isSending = false }
            let documents = pendingAttachments
            pendingAttachments = []
            var attachmentIDs: [String] = []
            for thumbnail in thumbnails {
                attachmentIDs += await storeImageAttachment(thumbnail)
            }
            attachmentIDs += documents.map(\.id)
            await chatStore.append(ChatMessage(role: .user, content: text, attachmentIDs: attachmentIDs))
            // Exclude the user message we just appended; the call carries it as `newUserMessage`.
            let historyForCall = Array(chatStore.contextMessages().dropLast())
            // The excerpts are already redacted and are exactly what the excerpt sheet showed (§6).
            let textForAI = CoachAttachmentComposer.messageWithAttachments(text, documents)
            do {
                // Health Data hub: nil unless Health sync and the Coach consent are both on.
                let health = await healthDataStore.coachContext()
                let reply = try await sendWithRecords(history: historyForCall, text: textForAI, images: imagesForAI, health: health)
                let assistant = ChatMessage(role: .assistant, content: reply.text,
                                            recordRefs: reply.refs.isEmpty ? nil : reply.refs)
                await chatStore.append(assistant)
                if !reply.proposals.isEmpty {
                    actionProposals[chatStore.messages.last(where: { $0.role == .assistant })?.id ?? assistant.id] = reply.proposals
                }
            } catch {
                errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
        }
    }

    // MARK: - Message actions (docs/coach.md §10)

    /// Which replies have more than one version, so the stepper only appears where it means
    /// something. Loaded off the transcript rather than per row, to keep the list cheap.
    private func loadVariants() async {
        var out: [Int: [ChatMessage]] = [:]
        for message in messages where message.role == .assistant {
            let rows = await chatStore.variants(seq: message.seq)
            if rows.count > 1 { out[message.seq] = rows }
        }
        variantsBySeq = out
    }

    /// Re-sends the user turn this reply answered, with the same attachments, records and switches.
    /// The old answer is kept — it becomes version 1 of 2.
    private func regenerate(_ message: ChatMessage) async {
        guard !isSending else { return }
        guard let plan = await chatStore.regeneratePlan(for: message) else { return }
        guard let promptID = plan["prompt_id"].string else { return }
        var prompt = messages.first { $0.id == promptID }
        if prompt == nil {
            // The prompt is older than the visible slice, or is itself an earlier variant.
            let older = await chatStore.variants(seq: Int(plan["prompt_seq"].double ?? 0))
            prompt = older.first { $0.id == promptID }
        }
        guard let prompt else { return }

        isSending = true
        errorMessage = nil
        defer { isSending = false }
        do {
            let health = await healthDataStore.coachContext()
            // Exclude the prompt itself and everything after it: the model gets the same view it had.
            let history = Array(messages.filter { $0.seq < prompt.seq }.suffix(CoachStore.maxMessagesInContext))
            let documents = await chatStore.attachments(ids: prompt.attachmentIDs)
            let textForAI = CoachAttachmentComposer.messageWithAttachments(prompt.content, documents)
            let reply = try await sendWithRecords(history: history, text: textForAI, images: [], health: health)
            let variant = ChatMessage(
                conversationID: prompt.conversationID,
                seq: Int(plan["seq"].double ?? Double(message.seq)),
                variantIndex: Int(plan["variant_index"].double ?? 1),
                role: .assistant,
                content: reply.text,
                regeneratedFrom: plan["regenerated_from"].string,
                recordRefs: reply.refs.isEmpty ? nil : reply.refs
            )
            await chatStore.appendVariant(variant)
            await loadVariants()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    /// §10 "Export as Markdown" / "Export as JSON".
    private func exportConversation(_ format: CoachExportFormat) async {
        guard let id = chatStore.current?.id else { return }
        let provider = CoachRecordsFormatting.coachProvider(override: chatStore.providerOverride).name
        guard let file = await chatStore.export(conversationID: id, format: format, provider: provider),
              let url = file.writeToTemporaryFile()
        else {
            exportError = String(localized: "That chat could not be exported.")
            return
        }
        shareItems = [url]
    }

    // MARK: - Attachments and data sources (docs/coach.md §3, §6, §8)

    /// What the composer sheet shows per source: on, off for this chat, not connected, or nothing to
    /// read yet. A switch can only narrow, so "not connected" offers Connect instead of a toggle.
    private var sourceStates: [CoachSource: CoachComposerSheet.SourceState] {
        var out: [CoachSource: CoachComposerSheet.SourceState] = [:]
        let switches = chatStore.dataSwitches
        func state(available: Bool, consented: Bool, source: CoachSource) -> CoachComposerSheet.SourceState {
            if !available { return .unavailable }
            if !consented { return .notConnected }
            return switches.isOn(source) ? .on : .off
        }
        out[.food] = switches.isOn(.food) ? .on : .off
        out[.health] = state(available: healthDataStore.isEnabled,
                             consented: healthDataStore.coachHealthDataEnabled, source: .health)
        out[.medications] = state(available: medicationStore.hasAnyMedication,
                                  consented: medicationStore.coachAccessEnabled, source: .medications)
        out[.records] = state(available: recordsStore.revision > 0 || recordsStore.coachAccessEnabled,
                              consented: recordsStore.coachAccessEnabled, source: .records)
        return out
    }

    private func setSource(_ source: CoachSource, _ on: Bool) {
        var switches = chatStore.dataSwitches
        switches.set(source, on)
        chatStore.dataSwitches = switches
    }

    /// "Connect" never flips the switch itself — it opens the consent that owns that decision.
    private func connectSource(_ source: CoachSource) {
        switch source {
        case .records:
            showComposerSheet = false
            showRecordsConsent = true
        case .medications:
            showComposerSheet = false
            showMedicationsConsent = true
        case .health, .food:
            // Health sync lives in Settings; Coach cannot grant it.
            break
        }
    }

    private func handleAttachment(_ item: CoachComposerSheet.Attachment) {
        switch item {
        case .camera: openCamera()
        case .photos: showPhotoPicker = true
        case .files: showFileImporter = true
        case .record: showRecordsPicker = true
        case .note:
            noteDraft = ""
            showNoteEditor = true
        }
    }

    /// Documents are read and redacted on this device; only the excerpt is ever sent (§6).
    private func importDocuments(_ result: Result<[URL], Error>) async {
        guard case .success(let urls) = result, !urls.isEmpty else {
            if case .failure(let error) = result { errorMessage = error.localizedDescription }
            return
        }
        guard let files = await chatStore.fileStore() else { return }
        isProcessingAttachment = true
        defer { isProcessingAttachment = false }
        let processor = CoachAttachmentProcessor(files: files)
        for url in urls.prefix(CoachComposerLimits.maxDocuments) {
            do {
                let outcome = try await processor.process(url: url)
                guard await chatStore.store(outcome.attachment) != nil else { continue }
                pendingAttachments.append(outcome.attachment)
                if outcome.isEmpty {
                    errorMessage = String(localized: "No readable text was found in \(outcome.attachment.filename).")
                }
            } catch {
                errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
        }
    }

    private func attachNote(_ text: String) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let files = await chatStore.fileStore() else { return }
        let outcome = CoachAttachmentProcessor(files: files).processNote(trimmed)
        guard await chatStore.store(outcome.attachment) != nil else { return }
        pendingAttachments.append(outcome.attachment)
    }

    /// Writes the bubble thumbnail to the attachment store and returns its id (docs/coach.md §6).
    /// A failure here costs the picture, never the message, so the turn still sends.
    private func storeImageAttachment(_ thumbnailData: Data?) async -> [String] {
        guard let thumbnailData, !thumbnailData.isEmpty,
              let files = await chatStore.fileStore()
        else { return [] }
        let id = UUID().uuidString.lowercased()
        guard let path = try? files.writeOriginal(thumbnailData, attachmentID: id, fileExtension: "jpg") else {
            return []
        }
        let attachment = ChatAttachment(
            id: id,
            kind: .image,
            filename: "photo.jpg",
            mimeType: "image/jpeg",
            bytes: thumbnailData.count,
            sha256: CoachFileStore.sha256(thumbnailData),
            filePath: path,
            createdMs: CoachStore.nowMs()
        )
        guard await chatStore.store(attachment) != nil else {
            files.delete(attachmentID: id)
            return []
        }
        if let image = UIImage(data: thumbnailData) { chatStore.cacheImage(image, for: id) }
        return [id]
    }

    private func applyOnlineDecision(_ decision: CoachRecordsOnlineDecision) {
        switch decision {
        case .send:
            chatStore.recordsOnlineDecision = .send
        case .cancel:
            // Cancel removes the records tools and the selection for this conversation.
            chatStore.recordsOnlineDecision = .cancel
            chatStore.setSelectedRecords([])
        case .useOnDevice:
            chatStore.providerOverride = CoachRecordsFormatting.onDeviceProvider()
            chatStore.recordsOnlineDecision = nil
        }
    }

    /// Builds the records context, sends, and retries on the on-device Coach when the user picks it
    /// during a records tool call.
    private func sendWithRecords(history: [ChatMessage], text: String, images: [Data], health: CoachHealthContext?) async throws -> (text: String, refs: [ChatRecordRef], proposals: [CoachActionProposal]) {
        for attempt in 0..<2 {
            let needsApproval = recordsNeedOnlineApproval()
            let box = approvalBox
            let provider = CoachRecordsFormatting.coachProvider(override: chatStore.providerOverride)
            let offersOnDevice = CoachRecordsFormatting.onDeviceProvider() != nil
            let session = CoachRecordsSession(
                needsOnlineApproval: needsApproval,
                decision: needsApproval ? chatStore.recordsOnlineDecision : nil,
                requestApproval: { await box.ask(providerName: provider.name, offersOnDevice: offersOnDevice) }
            )
            var records: CoachRecordsContext? = await recordsStore.coachContext(selected: chatStore.selectedRecords, session: session)
            let proposalSink = CoachActionProposalSink()
            if chatStore.recordsOnlineDecision == .cancel { records = nil }
            do {
                let reply = try await ChatService.sendMessage(
                    history: history,
                    newUserMessage: text,
                    images: images,
                    profile: userProfile,
                    weights: weightStore.entries,
                    bodyFats: bodyFatStore.entries,
                    measurements: bodyMeasurementStore.entries,
                    foods: foodStore.entries,
                    fastingSessions: fastingStore.sessions,
                    heightMetric: heightUnitRaw == "cm",
                    weightMetric: weightUnitRaw == "kg",
                    workoutSessions: strengthWorkoutStore.completedSessions,
                    workoutPlans: Array(strengthWorkoutStore.dayPlans.values),
                    workoutPreferences: strengthWorkoutStore.preferences,
                    workoutAccessEnabled: true,
                    health: health,
                    records: records,
                    medications: await medicationStore.coachContext(),
                    sources: chatStore.dataSwitches,
                    providerOverride: chatStore.providerOverride,
                    profileOverride: chatStore.modelOverride.profileID,
                    actionProposals: proposalSink
                )
                if needsApproval, let decision = await session.decision { applyOnlineDecision(decision) }
                return (reply, await session.refs, proposalSink.proposals)
            } catch ChatService.ChatError.recordsSwitchToOnDevice where attempt == 0 {
                applyOnlineDecision(.useOnDevice)
                continue
            } catch {
                if needsApproval, let decision = await session.decision, decision != .useOnDevice { applyOnlineDecision(decision) }
                throw error
            }
        }
        throw ChatService.ChatError.invalidResponse
    }

    /// The user's OK on a Coach proposal: the only way a Coach suggestion ever changes data.
    private func confirmProposal(_ proposal: CoachActionProposal) {
        guard runningProposal == nil else { return }
        runningProposal = proposal.id
        Task {
            defer { runningProposal = nil }
            do {
                let result = try await ActionExecutor.shared.perform(proposal.validation, source: .app, confirmed: true)
                proposalOutcomes[proposal.id] = result.dialog
            } catch {
                proposalOutcomes[proposal.id] = (error as? ActionError)?.message ?? error.localizedDescription
            }
        }
    }

    private func openCamera() {
        guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
            errorMessage = "Camera is not available on this device."
            return
        }
        showCamera = true
    }

    private func resizedJPEGData(from image: UIImage, maxDimension: CGFloat, compressionQuality: CGFloat) -> Data? {
        let originalSize = image.size
        let longestSide = max(originalSize.width, originalSize.height)
        guard longestSide > 0 else {
            return image.jpegData(compressionQuality: compressionQuality)
        }

        let scale = min(1, maxDimension / longestSide)
        let targetSize = CGSize(width: originalSize.width * scale, height: originalSize.height * scale)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)
        let resized = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }
        return resized.jpegData(compressionQuality: compressionQuality)
    }
}

// MARK: - Supporting views

/// Lightweight Markdown renderer for assistant chat bubbles — handles the formatting the Coach
/// actually emits: #/##/### headings, "- / * / 1." lists, ``` code fences ```, `inline code`,
/// **bold**, *italic*, and [links](url). Block layout is done here; inline styling uses
/// AttributedString's inline-only markdown so no third-party dependency is needed.
private struct MessageBubble: View {
    /// Resolved by `ChatView` from the attachment store; the bubble never does file IO itself.
    var attachmentImages: [UIImage] = []
    let message: ChatMessage
    var onOpenRecord: (ChatRecordRef) -> Void = { _ in }

    private var isUser: Bool { message.role == .user }
    private var bubbleShape: UnevenRoundedRectangle {
        UnevenRoundedRectangle(
            cornerRadii: .init(
                topLeading: isUser ? 20 : 8,
                bottomLeading: 20,
                bottomTrailing: 20,
                topTrailing: isUser ? 8 : 20
            ),
            style: .continuous
        )
    }

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            if !isUser {
                CoachAssistantBadge()
            } else {
                Spacer(minLength: 48)
            }

            VStack(alignment: .leading, spacing: 6) {
                bubble
                if !isUser, let refs = message.recordRefs, !refs.isEmpty {
                    CoachUsedRecordsRow(refs: refs, onOpen: onOpenRecord)
                        .padding(.leading, 4)
                }
            }

            if isUser {
                // no trailing icon
            } else {
                Spacer(minLength: 48)
            }
        }
        .padding(.horizontal)
    }

    private var bubble: some View {
        VStack(alignment: .leading, spacing: 9) {
            if let uiImage = attachmentImages.first {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFill()
                    .frame(width: 196, height: 140)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .stroke(Color.white.opacity(isUser ? 0.25 : 0.12), lineWidth: 0.7)
                    )
            }

            if isUser {
                // User's own typed text — show verbatim, no markdown.
                Text(message.content)
                    .font(.system(.body, design: .rounded))
                    .textSelection(.enabled)
                    .foregroundStyle(.white)
            } else {
                // Coach replies often use markdown — render it.
                CoachMarkdownView(text: message.content)
                    .textSelection(.enabled)
                    .foregroundStyle(.primary)
            }
        }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .background(bubbleBackground)
            .overlay(bubbleStroke)
            .overlay(alignment: .top) {
                if isUser { bubbleHighlight }
            }
            .clipShape(bubbleShape)
            .shadow(color: isUser ? AppColors.calorie.opacity(0.18) : Color.black.opacity(0.08),
                    radius: isUser ? 9 : 5, x: 0, y: isUser ? 5 : 3)
            .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder
    private var bubbleBackground: some View {
        if isUser {
            LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
        } else {
            ZStack {
                bubbleShape
                    .fill(.ultraThinMaterial)
                bubbleShape
                    .fill(AppColors.calorie.opacity(0.035))
            }
        }
    }

    private var bubbleStroke: some View {
        bubbleShape
            .stroke(
                LinearGradient(
                    colors: isUser
                        ? [Color.white.opacity(0.45), Color.white.opacity(0.05)]
                        : [Color.white.opacity(0.22), Color.white.opacity(0.04)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                ),
                lineWidth: 0.7
            )
    }

    /// Glassy top highlight on user bubbles — makes the gradient read as polished glass, not flat paint.
    private var bubbleHighlight: some View {
        LinearGradient(
            colors: [Color.white.opacity(0.35), Color.white.opacity(0)],
            startPoint: .top,
            endPoint: .center
        )
        .blendMode(.plusLighter)
        .allowsHitTesting(false)
    }
}

private struct CoachAssistantBadge: View {
    var body: some View {
        ZStack {
            Circle()
                .fill(.ultraThinMaterial)
                .frame(width: 28, height: 28)
                .overlay(Circle().stroke(Color.white.opacity(0.18), lineWidth: 0.5))
            Image(systemName: "sparkles")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(
                    LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
                )
        }
        .padding(.top, 8)
        .accessibilityHidden(true)
    }
}

private struct TypingIndicator: View {
    @State private var phase = 0
    var body: some View {
        HStack(spacing: 5) {
            ForEach(0..<3) { i in
                Circle()
                    .fill(
                        LinearGradient(colors: AppColors.calorieGradient, startPoint: .topLeading, endPoint: .bottomTrailing)
                    )
                    .frame(width: 7, height: 7)
                    .opacity(phase == i ? 1 : 0.3)
                    .scaleEffect(phase == i ? 1.15 : 1.0)
                    .animation(.easeInOut(duration: 0.35), value: phase)
            }
        }
        .onAppear {
            Timer.scheduledTimer(withTimeInterval: 0.35, repeats: true) { _ in
                phase = (phase + 1) % 3
            }
        }
    }
}
