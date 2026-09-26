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

enum AppUpdateState: Equatable {
    case idle
    case checking
    case upToDate(current: String, latest: String?)
    case available(current: String, latest: String, url: URL)
    case failed(current: String)

    var isUpdateAvailable: Bool {
        if case .available = self {
            return true
        }
        return false
    }

    var hasStartedCheck: Bool {
        if case .idle = self {
            return false
        }
        return true
    }
}

private struct AppStoreLookupResponse: Decodable {
    let results: [AppStoreLookupResult]
}

private struct AppStoreLookupResult: Decodable {
    let version: String
    let trackViewUrl: String?
}

enum AppUpdateChecker {
    static var currentVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "Unknown"
    }

    static var currentVersionDisplay: String {
        currentVersion
    }

    static func check() async -> AppUpdateState {
        let current = currentVersion

        // Until the App Store record exists the placeholder id can't be looked up; show the
        // installed version instead of a failed check. Only Apple's public lookup is contacted.
        guard AppLinks.hasAppStoreID else {
            return .upToDate(current: current, latest: nil)
        }
        let url = AppLinks.lookupURL

        do {
            let (data, response) = try await URLSession.shared.data(from: url)
            guard let httpResponse = response as? HTTPURLResponse,
                  (200..<300).contains(httpResponse.statusCode) else {
                return .failed(current: current)
            }

            let lookup = try JSONDecoder().decode(AppStoreLookupResponse.self, from: data)
            guard let result = lookup.results.first else {
                return .upToDate(current: current, latest: nil)
            }

            let updateURL = result.trackViewUrl.flatMap(URL.init(string:)) ?? AppLinks.appStoreURL
            if isVersion(result.version, newerThan: current) {
                return .available(current: current, latest: result.version, url: updateURL)
            }

            return .upToDate(current: current, latest: result.version)
        } catch {
            return .failed(current: current)
        }
    }

    private static func isVersion(_ latest: String, newerThan current: String) -> Bool {
        let latestParts = latest.split(separator: ".").map { Int($0) ?? 0 }
        let currentParts = current.split(separator: ".").map { Int($0) ?? 0 }
        let count = max(latestParts.count, currentParts.count)

        for index in 0..<count {
            let latestValue = index < latestParts.count ? latestParts[index] : 0
            let currentValue = index < currentParts.count ? currentParts[index] : 0

            if latestValue > currentValue {
                return true
            }
            if latestValue < currentValue {
                return false
            }
        }

        return false
    }
}


// MARK: - About (embedded as the last Settings section)
enum AboutSettingsCategory: String, CaseIterable, Identifiable, Hashable {
    case appUpdates
    case helpSupport
    case legal

    var id: Self { self }

    var title: LocalizedStringResource {
        switch self {
        case .appUpdates: "App & Updates"
        case .helpSupport: "Help & Support"
        case .legal: "Legal"
        }
    }

    var systemImage: String {
        switch self {
        case .appUpdates: "arrow.triangle.2.circlepath.circle.fill"
        case .helpSupport: "questionmark.bubble.fill"
        case .legal: "lock.shield.fill"
        }
    }
}

