import SwiftUI

/// Renders an assistant reply (docs/coach.md §4). Blocks come from `CR.parseBlocks`, the shared
/// parser, so iOS and Android split the same text the same way; inline emphasis, code spans and
/// links stay with `AttributedString(markdown:)`.
///
/// Replaces the hand-rolled parser that used to live in `ChatView.swift` and only knew headings,
/// bullets, numbers and code fences. New here: tables, block quotes, nested lists, rules, task lists
/// and charts.
struct CoachMarkdownView: View {
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(CoachMarkdownCache.blocks(for: text)) { block in
                view(for: block)
            }
        }
    }

    @ViewBuilder
    private func view(for block: CoachBlock) -> some View {
        switch block.kind {
        case "heading":
            Text(inline(block.text))
                .font(.system(headingStyle(block.level), design: .rounded, weight: .bold))
                .padding(.top, block.level <= 2 ? 2 : 0)

        case "paragraph":
            Text(inline(block.text))
                .font(.system(.body, design: .rounded))
                .fixedSize(horizontal: false, vertical: true)

        case "bullet":
            listRow(depth: block.depth, marker: AnyView(
                Text("•").font(.system(.body, design: .rounded)).foregroundStyle(.secondary)
            ), text: block.text)

        case "numbered":
            listRow(depth: block.depth, marker: AnyView(
                Text("\(block.marker).")
                    .font(.system(.body, design: .rounded, weight: .medium))
                    .foregroundStyle(.secondary)
            ), text: block.text)

        case "task":
            listRow(depth: block.depth, marker: AnyView(
                Image(systemName: block.checked ? "checkmark.square.fill" : "square")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(block.checked ? AppColors.calorie : Color.secondary)
            ), text: block.text)

        case "quote":
            HStack(alignment: .top, spacing: 10) {
                RoundedRectangle(cornerRadius: 1.5)
                    .fill(AppColors.calorie.opacity(0.5))
                    .frame(width: 3)
                Text(inline(block.text))
                    .font(.system(.body, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.leading, CGFloat(block.depth - 1) * 12)

        case "code":
            VStack(alignment: .leading, spacing: 4) {
                if let language = block.lang {
                    Text(language)
                        .font(.system(.caption2, design: .monospaced))
                        .foregroundStyle(.tertiary)
                }
                ScrollView(.horizontal, showsIndicators: false) {
                    Text(block.text)
                        .font(.system(.callout, design: .monospaced))
                        .textSelection(.enabled)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))

        case "table":
            CoachTableView(headers: block.headers, aligns: block.aligns, rows: block.rows)

        case "rule":
            Divider().padding(.vertical, 2)

        case "chart":
            if block.chartOK, let spec = block.spec {
                CoachChartView(spec: spec)
            } else {
                // A spec that does not parse renders as its own text, never as a guessed chart
                // (docs/coach.md rule 1).
                VStack(alignment: .leading, spacing: 4) {
                    Label(Self.chartFailureText(block.reason), systemImage: "exclamationmark.triangle")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                    ScrollView(.horizontal, showsIndicators: false) {
                        Text(block.text)
                            .font(.system(.caption, design: .monospaced))
                            .textSelection(.enabled)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(10)
                .background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
            }

        default:
            Text(inline(block.text)).font(.system(.body, design: .rounded))
        }
    }

    /// Why the chart is not drawn, in the four groups of docs/coach.md §5. Saying which one it is
    /// turns "it did not work" into something the user can act on (or report).
    static func chartFailureText(_ reason: String?) -> String {
        switch CR.chartReasonGroup(reason) {
        case "unsupported": String(localized: "Chart could not be read: that chart type is not supported")
        case "too_big": String(localized: "Chart could not be read: it was too large to draw")
        case "no_readings": String(localized: "Chart could not be read: it had no readings to draw")
        default: String(localized: "Chart could not be read: the chart data was malformed")
        }
    }

    private func listRow(depth: Int, marker: AnyView, text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            marker.frame(minWidth: 16, alignment: .trailing)
            Text(inline(text))
                .font(.system(.body, design: .rounded))
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(.leading, CGFloat(depth) * 16)
    }

    private func headingStyle(_ level: Int) -> Font.TextStyle {
        switch level {
        case 1: .title3
        case 2: .headline
        case 3: .subheadline
        default: .footnote
        }
    }

    private func inline(_ string: String) -> AttributedString {
        (try? AttributedString(markdown: string, options: .init(
            interpretedSyntax: .inlineOnlyPreservingWhitespace,
            failurePolicy: .returnPartiallyParsedIfPossible
        ))) ?? AttributedString(string)
    }
}

/// A table from the assistant. Scrolls sideways rather than squeezing columns on a phone.
struct CoachTableView: View {
    let headers: [String]
    let aligns: [String]
    let rows: [[String]]

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                row(headers, isHeader: true)
                ForEach(Array(rows.enumerated()), id: \.offset) { index, cells in
                    Divider()
                    row(cells, isHeader: false)
                        .background(index.isMultiple(of: 2) ? Color.clear : Color.secondary.opacity(0.05))
                }
            }
            .background(Color.secondary.opacity(0.06), in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.secondary.opacity(0.15), lineWidth: 0.6))
        }
    }

    private func row(_ cells: [String], isHeader: Bool) -> some View {
        HStack(alignment: .top, spacing: 0) {
            ForEach(Array(cells.enumerated()), id: \.offset) { index, cell in
                Text(cell)
                    .font(.system(isHeader ? .caption : .footnote, design: .rounded,
                                  weight: isHeader ? .semibold : .regular))
                    .foregroundStyle(isHeader ? .secondary : .primary)
                    .frame(minWidth: 64, alignment: alignment(index))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 7)
            }
        }
    }

    private func alignment(_ index: Int) -> Alignment {
        switch index < aligns.count ? aligns[index] : "left" {
        case "center": .center
        case "right": .trailing
        default: .leading
        }
    }
}

