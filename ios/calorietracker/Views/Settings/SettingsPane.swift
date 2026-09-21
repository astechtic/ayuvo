import SwiftUI

/// Settings groups in the order they appear on the Settings hub (plan §6).
enum SettingsGroup: CaseIterable, Identifiable {
    case healthProfile, tracking, notifications, dataPrivacy, aiSpeech, appearance, about

    var id: Self { self }

    var title: LocalizedStringResource? {
        switch self {
        case .healthProfile: "Health Profile"
        case .tracking: "Tracking"
        case .notifications: nil
        case .dataPrivacy: "Data & Privacy"
        case .aiSpeech: "AI & Speech"
        case .appearance: nil
        case .about: "About"
        }
    }

    var panes: [SettingsPane] { SettingsPane.allCases.filter { $0.group == self } }
}

/// One Settings screen (replaces `ProfileSettingsCategory`). Raw values back the
/// `settings.category.<rawValue>` accessibility ids, so the ids UI tests use (`aiProviders`,
/// `speechToText`, `dataManagement`, `healthRecords`, `appUpdates`, `helpSupport`, `legal`) are kept.
enum SettingsPane: String, CaseIterable, Identifiable, Hashable {
    case personalInfo, goalsNutrition, units
    case nutritionTracking, hydration, fasting, activity, medications
    case notifications
    case healthData, healthRecords, dataManagement, deleteData
    case aiProviders, speechToText, customInstructions
    case appearance
    case appUpdates, helpSupport, legal

    var id: Self { self }

    var group: SettingsGroup {
        switch self {
        case .personalInfo, .goalsNutrition, .units: .healthProfile
        case .nutritionTracking, .hydration, .fasting, .activity, .medications: .tracking
        case .notifications: .notifications
        case .healthData, .healthRecords, .dataManagement, .deleteData: .dataPrivacy
        case .aiProviders, .speechToText, .customInstructions: .aiSpeech
        case .appearance: .appearance
        case .appUpdates, .helpSupport, .legal: .about
        }
    }

    var title: LocalizedStringResource {
        switch self {
        case .personalInfo: "Personal Info"
        case .goalsNutrition: "Goals & Targets"
        case .units: "Units"
        case .nutritionTracking: "Nutrition"
        case .hydration: "Hydration"
        case .fasting: "Fasting"
        case .activity: "Activity"
        case .medications: "Medications"
        case .notifications: "Notifications"
        case .healthData: "Health Sync"
        case .healthRecords: "Health Records"
        case .dataManagement: "Backup & Export"
        case .deleteData: "Delete All Data"
        case .aiProviders: "AI Providers"
        case .speechToText: "Speech-to-Text"
        case .customInstructions: "Custom Instructions"
        case .appearance: "Appearance"
        case .appUpdates: "App & Updates"
        case .helpSupport: "Help & Support"
        case .legal: "Legal"
        }
    }

    var systemImage: String {
        switch self {
        case .personalInfo: "person.fill"
        case .goalsNutrition: "target"
        case .units: "ruler.fill"
        case .nutritionTracking: "fork.knife"
        case .hydration: "drop.fill"
        case .fasting: "timer"
        case .activity: "figure.walk"
        case .medications: "pills.fill"
        case .notifications: "bell.badge.fill"
        case .healthData: "heart.fill"
        case .healthRecords: "doc.text.fill"
        case .dataManagement: "externaldrive.fill"
        case .deleteData: "trash.fill"
        case .aiProviders: "sparkles"
        case .speechToText: "waveform"
        case .customInstructions: "text.quote"
        case .appearance: "paintpalette.fill"
        case .appUpdates: "arrow.triangle.2.circlepath"
        case .helpSupport: "questionmark.bubble.fill"
        case .legal: "lock.shield.fill"
        }
    }

    var tint: Color {
        switch self {
        case .personalInfo: AyuvoPalette.body
        case .goalsNutrition, .nutritionTracking: AyuvoPalette.nutrition
        case .hydration, .appearance: AyuvoPalette.hydration
        case .fasting: AyuvoPalette.fasting
        case .activity: AyuvoPalette.activity
        case .medications: AyuvoPalette.medications
        case .notifications, .deleteData: AyuvoPalette.heart
        case .healthData: AyuvoPalette.vitals
        case .healthRecords: AyuvoPalette.records
        case .aiProviders: AyuvoPalette.sleep
        case .speechToText: AyuvoPalette.mindfulness
        case .units, .dataManagement, .customInstructions, .appUpdates, .helpSupport, .legal: AyuvoPalette.other
        }
    }

    var aboutCategory: AboutSettingsCategory? {
        switch self {
        case .appUpdates: .appUpdates
        case .helpSupport: .helpSupport
        case .legal: .legal
        default: nil
        }
    }
}

/// A hub row in the system-Settings style: filled coloured glyph, title, chevron.
struct SettingsPaneRow: View {
    let pane: SettingsPane
    var badge = false
    @ScaledMetric(relativeTo: .body) private var iconSize: CGFloat = 29

    var body: some View {
        NavigationLink(value: pane) {
            HStack(spacing: 12) {
                CategoryIconView(systemImage: pane.systemImage, tint: pane.tint, style: .filled, size: iconSize)
                Text(pane.title)
                    .font(.system(.body, design: .rounded))
                    .foregroundStyle(pane == .deleteData ? Color.red : Color.primary)
                if badge {
                    Spacer()
                    Text("Update")
                        .font(.system(.caption, design: .rounded, weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 2)
                        .background(Color.red, in: Capsule())
                }
            }
        }
        .accessibilityIdentifier("settings.category.\(pane.rawValue)")
    }
}
