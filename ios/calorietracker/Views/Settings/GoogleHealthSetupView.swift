import SwiftUI
import HealthKit

/// The 4-step Google Health setup sheet (docs/google-health.md §5), in the onboarding step
/// style (progress capsule, `stepHeader`, capsule Continue button):
/// 1. intro and privacy, 2. data groups (+ Advanced custom client id), 3. Google consent,
/// 4. Apple Health write permission and the first 90-day sync.
struct GoogleHealthSetupView: View {
    @Environment(GoogleHealthStore.self) private var store
    @Environment(HealthKitManager.self) private var healthKitManager
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    private static let totalSteps = 4
    private static let consoleURL = URL(string: "https://console.cloud.google.com/apis/credentials")!

    @State private var step: Int
    @State private var groups: Set<String> = []
    @State private var showAdvanced = false
    @State private var customClientID = ""
    @State private var isConnecting = false
    @State private var connectError: String?
    @State private var connected: GoogleHealthAccount?
    @State private var writeRequested = false
    @State private var firstSyncStarted = false
    @State private var firstSyncDone = false

    init(startStep: Int = 1) {
        _step = State(initialValue: min(max(startStep, 1), Self.totalSteps))
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                progressHeader
                ZStack {
                    switch step {
                    case 1: introStep
                    case 2: groupsStep
                    case 3: consentStep
                    default: finishStep
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("googleHealth.setup.step.\(step)")
                .transition(.asymmetric(
                    insertion: .move(edge: .trailing).combined(with: .opacity),
                    removal: .move(edge: .leading).combined(with: .opacity)
                ))
                .animation(.snappy, value: step)
            }
            .background(AppColors.appBackground)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(firstSyncDone ? String(localized: "Done") : String(localized: "Cancel")) { dismiss() }
                        .disabled(isConnecting)
                }
            }
            .interactiveDismissDisabled(isConnecting || (firstSyncStarted && !firstSyncDone))
        }
        .onAppear {
            groups = store.enabledGroups
            customClientID = store.customClientID
            // `connected` is only set by this sheet's own consent run, so Manage Data Types and
            // Reconnect always go back through Google.
            showAdvanced = !customClientID.isEmpty || !store.hasBundledClient
        }
    }

    // MARK: - Chrome

    private var progressHeader: some View {
        HStack(spacing: 16) {
            if step > 1, step < Self.totalSteps, !isConnecting {
                Button {
                    withAnimation(.snappy) { step -= 1 }
                } label: {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(.primary)
                }
                .accessibilityLabel(Text("Back"))
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Color.primary.opacity(0.08))
                    Capsule()
                        .fill(Color.primary)
                        .frame(width: geo.size.width * Double(step) / Double(Self.totalSteps))
                        .animation(.snappy, value: step)
                }
            }
            .frame(height: 4)
        }
        .padding(.horizontal, 24)
        .padding(.top, 12)
        .padding(.bottom, 8)
    }

    private func stepHeader(title: String, subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(size: 28, weight: .bold, design: .rounded))
            if !subtitle.isEmpty {
                Text(subtitle).font(.system(.callout, design: .rounded)).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 24).padding(.top, 24)
    }

    private func continueButton(_ title: String = String(localized: "Continue"), disabled: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(.body, design: .rounded, weight: .semibold))
                .frame(maxWidth: .infinity)
                .frame(height: 40)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .buttonBorderShape(.capsule)
        .disabled(disabled)
        .padding(.horizontal, 24)
        .padding(.bottom, 36)
        .accessibilityIdentifier("googleHealth.setup.continue")
    }

    private func advance() {
        withAnimation(.snappy) { step = min(step + 1, Self.totalSteps) }
    }

    private func bullet(_ icon: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 20))
                .foregroundStyle(AppColors.calorie)
                .frame(width: 28)
            Text(text)
                .font(.system(.callout, design: .rounded))
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - 1: Intro & privacy

    private var introStep: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    stepHeader(
                        title: String(localized: "Connect Google Health", comment: "Google Health setup step 1 title"),
                        subtitle: String(localized: "Fitbit and Pixel Watch data, in Ayuvo", comment: "Google Health setup step 1 subtitle")
                    )
                    VStack(spacing: 18) {
                        bullet("figure.walk", String(localized: "Steps, heart rate, sleep, workouts, weight, nutrition and more from your Google account.", comment: "Google Health setup intro"))
                        bullet("iphone", String(localized: "The data is saved on this iPhone and shown in Browse, Summary and Coach like your Apple Health data.", comment: "Google Health setup intro"))
                        bullet("lock.shield", String(localized: "Ayuvo talks to Google directly. There is no Ayuvo server, and nothing syncs in the background unless you turn on Auto-sync.", comment: "Google Health setup intro"))
                        bullet("heart.text.square", String(localized: "Optionally, Ayuvo copies the data into Apple Health.", comment: "Google Health setup intro"))
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 28)
                }
            }
            continueButton { advance() }
        }
    }

    // MARK: - 2: Choose data

    static func groupTitle(_ group: GoogleHealthMap.ScopeGroup) -> String {
        switch group.id {
        case "body_vitals": return String(localized: "Body & vitals", comment: "Google Health data group")
        case "activity": return String(localized: "Activity & workouts", comment: "Google Health data group")
        case "sleep": return String(localized: "Sleep", comment: "Google Health data group")
        case "nutrition": return String(localized: "Nutrition", comment: "Google Health data group")
        case "heart_rhythm": return String(localized: "ECG & irregular rhythm", comment: "Google Health data group")
        default: return group.displayName
        }
    }

    private var customIDValid: Bool {
        let trimmed = customClientID.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? store.hasBundledClient : GoogleOAuthPKCE.isValidIOSClientID(trimmed)
    }

    private var groupsStep: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    stepHeader(
                        title: String(localized: "Choose your data", comment: "Google Health setup step 2 title"),
                        subtitle: String(localized: "Google asks you to allow each group. You can change this later.", comment: "Google Health setup step 2 subtitle")
                    )
                    VStack(spacing: 10) {
                        ForEach(store.map?.scopeGroups ?? []) { group in
                            Toggle(isOn: Binding(
                                get: { groups.contains(group.id) },
                                set: { on in if on { groups.insert(group.id) } else { groups.remove(group.id) } }
                            )) {
                                Text(Self.groupTitle(group))
                                    .font(.system(.body, design: .rounded, weight: .semibold))
                            }
                            .tint(AppColors.calorie)
                            .padding(16)
                            .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16))
                            .accessibilityIdentifier("googleHealth.setup.group.\(group.id)")
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 24)

                    DisclosureGroup(isExpanded: $showAdvanced) {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Use my own Google Cloud client ID", comment: "Google Health setup advanced option")
                                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            TextField(String(localized: "123456-abc.apps.googleusercontent.com", comment: "Placeholder for a Google OAuth iOS client id"), text: $customClientID)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .font(.system(.footnote, design: .monospaced))
                                .padding(12)
                                .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 12))
                                .accessibilityIdentifier("googleHealth.setup.clientId")
                            Text("Create an iOS OAuth client with bundle ID \(Bundle.main.bundleIdentifier ?? "com.ayuvo.health") in a Google Cloud project that has the Google Health API enabled.", comment: "Google Health custom client id help; placeholder is the app bundle id")
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                            Button {
                                openURL(Self.consoleURL)
                            } label: {
                                Label("How to create one", systemImage: "arrow.up.right.square")
                                    .font(.system(.caption, design: .rounded, weight: .semibold))
                            }
                            if !store.hasBundledClient, customClientID.isEmpty {
                                Text("This build has no built-in Google client, so a client ID is required.", comment: "Google Health setup when the bundled client id is missing")
                                    .font(.system(.caption, design: .rounded))
                                    .foregroundStyle(.orange)
                            } else if !customIDValid {
                                Text("That is not an iOS OAuth client ID (it should end in .apps.googleusercontent.com).")
                                    .font(.system(.caption, design: .rounded))
                                    .foregroundStyle(.orange)
                            }
                        }
                        .padding(.top, 8)
                    } label: {
                        Text("Advanced", comment: "Google Health setup disclosure")
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 24)
                }
            }
            continueButton(disabled: groups.isEmpty || !customIDValid) {
                connectError = nil
                advance()
            }
        }
    }

    // MARK: - 3: Google consent

    private var consentStep: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    stepHeader(
                        title: String(localized: "Sign in with Google", comment: "Google Health setup step 3 title"),
                        subtitle: String(localized: "Choose your Google account and allow the data you picked.", comment: "Google Health setup step 3 subtitle")
                    )
                    VStack(alignment: .leading, spacing: 12) {
                        if let connected {
                            if let email = connected.email {
                                Label(email, systemImage: "person.crop.circle.badge.checkmark")
                                    .font(.system(.body, design: .rounded, weight: .semibold))
                            }
                            ForEach((store.map?.scopeGroups ?? []).filter { groups.contains($0.id) }) { group in
                                let granted = store.grantedGroups.contains(group.id)
                                Label {
                                    Text(Self.groupTitle(group))
                                } icon: {
                                    Image(systemName: granted ? "checkmark.circle.fill" : "xmark.circle")
                                        .foregroundStyle(granted ? Color.green : Color.secondary)
                                }
                                .font(.system(.callout, design: .rounded))
                            }
                            if !groups.isSubset(of: store.grantedGroups) {
                                Text("Some data was not allowed. Ayuvo skips it; use Manage Data Types to allow it later.", comment: "Google Health partial consent note")
                                    .font(.system(.caption, design: .rounded))
                                    .foregroundStyle(.secondary)
                            }
                        }
                        if let connectError {
                            Text(connectError)
                                .font(.system(.callout, design: .rounded))
                                .foregroundStyle(.orange)
                        }
                        if isConnecting {
                            ProgressView()
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 28)
                }
            }
            if connected != nil, connectError == nil, store.grantedGroups.isEmpty == false, !isConnecting {
                continueButton { advance() }
            } else {
                continueButton(String(localized: "Continue with Google", comment: "Google Health setup consent button"), disabled: isConnecting) {
                    Task { await connect() }
                }
            }
        }
    }

    private func connect() async {
        isConnecting = true
        connectError = nil
        defer { isConnecting = false }
        do {
            connected = try await store.connect(groups: groups, customClientID: customClientID)
            if store.grantedGroups.isEmpty {
                connectError = String(localized: "No data was allowed. Try again and tick at least one group on Google's page.", comment: "Google Health consent with no data scopes")
            }
        } catch GoogleHealthAuthError.cancelled {
            connectError = nil
        } catch {
            connectError = error.localizedDescription
        }
    }

    // MARK: - 4: Apple Health + first sync

    private var finishStep: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    stepHeader(
                        title: String(localized: "First sync", comment: "Google Health setup step 4 title"),
                        subtitle: String(localized: "Ayuvo reads the last 90 days. Later syncs only fetch what is new.", comment: "Google Health setup step 4 subtitle")
                    )
                    VStack(alignment: .leading, spacing: 16) {
                        if HKHealthStore.isHealthDataAvailable() {
                            Toggle(isOn: Binding(
                                get: { store.writeBackEnabled },
                                set: { store.setWriteBack($0) }
                            )) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Also write to Apple Health", comment: "Google Health setup write-back toggle")
                                        .font(.system(.body, design: .rounded, weight: .semibold))
                                    Text("Apple Health asks which types Ayuvo may write.", comment: "Google Health setup write-back toggle subtitle")
                                        .font(.system(.caption, design: .rounded))
                                        .foregroundStyle(.secondary)
                                }
                            }
                            .tint(AppColors.calorie)
                            .padding(16)
                            .background(AppColors.appCard, in: RoundedRectangle(cornerRadius: 16))
                            .disabled(firstSyncStarted)
                        }

                        if firstSyncStarted {
                            VStack(alignment: .leading, spacing: 8) {
                                ProgressView(value: firstSyncDone ? 1 : (store.progress?.fraction ?? 0))
                                    .tint(AppColors.calorie)
                                Text(syncStatusText)
                                    .font(.system(.callout, design: .rounded))
                                    .foregroundStyle(.secondary)
                                    .accessibilityAddTraits(.updatesFrequently)
                            }
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 28)
                }
            }
            if firstSyncDone {
                continueButton(String(localized: "Done")) { dismiss() }
            } else {
                continueButton(String(localized: "Start Sync", comment: "Google Health setup start first sync"), disabled: firstSyncStarted) {
                    Task { await startFirstSync() }
                }
            }
        }
    }

    private var syncStatusText: String {
        if firstSyncDone {
            switch store.lastOutcome {
            case .synced(_, let rows, _)?:
                return String(localized: "Done — \(rows) records synced.", comment: "Google Health first sync finished; placeholder is the record count")
            case .reauthRequired?:
                return String(localized: "Google access has expired or was removed. Reconnect to keep syncing.", comment: "Google Health error")
            case .failed(let message)?:
                return message
            default:
                return String(localized: "Done.", comment: "Google Health first sync finished")
            }
        }
        guard let progress = store.progress else { return String(localized: "Starting…", comment: "Google Health first sync starting") }
        if progress.mirroring {
            return String(localized: "Writing to Apple Health… \(progress.mirrored)", comment: "Google Health progress; placeholder is the number of records written")
        }
        return String(localized: "\(progress.typesDone) of \(progress.typesTotal) data types · \(progress.rowsCommitted) records", comment: "Google Health first sync progress")
    }

    private func startFirstSync() async {
        firstSyncStarted = true
        if store.writeBackEnabled, HKHealthStore.isHealthDataAvailable(), !writeRequested {
            writeRequested = true
            _ = await healthKitManager.requestGoogleHealthWriteAuthorization()
        }
        await store.sync()
        firstSyncDone = true
    }
}