struct AboutAppHeaderSection: View {
    var body: some View {
        Section {
            VStack(spacing: 8) {
                Image("onboardingLogo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 64, height: 64)
                    .accessibilityHidden(true)

                Text("Ayuvo")
                    .font(.system(.title2, design: .rounded, weight: .bold))

                Text("Version \(AppUpdateChecker.currentVersionDisplay)")
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
        }
        .listRowBackground(AppColors.appCard)
    }
}

struct AboutSettingsSections: View {
    private let category: AboutSettingsCategory
    @Binding private var updateState: AppUpdateState
    private let refreshUpdateState: () async -> Void

    @State private var showShareSheet = false
    @State private var showNoMailClientAlert = false

    init(
        category: AboutSettingsCategory,
        updateState: Binding<AppUpdateState>,
        refreshUpdateState: @escaping () async -> Void
    ) {
        self.category = category
        self._updateState = updateState
        self.refreshUpdateState = refreshUpdateState
    }

    private var shareMessage: String {
        String(localized: "I've been tracking my health with Ayuvo — meals, workouts, sleep and more in one place.\n\nDownload: https://ayuvo-health.web.app")
    }

    var body: some View {
        Group {
            switch category {
            case .appUpdates:
                Section {
                updateRow

                Button {
                    requestNativeReview()
                } label: {
                    Label {
                        Text("Rate the App")
                    } icon: {
                        SettingsIcon("star.fill", tint: SettingsTint.rating)
                    }
                }
                .tint(.primary)

                Button {
                    showShareSheet = true
                } label: {
                    Label {
                        Text("Share the App")
                    } icon: {
                        SettingsIcon("square.and.arrow.up.fill", tint: SettingsTint.export)
                    }
                }
                .tint(.primary)
                }
                .listRowBackground(AppColors.appCard)

            case .helpSupport:
                Section {
                Button {
                    SupportMail.open(.support) { opened in
                        if !opened { showNoMailClientAlert = true }
                    }
                } label: {
                    Label {
                        Text("Contact Support")
                    } icon: {
                        SettingsIcon("envelope.fill", tint: SettingsTint.privacy)
                    }
                }
                .tint(.primary)

                Button {
                    SupportMail.open(.feedback) { opened in
                        if !opened { showNoMailClientAlert = true }
                    }
                } label: {
                    Label {
                        Text("Send Feedback")
                    } icon: {
                        SettingsIcon("lightbulb.fill", tint: SettingsTint.feedback)
                    }
                }
                .tint(.primary)
                } footer: {
                    Text("Your message opens in your mail app with the app version, iOS version, device model and locale prefilled so we can help faster. Nothing is sent until you tap Send.")
                }
                .listRowBackground(AppColors.appCard)

            case .legal:
                Section {
                Link(destination: AppLinks.privacyURL) {
                    Label {
                        Text("Privacy Policy")
                    } icon: {
                        SettingsIcon("hand.raised.fill", tint: SettingsTint.privacy)
                    }
                }
                .tint(.primary)

                Link(destination: AppLinks.termsURL) {
                    Label {
                        Text("Terms of Service")
                    } icon: {
                        SettingsIcon("doc.text.fill", tint: SettingsTint.legal)
                    }
                }
                .tint(.primary)

                Link(destination: AppLinks.githubURL) {
                    Label {
                        Text("Source code on GitHub")
                    } icon: {
                        SettingsIcon("chevron.left.forwardslash.chevron.right", tint: SettingsTint.legal)
                    }
                }
                .tint(.primary)

                NavigationLink {
                    LicensesView()
                } label: {
                    Label {
                        Text("Licenses")
                    } icon: {
                        SettingsIcon("doc.text.magnifyingglass", tint: SettingsTint.legal)
                    }
                }
                .tint(.primary)
                }
                .listRowBackground(AppColors.appCard)
            }
        }
        .sheet(isPresented: $showShareSheet) {
            ActivityShareSheet(activityItems: [shareMessage, AppLinks.appStoreURL])
        }
        .alert("No mail app found", isPresented: $showNoMailClientAlert) {
            Button("OK", role: .cancel) { }
        } message: {
            Text("The support address \(AppLinks.supportEmail) was copied to your clipboard.")
        }
    }

    @ViewBuilder
    private var updateRow: some View {
        switch updateState {
        case .checking:
            HStack {
                Label {
                    Text("Checking for Updates")
                } icon: {
                    SettingsIcon("arrow.triangle.2.circlepath", tint: SettingsTint.update)
                }

                Spacer()

                ProgressView()
                    .tint(AppColors.calorie)
            }

        case .available(let current, let latest, let url):
            Button {
                UIApplication.shared.open(url)
            } label: {
                HStack(spacing: 12) {
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Update Available")
                            Text("Current \(current) -> Latest \(latest)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        SettingsIcon("arrow.down", tint: SettingsTint.update)
                            .overlay(alignment: .topTrailing) {
                                Circle()
                                    .fill(SettingsTint.notifications)
                                    .frame(width: 10, height: 10)
                                    .offset(x: 3, y: -3)
                            }
                    }

                    Spacer()

                    Text("Update")
                        .fontWeight(.semibold)
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .tint(.primary)

        case .failed:
            Button {
                Task {
                    await refreshUpdateState()
                }
            } label: {
                HStack {
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Check for Updates")
                            Text("Version \(AppUpdateChecker.currentVersionDisplay)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        SettingsIcon("arrow.triangle.2.circlepath", tint: SettingsTint.update)
                    }

                    Spacer()
                }
            }
            .tint(.primary)

        case .idle, .upToDate:
            Button {
                Task {
                    await refreshUpdateState()
                }
            } label: {
                HStack {
                    Label {
                        Text("App Version")
                    } icon: {
                        SettingsIcon("checkmark.seal.fill", tint: SettingsTint.success)
                    }

                    Spacer()

                    Text(AppUpdateChecker.currentVersionDisplay)
                        .foregroundStyle(.secondary)
                }
            }
            .tint(.primary)
        }
    }

    private func requestNativeReview() {
        if let scene = UIApplication.shared.connectedScenes
            .first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene {
            AppStore.requestReview(in: scene)
        }
    }
}

// MARK: - Share Sheet wrapper (UIActivityViewController)
// Used by AboutView so the personalized message AND the App Store URL
// both reach every share target. SwiftUI's ShareLink message arg is
// dropped by most targets; UIActivityViewController forwards every item.
struct ActivityShareSheet: UIViewControllerRepresentable {
    let activityItems: [Any]
    var applicationActivities: [UIActivity]? = nil

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: applicationActivities)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
