import SwiftUI

/// "Get More From Ayuvo": setup steps that disappear once done; dismissible for good.
struct SummaryChecklistCard: View {
    @Environment(AppNavigator.self) private var navigator
    @Environment(RecordsStore.self) private var recordsStore
    @Environment(MedicationStore.self) private var medicationStore
    @Environment(NotificationManager.self) private var notificationManager
    @AppStorage("healthKitEnabled") private var healthKitEnabled = false
    @AppStorage("notificationsEnabled") private var notificationsEnabled = false
    @AppStorage(ActivitySettings.summaryChecklistDismissedKey) private var dismissed = false

    private struct Item: Identifiable {
        let id: String
        let title: String
        let systemImage: String
        let action: () -> Void
    }

    private var items: [Item] {
        var result: [Item] = []
        if !healthKitEnabled {
            result.append(Item(id: "health", title: String(localized: "Connect Apple Health"), systemImage: "heart.fill") {
                navigator.openSettings(.healthData)
            })
        }
        if !notificationsEnabled || notificationManager.authorizationStatus == .denied {
            result.append(Item(id: "reminders", title: String(localized: "Turn on reminders"), systemImage: "bell.fill") {
                navigator.openSettings(.notifications)
            })
        }
        if recordsStore.hasLoadedOnce, recordsStore.totalCount == 0 {
            result.append(Item(id: "records", title: String(localized: "Add a health record"), systemImage: "doc.text.fill") {
                recordsStore.requestAddRecord()
            })
        }
        if medicationStore.hasLoadedOnce, medicationStore.totalCount == 0 {
            result.append(Item(id: "medications", title: String(localized: "Add your medications"), systemImage: "pills.fill") {
                navigator.openMedications()
            })
        }
        return result
    }

    var body: some View {
        let items = items
        if !dismissed, !items.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                AyuvoSectionHeader("Get More From Ayuvo") {
                    Button("Dismiss") { dismissed = true }
                        .accessibilityIdentifier("summary.checklist.dismiss")
                }
                VStack(spacing: 0) {
                    ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                        Button(action: item.action) {
                            HStack(spacing: 12) {
                                CategoryIconView(systemImage: item.systemImage, tint: AppColors.calorie)
                                Text(item.title)
                                    .font(.system(.body, design: .rounded))
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(.tertiary)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("summary.checklist.\(item.id)")
                        if index < items.count - 1 {
                            Divider().padding(.vertical, 10)
                        }
                    }
                }
                .ayuvoCard()
            }
            .accessibilityIdentifier("summary.checklist")
        }
    }
}
