import SwiftUI
import UIKit

/// Navigation values pushed on the Records tab's stack.
enum RecordsRoute: Hashable {
    case detail(String)
    /// Record detail opened at an observation's source page (trend points, Values hits).
    case detailSource(recordID: String, observationID: String)
    /// Full trend for an analyte (§21).
    case trend(String)
}

extension View {
    /// Attaches the Records destinations to a `NavigationStack` content view.
    func recordsRouteDestinations() -> some View {
        navigationDestination(for: RecordsRoute.self) { route in
            switch route {
            case .detail(let id):
                RecordDetailView(recordID: id)
            case .detailSource(let id, let observationID):
                RecordDetailView(recordID: id, initialObservationID: observationID)
            case .trend(let analyteID):
                RecordTrendView(analyteID: analyteID)
            }
        }
    }
}

/// Phase 1 filter chips (plan §1 screen map).
enum RecordsFilterChip: String, CaseIterable, Identifiable {
    case all, needsReview, reports, prescriptions, lab, imaging, doctorNotes, discharge, bills, images, pdfs, notes, received, favorites, archived

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: String(localized: "All")
        case .needsReview: String(localized: "Needs Review")
        case .reports: String(localized: "Reports")
        case .prescriptions: String(localized: "Prescriptions")
        case .lab: String(localized: "Lab")
        case .imaging: String(localized: "Imaging")
        case .doctorNotes: String(localized: "Doctor Notes")
        case .discharge: String(localized: "Discharge")
        case .bills: String(localized: "Bills")
        case .images: String(localized: "Images")
        case .pdfs: String(localized: "PDFs")
        case .notes: String(localized: "Notes")
        case .received: String(localized: "Received")
        case .favorites: String(localized: "Favorites")
        case .archived: String(localized: "Archived")
        }
    }

    func query(text: String) -> RecordQuery {
        var query = RecordQuery(text: text)
        switch self {
        case .all: break
        case .needsReview: query.needsReviewOnly = true
        case .reports: query.recordTypes = [.labReport, .imagingReport, .diagnosticReport]
        case .prescriptions: query.recordTypes = [.prescription, .medicationList]
        case .lab: query.categories = [.labReports]
        case .imaging: query.categories = [.imaging]
        case .doctorNotes: query.recordTypes = [.consultationNote]
        case .discharge: query.recordTypes = [.dischargeSummary]
        case .bills: query.categories = [.insuranceBills]
        case .images: query.fileTypes = [.image]
        case .pdfs: query.fileTypes = [.pdf]
        case .notes: query.categories = [.personalNotes]
        case .received: query.receivedOnly = true
        case .favorites: query.favoritesOnly = true
        case .archived: query.archivedOnly = true
        }
        return query
    }
}

/// Small in-memory cache for 320 px thumbnails (decoded off the main thread).
@MainActor
enum RecordThumbnailCache {
    private static let cache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 300
        return cache
    }()

    static func image(for url: URL) -> UIImage? {
        cache.object(forKey: url.path as NSString)
    }

    static func load(_ url: URL) async -> UIImage? {
        if let cached = image(for: url) { return cached }
        let path = url.path
        let image = await Task.detached(priority: .utility) { () -> UIImage? in
            guard let data = FileManager.default.contents(atPath: path), let image = UIImage(data: data) else { return nil }
            return image.preparingForDisplay() ?? image
        }.value
        if let image { cache.setObject(image, forKey: path as NSString) }
        return image
    }

    static func removeAll() {
        cache.removeAllObjects()
    }
}

struct RecordThumbnailView: View {
    let record: HealthRecord
    var cornerRadius: CGFloat = 10
    @Environment(RecordsStore.self) private var store
    @State private var image: UIImage?

    private var thumbnailURL: URL? { store.thumbnailURL(for: record) }

    var body: some View {
        // The fill defines the size; the image only overlays it, so scaledToFill never grows the cell.
        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            .fill(AppColors.calorie.opacity(0.08))
            .overlay {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                } else {
                    Image(systemName: placeholderIcon)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(AppColors.calorie)
                }
            }
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        .task(id: thumbnailURL) {
            guard let thumbnailURL else {
                image = nil
                return
            }
            image = RecordThumbnailCache.image(for: thumbnailURL)
            if image == nil {
                image = await RecordThumbnailCache.load(thumbnailURL)
            }
        }
        .accessibilityHidden(true)
    }

    private var placeholderIcon: String {
        switch record.fileType {
        case .pdf: "doc.richtext.fill"
        case .image: "photo.fill"
        case .text: record.recordType == .personalNote ? "note.text" : "text.alignleft"
        case .other: "doc.fill"
        }
    }
}

struct RecordTypePill: View {
    let type: RecordType

    var body: some View {
        Text(type.title)
            .font(.system(.caption2, design: .rounded, weight: .semibold))
            .foregroundStyle(AppColors.calorie)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(AppColors.calorie.opacity(0.12), in: Capsule())
            .lineLimit(1)
    }
}

enum RecordFormatting {
    static func dateText(_ record: HealthRecord) -> String {
        guard let date = record.displayDate else { return record.effectiveDate }
        return date.formatted(date: .abbreviated, time: .omitted)
    }

