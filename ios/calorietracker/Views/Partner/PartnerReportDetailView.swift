import SwiftUI

/// One report overview a partner shared: summary, highlights, out-of-range results first (with reference ranges and
/// flags), all results, diagnoses, medications and recommendations. Never a document: those stay on their phone.
struct PartnerReportDetailView: View {
    let ownerID: String
    let recordID: String
    @State private var manager = PartnerManager.shared
    @State private var report: PartnerDashboardData.Report?
    @State private var loaded = false

    private var item: PartnerManager.PartnerItem? { manager.partners.first { $0.id == ownerID } }
    private var name: String { item?.name ?? "" }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if let report {
                    content(report)
                } else if loaded {
                    EmptyStateView("Report not available", systemImage: "doc.text.magnifyingglass",
                                   description: "It may have been removed by your partner or deleted from this phone.")
                } else {
                    ProgressView().frame(maxWidth: .infinity).padding(.top, 40)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .ayuvoScreenBackground()
        .navigationTitle(report?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .accessibilityIdentifier("partner.reportDetail")
    }

    private func load() async {
        guard let db = await manager.database() else {
            loaded = true
            return
        }
        let owner = ownerID
        let id = recordID
        let row = await Task.detached(priority: .userInitiated) { () -> PartnerDashboardData.Report? in
            guard let r = try? await db.record(owner, type: "report_overview", recordID: id) else { return nil }
            return PartnerDashboardData.Report(id: r.recordID, day: r.day, data: r.data)
        }.value
        report = row
        loaded = true
    }

    @ViewBuilder
    private func content(_ r: PartnerDashboardData.Report) -> some View {
        let d = r.data
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                PartnerAvatar(name: name, size: 30)
                Text("Shared by \(name)")
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .foregroundStyle(AyuvoPalette.partner)
                Spacer()
                PartnerReadOnlyBadge()
            }
            Text(verbatim: r.title)
                .font(.system(.title2, design: .rounded, weight: .bold))
                .fixedSize(horizontal: false, vertical: true)
            let meta = [r.reportDate.flatMap { PartnerDay.date($0) }.map { $0.formatted(date: .long, time: .omitted) },
                        d["doctor"].string, d["doctor_specialty"].string, d["facility"].string].compactMap { $0 }
            if !meta.isEmpty {
                Text(verbatim: meta.joined(separator: " · "))
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Label {
                Text("Overview only — the original document stays on \(name)'s phone.")
            } icon: {
                Image(systemName: "iphone")
            }
            .font(.system(.footnote, design: .rounded))
            .foregroundStyle(.secondary)
        }
        .ayuvoCard()

        if let summary = d["summary"].string, !summary.isEmpty {
            section("Summary") {
                Text(verbatim: summary).font(.system(.body, design: .rounded)).fixedSize(horizontal: false, vertical: true)
            }
        }
        textList("Highlights", d["highlights"].array)
        let abnormal = d["abnormal"].array ?? []
        if !abnormal.isEmpty {
            section("Out of range") {
                VStack(spacing: 12) {
                    ForEach(Array(abnormal.enumerated()), id: \.offset) { index, result in
                        if index > 0 { Divider() }
                        resultRow(result)
                    }
                }
            }
        }
        let results = d["results"].array ?? []
        if !results.isEmpty {
            section("All results") {
                VStack(spacing: 12) {
                    ForEach(Array(results.enumerated()), id: \.offset) { index, result in
                        if index > 0 { Divider() }
                        resultRow(result)
                    }
                }
            }
        }
        textList("Diagnoses", d["diagnoses"].array)
        if let meds = d["medications"].array, !meds.isEmpty {
            section("Medications") {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(meds.enumerated()), id: \.offset) { _, m in
                        VStack(alignment: .leading, spacing: 1) {
                            Text(verbatim: m["name"].string ?? "")
                                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            let detail = [m["strength"].string, m["dose"].string, m["frequency"].string, m["duration"].string].compactMap { $0 }
                            if !detail.isEmpty {
                                Text(verbatim: detail.joined(separator: " · "))
                                    .font(.system(.footnote, design: .rounded))
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
        textList("Recommendations", d["recommendations"].array)
        Text("Shared for information only. It isn't medical advice; ask a doctor about results.")
            .font(.system(.caption, design: .rounded))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 4)
    }

    private func section<Content: View>(_ title: LocalizedStringKey, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            AyuvoSectionHeader(title)
            content().ayuvoCard()
        }
    }

    @ViewBuilder
    private func textList(_ title: LocalizedStringKey, _ items: [RJ]?) -> some View {
        let texts = (items ?? []).compactMap(\.string).filter { !$0.isEmpty }
        if !texts.isEmpty {
            section(title) {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(texts.enumerated()), id: \.offset) { _, t in
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Circle().fill(AyuvoPalette.records).frame(width: 5, height: 5)
                            Text(verbatim: t).font(.system(.subheadline, design: .rounded)).fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            }
        }
    }

    private func resultRow(_ r: RJ) -> some View {
        let flag = r["flag"].string ?? "unknown"
        let flagText = PartnerDisplay.flagText(flag)
        return HStack(alignment: .top, spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: r["name"].string ?? "")
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .fixedSize(horizontal: false, vertical: true)
                if let range = PartnerDisplay.referenceRange(r) {
                    Text("Reference \(range)")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 8)
            VStack(alignment: .trailing, spacing: 3) {
                Text(verbatim: PartnerDisplay.resultValue(r))
                    .font(.ayuvoNumber(.subheadline))
                if !flagText.isEmpty {
                    Text(verbatim: flagText)
                        .font(.system(.caption2, design: .rounded, weight: .bold))
                        .foregroundStyle(PartnerDisplay.flagTint(flag))
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(PartnerDisplay.flagTint(flag).opacity(0.14), in: Capsule())
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}