/// One parsed block, flattened out of the shared parser's JSON so the view does not index into `RJ`.
struct CoachBlock: Identifiable {
    let id: Int
    var kind: String
    var text: String = ""
    var level: Int = 1
    var depth: Int = 0
    var marker: String = ""
    var checked: Bool = false
    var lang: String?
    var headers: [String] = []
    var aligns: [String] = []
    var rows: [[String]] = []
    var chartOK: Bool = false
    var reason: String?
    var spec: RJ?

    init(id: Int, value: RJ) {
        self.id = id
        kind = value["kind"].string ?? "paragraph"
        text = value["text"].string ?? ""
        level = value["level"].double.map { Int($0) } ?? 1
        depth = value["depth"].double.map { Int($0) } ?? 0
        marker = value["marker"].string ?? ""
        checked = value["checked"].bool ?? false
        lang = value["lang"].string
        headers = (value["headers"].array ?? []).compactMap(\.string)
        aligns = (value["aligns"].array ?? []).compactMap(\.string)
        rows = (value["rows"].array ?? []).map { row in (row.array ?? []).compactMap(\.string) }
        chartOK = value["ok"].bool ?? false
        reason = value["reason"].string
        spec = value["spec"].isNull ? nil : value["spec"]
    }
}

/// Parsing is cheap but not free, and a long conversation re-renders often.
enum CoachMarkdownCache {
    private static let cache: NSCache<NSString, NSArray> = {
        let cache = NSCache<NSString, NSArray>()
        cache.countLimit = 48
        return cache
    }()

    static func blocks(for text: String) -> [CoachBlock] {
        let key = text as NSString
        if let cached = cache.object(forKey: key) as? [CoachBlock] {
            return cached
        }
        let parsed = (CR.parseBlocks(text)["blocks"].array ?? [])
            .enumerated()
            .map { CoachBlock(id: $0.offset, value: $0.element) }
        cache.setObject(parsed as NSArray, forKey: key)
        return parsed
    }
}