    static func monthTitle(_ monthKey: String) -> String {
        guard let date = RecordDates.date(fromDay: monthKey + "-01") else { return monthKey }
        return date.formatted(.dateTime.month(.wide).year())
    }

    static func sizeText(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    static func subtitle(_ record: HealthRecord) -> String {
        var parts = [dateText(record)]
        switch record.fileType {
        case .pdf:
            if record.pageCount > 0 {
                parts.append(record.pageCount == 1 ? String(localized: "1 page") : String(localized: "\(record.pageCount) pages"))
            } else {
                parts.append("PDF")
            }
        case .image: parts.append(String(localized: "Image"))
        case .text: parts.append(String(localized: "Text"))
        case .other: parts.append(String(localized: "File"))
        }
        if record.isReceived { parts.append(String(localized: "Received")) }
        if record.isSplitChild, let start = record.pageStart, let end = record.pageEnd {
            parts.append(start == end ? String(localized: "Page \(start + 1)") : String(localized: "Pages \(start + 1)–\(end + 1)"))
        }
        return parts.joined(separator: " · ")
    }
}

struct RecordRow: View {
    let record: HealthRecord
    var isSelecting = false
    var isSelected = false
    var compact = false
    /// Phase 3: the record has accepted links (timeline "Episode" badge).
    var episode = false

    var body: some View {
        HStack(spacing: 12) {
            if isSelecting {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22))
                    .foregroundStyle(isSelected ? AppColors.calorie : Color.secondary)
            }
            RecordThumbnailView(record: record)
                .frame(width: compact ? 40 : 48, height: compact ? 40 : 48)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(record.title)
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(compact ? 1 : 2)
                    if record.favorite {
                        Image(systemName: "star.fill")
                            .font(.caption2)
                            .foregroundStyle(.yellow)
                    }
                }
                HStack(spacing: 6) {
                    if episode { RecordEpisodeBadge() }
                    if !compact { RecordTypePill(type: record.recordType) }
                    Text(RecordFormatting.subtitle(record))
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            RecordStatusIndicator(record: record)
        }
        .padding(.vertical, compact ? 6 : 8)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

struct RecordGridCell: View {
    let record: HealthRecord
    var isSelecting = false
    var isSelected = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            RecordThumbnailView(record: record, cornerRadius: 12)
                .aspectRatio(0.78, contentMode: .fit)
                .overlay(alignment: .topTrailing) {
                    if isSelecting {
                        Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                            .font(.system(size: 20))
                            .foregroundStyle(isSelected ? AppColors.calorie : Color.white)
                            .shadow(radius: 2)
                            .padding(6)
                    } else if record.favorite {
                        Image(systemName: "star.fill")
                            .font(.caption)
                            .foregroundStyle(.yellow)
                            .padding(6)
                    }
                }
            Text(record.title)
                .font(.system(.caption, design: .rounded, weight: .semibold))
                .foregroundStyle(.primary)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
            Text(RecordFormatting.dateText(record))
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.secondary)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

struct RecordsBannerView: View {
    let banner: RecordsBanner

    var body: some View {
        Label(banner.message, systemImage: banner.systemImage)
            .font(.system(.subheadline, design: .rounded, weight: .semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(
                LinearGradient(colors: AppColors.calorieGradient, startPoint: .leading, endPoint: .trailing),
                in: Capsule()
            )
            .shadow(color: .black.opacity(0.15), radius: 8, y: 3)
            .accessibilityIdentifier("records.banner")
    }
}

struct RecordsPrivacyExplainer: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            explainerRow("iphone", "Your health records are stored on this iPhone, in Ayuvo's own storage.")
            explainerRow("icloud.slash", "Records are not included in your iPhone backup. Export All Data can save a copy.")
            explainerRow("doc.on.doc", "The original file is kept exactly as you imported it.")
            explainerRow("square.and.arrow.up", "Sharing or exporting sends only the records you choose, where you choose.")
            explainerRow("eye.slash", "Before you share, you can leave out pages and black out names, IDs and phone numbers. Redaction is best-effort, so always check the preview.")
            explainerRow("archivebox", "A backup archive contains every record and original file. You decide where to keep it; Ayuvo never uploads it.")
            explainerRow("trash", "Deleting a record removes it and its file permanently. Delete All Data removes every record.")
        }
    }

    private func explainerRow(_ icon: String, _ text: LocalizedStringKey) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
                .foregroundStyle(AppColors.calorie)
                .frame(width: 22)
            Text(text)
                .font(.system(.subheadline, design: .rounded))
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Small "Episode" capsule for records with accepted links (plan §3.10 timeline).
struct RecordEpisodeBadge: View {
    var body: some View {
        Label("Episode", systemImage: "link")
            .labelStyle(.titleAndIcon)
            .font(.system(.caption2, design: .rounded, weight: .semibold))
            .foregroundStyle(.teal)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color.teal.opacity(0.12), in: Capsule())
            .lineLimit(1)
            .accessibilityIdentifier("records.row.episode")
    }
}
