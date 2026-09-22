import SwiftUI

/// Connect / Grant-access screen. The Coach consent toggle is visible and pre-checked;
/// its value is stored only when the user confirms with Connect (never flipped silently).
struct HealthPermissionView: View {
    var compact = false
    @Environment(HealthDataStore.self) private var store
    @Environment(HealthKitManager.self) private var healthKitManager
    @AppStorage("healthKitEnabled") private var healthKitEnabled = false
    @State private var coachConsent = true
    @State private var isRequesting = false
    @State private var failed = false

    private let categories: [HealthCategory] = [.activity, .heart, .sleep, .body, .vitals, .respiratory, .mobility, .cycleTracking]

    var body: some View {
        VStack(alignment: .leading, spacing: compact ? 12 : 20) {
            if !compact {
                HStack {
                    Spacer()
                    ZStack {
                        Circle()
                            .fill(Color.pink.opacity(0.08))
                            .frame(width: 96, height: 96)
                        Image(systemName: "heart.text.square.fill")
                            .font(.system(size: 40))
                            .foregroundStyle(LinearGradient(colors: [.pink, .red], startPoint: .topLeading, endPoint: .bottomTrailing))
                    }
                    Spacer()
                }
            }

            VStack(alignment: .leading, spacing: 6) {
                Text(store.hasAnyData || store.needsGrant == true ? "Allow Apple Health access" : "Connect Apple Health")
                    .font(.system(compact ? .headline : .title2, design: .rounded, weight: .bold))
                Text("Ayuvo mirrors every Apple Health category you allow into a local database on this iPhone: kept on this device and shared with your AI provider only through Coach.")
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
            }

            if !compact {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(categories) { category in
                        HStack(spacing: 10) {
                            HealthIconBubble(systemImage: category.systemImage, tint: category.tint)
                            Text(category.displayName)
                                .font(.system(.subheadline, design: .rounded, weight: .medium))
                        }
                    }
                }
            }

            Toggle(isOn: $coachConsent) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Let Coach use my health data")
                        .font(.system(.subheadline, design: .rounded, weight: .medium))
                    Text("Sent to your AI provider only when Coach answers a question.")
                        .font(.system(.caption2, design: .rounded))
                        .foregroundStyle(.secondary)
                }.padding(16)
            }
            .tint(AppColors.calorie)

            Button {
                connect()
            } label: {
                HStack {
                    if isRequesting {
                        ProgressView()
                            .tint(.white)
                    }
                    Text(store.hasAnyData || store.needsGrant == true ? "Grant access" : "Connect")
                        .font(.system(.body, design: .rounded, weight: .semibold))
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 48)
                .background(AppColors.calorie, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(.plain)
            .disabled(isRequesting || !store.isHealthDataAvailable)

            if !store.isHealthDataAvailable {
                Text("Apple Health isn't available on this device.")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            if failed {
                Text("Apple Health didn't grant access. You can allow categories later in Health › Sharing › Apps › Ayuvo.")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(compact ? 4 : 20)
        .onAppear {
            if store.coachConsentedAt != nil {
                coachConsent = store.coachHealthDataEnabled
            }
        }
    }

    private func connect() {
        guard !isRequesting else { return }
        isRequesting = true
        failed = false
        Task {
            let authorized = await healthKitManager.requestAuthorization()
            isRequesting = false
            guard authorized else {
                failed = true
                return
            }
            healthKitEnabled = true
            store.setCoachAccess(coachConsent)
            healthKitManager.startBodyMeasurementObserver()
            store.authorizationDidChange()
        }
    }
}
