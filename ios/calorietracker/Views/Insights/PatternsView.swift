import SwiftUI

/// Ayuvo Patterns: associations in the person's own data, each with its sample sizes. A pattern is listed only
/// when it passes the consistency and size checks; the rest show what is still needed.
struct PatternsView: View {
    @Environment(InsightsStore.self) private var store

    private var surfaced: [PatternResult] { store.patterns.filter(\.surfaced) }
    private var others: [PatternResult] { store.patterns.filter { !$0.surfaced } }

    var body: some View {
        InsightsScreen(title: "Patterns", topic: .patterns, disclaimers: ["general", "patterns"],
                       inputs: { Self.inputRows(store.patterns) }) {
            Label(InsightsConfig.shared.disclaimer("patterns"), systemImage: "info.circle")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 4)
                .accessibilityIdentifier("patterns.note")
            if surfaced.isEmpty {
                InsightsCollectingView(title: String(localized: "No clear patterns yet"),
                                       detail: String(localized: "Patterns need at least 8 days with and 8 days without something, and a consistent difference."),
                                       collecting: nil)
            }
            ForEach(surfaced) { pattern in
                VStack(alignment: .leading, spacing: 6) {
                    Text(InsightsPatternText.title(pattern.id))
                        .font(.system(.caption, design: .rounded, weight: .semibold))
                        .foregroundStyle(AyuvoPalette.insights)
                    Text(pattern.text ?? "")
                        .font(.system(.subheadline, design: .rounded))
                        .fixedSize(horizontal: false, vertical: true)
                    Text("\(pattern.nExposed) vs \(pattern.nUnexposed) days")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                .ayuvoCard()
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("patterns.item.\(pattern.id)")
            }
            if !others.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    AyuvoSectionHeader("Still checking")
                    ForEach(others) { pattern in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(InsightsPatternText.title(pattern.id))
                                .font(.system(.subheadline, design: .rounded))
                            Text(Self.status(pattern))
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .ayuvoCard()
            }
        }
    }

    static func status(_ p: PatternResult) -> String {
        if p.status != "ok" {
            return String(localized: "Needs \(p.needed) days in each group (\(p.nExposed) and \(p.nUnexposed) so far)")
        }
        return String(localized: "No consistent difference yet (\(p.nExposed) vs \(p.nUnexposed) days)")
    }

    static func inputRows(_ patterns: [PatternResult]) -> [InsightsInputRow] {
        patterns.map { p in
            InsightsInputRow(id: p.id, title: InsightsPatternText.title(p.id),
                             value: "\(p.nExposed) vs \(p.nUnexposed)",
                             detail: p.status == "ok"
                                ? String(localized: "t \(p.t.map { InsightsFormat.number($0) } ?? InsightsText.missing) · d \(p.d.map { InsightsFormat.number($0) } ?? InsightsText.missing)")
                                : status(p),
                             missing: p.status != "ok")
        }
    }
}
