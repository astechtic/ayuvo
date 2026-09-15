import SwiftUI

/// Live-region status line at the top of the hub: last sync, import progress, limited
/// history, locked HealthKit, degraded database, or the "Grant access" prompt.
struct HealthSyncStatusView: View {
    @Environment(HealthDataStore.self) private var store
    @Environment(HealthKitManager.self) private var healthKitManager

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                statusIcon
                Text(statusText)
                    .font(.system(.footnote, design: .rounded, weight: .medium))
                    .foregroundStyle(.secondary)
                    .accessibilityAddTraits(.updatesFrequently)
                Spacer()
                if store.isSyncing {
                    ProgressView()
                        .controlSize(.small)
                }
            }
            if store.isSyncing, let progress = store.progress, progress.importing, progress.typesTotal > 0 {
                ProgressView(value: progress.fraction)
                    .tint(AppColors.calorie)
            }
            if store.needsGrant == true, store.isEnabled {
                Button {
                    Task {
                        _ = await healthKitManager.requestAuthorization()
                        store.authorizationDidChange()
                    }
                } label: {
                    Label("Grant access", systemImage: "lock.open.fill")
                        .font(.system(.footnote, design: .rounded, weight: .semibold))
                }
                .buttonStyle(.borderedProminent)
                .tint(AppColors.calorie)
            }
            if let limited = store.anyLimitedHistoryBefore {
                Label {
                    Text("History before \(limited.formatted(date: .abbreviated, time: .omitted)) isn't shared with Ayuvo — Health › Sharing › Apps › Ayuvo")
                        .font(.system(.caption2, design: .rounded))
                        .foregroundStyle(.secondary)
                } icon: {
                    Image(systemName: "clock.arrow.circlepath")
                        .foregroundStyle(.orange)
                }
            }
            if store.isEnabled, store.needsGrant == true || store.anyLimitedHistoryBefore != nil || !store.hasAnyData {
                OpenHealthAppButton(title: "Open Health app")
                    .font(.system(.footnote, design: .rounded, weight: .semibold))
            }
            if let quarantined = store.openError, !store.isDegraded {
                Text(quarantined.localizedDescription)
                    .font(.system(.caption2, design: .rounded))
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private var statusIcon: some View {
        if store.isDegraded {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
        } else if store.isLocked {
            Image(systemName: "lock.fill").foregroundStyle(.orange)
        } else if !store.isEnabled {
            Image(systemName: "pause.circle.fill").foregroundStyle(.secondary)
        } else {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
        }
    }

    private var statusText: String {
        if store.isDegraded {
            return String(localized: "Health database unavailable — try Clear synced health data in Settings")
        }
        if !store.isEnabled {
            return store.hasAnyData
                ? String(localized: "Health sync is off — data is read-only")
                : String(localized: "Connect Apple Health to start syncing")
        }
        if store.isLocked {
            return String(localized: "Unlock your iPhone to sync Health data")
        }
        if store.isSyncing, let progress = store.progress {
            if progress.importing {
                return String(localized: "Importing history… \(progress.typesDone) of \(progress.typesTotal) types")
            }
            return String(localized: "Syncing…")
        }
        if store.needsGrant == true, !store.hasAnyData {
            return String(localized: "Nothing shared yet — open Health › Sharing › Apps › Ayuvo")
        }
        if let lastSyncAt = store.lastSyncAt {
            let count = store.typeCountWithData
            return String(localized: "Last synced \(HealthUnitFormatting.relativeText(lastSyncAt)) · \(count) data types")
        }
        return String(localized: "Not synced yet — pull down to refresh")
    }
}
