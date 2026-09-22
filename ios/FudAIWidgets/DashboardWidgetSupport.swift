import WidgetKit
import SwiftUI

/// Timeline entry for the Today and My Metrics widgets. `snapshot` is nil until the app has
/// written one (the widgets then ask the user to open Ayuvo instead of showing numbers).
struct DashboardEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetDashboardSnapshot?

    /// The snapshot as it should render at this entry's date (day-scoped values cleared after midnight).
    var current: WidgetDashboardSnapshot? { snapshot?.display(at: date) }
}

enum DashboardTimeline {
    /// One entry now plus one at every dose time, the fasting goal and midnight within the next
    /// day, refreshed at least every 30 minutes (docs/widgets.md "Freshness").
    static func entries(now: Date = Date()) -> Timeline<DashboardEntry> {
        let snapshot = WidgetDashboardSnapshot.read()
        var entries = [DashboardEntry(date: now, snapshot: snapshot)]
        let horizon = now.addingTimeInterval(24 * 3600)
        for date in snapshot?.refreshDates(after: now) ?? [] where date <= horizon {
            entries.append(DashboardEntry(date: date, snapshot: snapshot))
            if entries.count >= 12 { break }
        }
        let fallback = now.addingTimeInterval(30 * 60)
        return Timeline(entries: entries, policy: .after(fallback))
    }
}

enum DashboardPalette {
    static let eat = Color(dashboardHex: "#34C759")
    static let move = Color(dashboardHex: "#FF9500")
    static let drink = Color(dashboardHex: "#007AFF")
    static let fasting = Color(dashboardHex: "#00C7BE")
    static let medications = Color(dashboardHex: "#32ADE6")
    static let body = Color(dashboardHex: "#AF52DE")
}

extension Color {
    /// `#RRGGBB` from the metric catalog; grey when malformed.
    init(dashboardHex: String) {
        let digits = dashboardHex.hasPrefix("#") ? String(dashboardHex.dropFirst()) : dashboardHex
        let value = UInt(digits, radix: 16) ?? 0x8E8E93
        self.init(
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255
        )
    }
}

/// Flat single-colour activity ring (the app's `ActivityRingView` lives in the app target).
struct DashboardRing: View {
    let progress: Double
    let tint: Color
    var lineWidth: CGFloat = 8

    var body: some View {
        ZStack {
            Circle()
                .stroke(tint.opacity(0.18), lineWidth: lineWidth)
            Circle()
                .trim(from: 0, to: max(0, min(1, progress)))
                .stroke(tint, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .padding(lineWidth / 2)
    }
}

/// Shown until the app has written its first dashboard snapshot.
struct DashboardEmptyView: View {
    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: "heart.text.square.fill")
                .font(.title2)
                .foregroundStyle(DashboardPalette.eat)
            Text("Open Ayuvo to see today")
                .font(.system(.caption, design: .rounded, weight: .semibold))
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

extension WidgetDashboardSnapshot.Ring {
    /// Text for the ring's value line: "Connect" when Apple Health is off.
    var displayValue: String {
        state == .connect ? String(localized: "Connect") : valueText
    }
}
