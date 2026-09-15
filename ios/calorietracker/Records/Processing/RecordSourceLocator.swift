import Foundation

/// Finds the outline box for a source jump (§24) when a row has `source_page` but no stored
/// `source_bbox`: the page's `blocks_json` line boxes whose text appears in the evidence line,
/// taken from the row of the first matching cell. Pure; runs off the main thread.
nonisolated enum RecordSourceLocator {
    struct Block {
        var text: String
        var box: [Double]
    }

    static func blocks(_ json: String?) -> [Block] {
        guard let json, let root = RJ.parse(json), let list = root.array else { return [] }
        return list.compactMap { item in
            guard let text = item["t"].string, let values = item["b"].array?.compactMap(\.double), values.count == 4 else { return nil }
            return Block(text: text, box: values)
        }
    }

    /// Union box of the evidence's cells on `page`, or nil.
    static func box(evidence: String?, page: Int, pages: [RecordPage]) -> [Double]? {
        guard let evidence, let stored = pages.first(where: { $0.pageIndex == page }) else { return nil }
        let wanted = " " + RR.normText(evidence) + " "
        guard wanted.count > 2 else { return nil }
        let candidates = blocks(stored.blocksJSON).filter { block in
            let norm = RR.normText(block.text)
            return !norm.isEmpty && wanted.contains(" " + norm + " ")
        }
        guard let anchor = candidates.first(where: { wanted.hasPrefix(" " + RR.normText($0.text) + " ") }) ?? candidates.first else { return nil }
        let centre = anchor.box[1] + anchor.box[3] / 2
        let row = candidates.filter { abs(($0.box[1] + $0.box[3] / 2) - centre) <= max(anchor.box[3], $0.box[3]) * 0.6 }
        let minX = row.map { $0.box[0] }.min() ?? anchor.box[0]
        let minY = row.map { $0.box[1] }.min() ?? anchor.box[1]
        let maxX = row.map { $0.box[0] + $0.box[2] }.max() ?? anchor.box[0] + anchor.box[2]
        let maxY = row.map { $0.box[1] + $0.box[3] }.max() ?? anchor.box[1] + anchor.box[3]
        return [minX, minY, maxX - minX, maxY - minY]
    }
}
