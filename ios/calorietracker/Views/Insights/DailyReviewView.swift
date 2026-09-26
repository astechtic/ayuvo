import SwiftUI

/// Daily Health Review for one of the last 7 days: Day Score, area scores, what went well, what needs attention,
/// what to try tomorrow and what to consider reducing. Areas without data show as not logged, never as 0.
struct DailyReviewView: View {
    var initialDay: String?
    @Environment(InsightsStore.self) private var store
    @State private var selectedDay: String?

    private var day: String? { selectedDay ?? initialDay ?? store.report?.today }
    private var review: DailyReviewResult? { day.flatMap { store.review(for: $0) } }

    static let sections: [(id: String, emoji: String, title: LocalizedStringKey)] = [
        ("went_well", "✅", "What went well"),
        ("needs_attention", "⚠️", "Needs attention"),
        ("improve", "🎯", "What to improve"),
        ("reduce", "↘", "Consider reducing"),
    ]

    var body: some View {
        InsightsScreen(title: "Daily Review", topic: .dailyReview, inputs: { Self.inputRows(review) }) {
            dayPicker
            if let review {
                scoreCard(review)
                ForEach(Self.sections, id: \.id) { section in
                    let items = review.items(section.id)
                    if !items.isEmpty {
                        itemsCard(emoji: section.emoji, title: section.title, items: items, id: section.id)
                    }
                }
                if !review.notLogged.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(review.notLogged, id: \.text) { item in
                            Label(item.text, systemImage: "minus.circle")
                                .font(.system(.subheadline, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                    }
                    .ayuvoCard()
                    .accessibilityIdentifier("review.notLogged")
                }
                InsightsExplainSection(kind: .dailyReview, summary: store.report?.summary(reviewDay: review.day))
                    .id(review.day)
            }
        }
    }

    private var dayPicker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(store.reviewDays.reversed(), id: \.self) { candidate in
                    let isSelected = candidate == day
                    Button {
                        selectedDay = candidate
                    } label: {
                        Text(InsightsText.dayTitle(candidate, today: store.report?.today))
                            .font(.system(.subheadline, design: .rounded, weight: isSelected ? .semibold : .regular))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .foregroundStyle(isSelected ? Color.white : Color.primary)
                            .background(isSelected ? AyuvoPalette.insights : AyuvoPalette.card, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("review.day.\(candidate)")
                }
            }
            .padding(.horizontal, 2)
        }
        .defaultScrollAnchor(.trailing)
    }

    private func scoreCard(_ review: DailyReviewResult) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 16) {
                InsightsScoreRing(score: review.dayScore, tint: InsightsText.scoreTint(review.dayScore), size: 88)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Day Score")
                        .font(.system(.headline, design: .rounded))
                    Text(review.dayScore == nil
                         ? String(localized: "Nothing logged or synced for this day yet.")
                         : String(localized: "From the areas you track and logged."))
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            ForEach(review.areas.filter(\.included)) { area in
                HStack {
                    Text(Self.areaLabel(area.id))
                        .font(.system(.subheadline, design: .rounded))
                    Spacer()
                    Text(area.score.map { "\($0)" } ?? InsightsText.missing)
                        .font(.ayuvoNumber(.subheadline))
                        .foregroundStyle(InsightsText.scoreTint(area.score))
                }
            }
        }
        .ayuvoCard()
        .accessibilityIdentifier("review.score")
    }

    private func itemsCard(emoji: String, title: LocalizedStringKey, items: [ReviewItem], id: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Text(verbatim: emoji)
                Text(title)
            }
            .font(.system(.headline, design: .rounded))
            ForEach(items, id: \.text) { item in
                Text(item.text)
                    .font(.system(.subheadline, design: .rounded))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .ayuvoCard()
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("review.section.\(id)")
    }

    static func areaLabel(_ id: String) -> String {
        InsightsConfig.shared.dailyReview.areas.first { $0.id == id }?.label ?? id
    }

    static func inputRows(_ review: DailyReviewResult?) -> [InsightsInputRow] {
        guard let review else { return [] }
        return review.areas.map { area in
            InsightsInputRow(
                id: area.id, title: areaLabel(area.id),
                value: area.score.map { "\($0)" } ?? String(localized: "Not logged"),
                detail: area.included ? String(localized: "Weight \(area.weight.formatted())%") : String(localized: "Not counted"),
                missing: !area.included
            )
        }
    }
}
