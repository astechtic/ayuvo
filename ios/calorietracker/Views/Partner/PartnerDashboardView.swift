import SwiftUI

/// Partner screens pushed on the Summary or Settings stack.
enum PartnerRoute: Hashable {
    /// A partner's read-only health dashboard.
    case dashboard(String)
    /// One report overview (owner id, record id).
    case report(String, String)
    /// Settings › Partner Health › one partner (sharing, sync, export, unpair / delete).
    case settings(String)
}

extension View {
    /// Registers the Partner screens. Attach once per stack (Summary, Settings).
    func partnerRouteDestinations() -> some View {
        navigationDestination(for: PartnerRoute.self) { route in
            switch route {
            case .dashboard(let id): PartnerDashboardView(ownerID: id)
            case .report(let owner, let record): PartnerReportDetailView(ownerID: owner, recordID: record)
            case .settings(let id): PartnerSettingsDetailView(ownerID: id)
            }
        }
    }
}

/// A partner's shared health, read-only and clearly theirs (pink accent, "Read-only", their name everywhere).
/// Shows only rows stored in `partner_records`; a section is hidden when that category was never shared, labelled
/// "No longer shared" when it was turned off, and shows an empty state when shared but empty.
struct PartnerDashboardView: View {
    let ownerID: String
    @State private var manager = PartnerManager.shared
    @State private var data: PartnerDashboardData?
    @State private var window: [PartnerRecordRow] = []
    @State private var latest: [PartnerRecordRow] = []
    @State private var reportRows: [PartnerRecordRow] = []
    @State private var withData: Set<String> = []
    @State private var day: String?
    @State private var trendDays = 7
    @State private var isSyncing = false

    private var item: PartnerManager.PartnerItem? { manager.partners.first { $0.id == ownerID } }
    private var today: String { PartnerDay.string(.now) }
    private var earliest: String { PartnerDay.adding(-29, to: today) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if let item {
                    header(item)
                    if let data {
                        content(item, data)
                    } else {
                        ProgressView().frame(maxWidth: .infinity).padding(.top, 40)
                    }
                } else {
                    EmptyStateView("Partner removed", systemImage: "person.crop.circle.badge.xmark",
                                   description: "This partner and their data are no longer on this phone.")
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .ayuvoScreenBackground()
        .navigationTitle(item?.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if item != nil {
                    NavigationLink(value: PartnerRoute.settings(ownerID)) {
                        Image(systemName: "slider.horizontal.3")
                    }
                    .accessibilityLabel(Text("Sharing settings"))
                }
            }
        }
        .task(id: item) { await load() }
        .onChange(of: day) { _, _ in rebuild() }
        .accessibilityIdentifier("partner.dashboard")
    }

    // MARK: Loading

    private func load() async {
        guard let item, let db = await manager.database() else { return }
        let id = ownerID
        let from = earliest
        let latestPrefixes = ["resting_heart_rate:", "daily_hrv:", "hrv_sdnn:", "hrv_rmssd:", "daily_blood_oxygen:", "blood_oxygen:", "blood_pressure:", "weight:"]
        let loaded = await Task.detached(priority: .userInitiated) { () -> ([PartnerRecordRow], [PartnerRecordRow], [PartnerRecordRow], Set<String>) in
            let rows = (try? await db.records(id, types: PartnerDashboardData.windowTypes, dayFrom: from)) ?? []
            var latest: [PartnerRecordRow] = []
            for prefix in latestPrefixes {
                if let r = try? await db.latestRecord(id, type: "metric_day", idPrefix: prefix) { latest.append(r) }
            }
            if let r = try? await db.latestRecord(id, type: "weight") { latest.append(r) }
            let reports = (try? await db.records(id, types: ["report_overview"], dayFrom: nil)) ?? []
            let cats = (try? await db.categoriesWithData(id)) ?? []
            return (rows, latest, reports, cats)
        }.value
        window = loaded.0
        latest = loaded.1
        reportRows = loaded.2
        withData = loaded.3
        if day == nil { day = PartnerDashboardData.defaultDay(rows: window, today: today, earliest: earliest) }
        _ = item
        rebuild()
    }

    private func rebuild() {
        guard let item else { return }
        data = PartnerDashboardData.build(day: day ?? today, rows: window, latest: latest, reports: reportRows,
                                          grants: item.grantsReceived, categoriesWithData: withData)
    }

    // MARK: Header

    private func header(_ item: PartnerManager.PartnerItem) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                PartnerAvatar(name: item.name, size: 48)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: item.name)
                        .font(.system(.title3, design: .rounded, weight: .bold))
                    Text(verbatim: PartnerRef.formatFingerprint(item.peer.fingerprint))
                        .font(.system(.caption2, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 8) {
                PartnerReadOnlyBadge()
                Text(verbatim: PartnerDisplay.freshness(item))
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            if item.peer.isTrusted {
                Button {
                    Task {
                        isSyncing = true
                        await manager.syncNow()
                        isSyncing = false
                    }
                } label: {
                    HStack {
                        if isSyncing || manager.isSyncing { ProgressView().controlSize(.small) }
                        Text(isSyncing || manager.isSyncing ? "Syncing…" : "Sync Now")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .tint(AyuvoPalette.partner)
                .disabled(isSyncing || manager.isSyncing)
                .accessibilityIdentifier("partner.dashboard.syncNow")
            }
        }
        .ayuvoCard()
    }

    // MARK: Content

    @ViewBuilder
    private func content(_ item: PartnerManager.PartnerItem, _ data: PartnerDashboardData) -> some View {
        let visible = PartnerCatalog.shared.categories.contains { data.state($0) != .hidden }
        if !visible {
            EmptyStateView("Nothing shared yet", systemImage: "person.2.slash",
                           description: "When your partner turns on sharing for you, their data appears here after the next sync.")
        } else {
            daySection(item, data)
            vitalsSection(data)
            trendsSection(data)
            reportsSection(item, data)
        }
        syncStatusSection(item)
    }

    private func daySection(_ item: PartnerManager.PartnerItem, _ data: PartnerDashboardData) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            AyuvoSectionHeader(verbatim: PartnerDisplay.dayTitle(data.day, today: today)) {
                HStack(spacing: 18) {
                    Button { day = PartnerDay.adding(-1, to: data.day) } label: { Image(systemName: "chevron.left") }
                        .disabled(data.day <= earliest)
                        .accessibilityLabel(Text("Previous day"))
                    Button { day = PartnerDay.adding(1, to: data.day) } label: { Image(systemName: "chevron.right") }
                        .disabled(data.day >= today)
                        .accessibilityLabel(Text("Next day"))
                }
                .tint(AyuvoPalette.partner)
            }
            if data.state("vitals") != .hidden { recoveryCard(data) }
            if data.state("sleep") != .hidden { sleepCard(data) }
            if data.state("nutrition") != .hidden { nutritionCard(data) }
            if data.state("workouts") != .hidden { workoutCard(data) }
            if data.state("medicines") != .hidden { medicinesCard(data) }
        }
    }

    private func cardTitle(_ title: String, category: String, systemImage: String, tint: Color, state: PartnerSectionState) -> some View {
        HStack(spacing: 8) {
            CategoryIconView(systemImage: systemImage, tint: tint, size: 28)
            Text(verbatim: title)
                .font(.system(.headline, design: .rounded))
            Spacer()
            if state == .revoked { PartnerNoLongerSharedBadge() }
        }
    }

    private func emptyLine(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(.system(.subheadline, design: .rounded))
            .foregroundStyle(.secondary)
    }

    private func recoveryCard(_ data: PartnerDashboardData) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            cardTitle(String(localized: "Recovery"), category: "vitals", systemImage: "bolt.heart.fill", tint: AyuvoPalette.insights, state: data.state("vitals"))
            if let r = data.recovery {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(verbatim: PartnerDisplay.number(r.value))
                        .font(.ayuvoNumber(.largeTitle))
                    Text(verbatim: "/ 100")
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                if let c = r.classification { Text(verbatim: c.replacingOccurrences(of: "_", with: " ").capitalized).font(.subheadline).foregroundStyle(.secondary) }
            } else {
                emptyLine("No Recovery score for this day.")
            }
        }
        .ayuvoCard()
        .accessibilityElement(children: .combine)
    }

    private func sleepCard(_ data: PartnerDashboardData) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            cardTitle(String(localized: "Sleep"), category: "sleep", systemImage: "bed.double.fill", tint: AyuvoPalette.sleep, state: data.state("sleep"))
            if let s = data.sleep {
                Text(verbatim: PartnerDisplay.duration(minutes: s.asleepMin))
                    .font(.ayuvoNumber(.title))
                if let start = s.startMs, let end = s.endMs {
                    Text(verbatim: "\(PartnerDisplay.time(ms: start)) – \(PartnerDisplay.time(ms: end))")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                if s.hasStages { PartnerSleepStagesBar(sleep: s) }
                if let hr = s.avgHR {
                    Text("Sleeping heart rate \(PartnerDisplay.value(key: "heart_rate", value: hr, unit: nil))")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            } else {
                emptyLine("No sleep recorded for this night.")
            }
        }
        .ayuvoCard()
    }

    private func nutritionCard(_ data: PartnerDashboardData) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            cardTitle(PartnerCategoryInfo.title("nutrition"), category: "nutrition", systemImage: "fork.knife", tint: AyuvoPalette.nutrition, state: data.state("nutrition"))
            if data.food.isEmpty && data.waterMl == nil {
                emptyLine("No food or water logged on this day.")
            } else {
                if let kcal = data.calories {
                    HStack(alignment: .firstTextBaseline, spacing: 16) {
                        Text("\(PartnerDisplay.number(kcal)) kcal").font(.ayuvoNumber(.title2))
                        macro("P", data.macroTotal(\.proteinG), AyuvoPalette.protein)
                        macro("C", data.macroTotal(\.carbsG), AyuvoPalette.carbs)
                        macro("F", data.macroTotal(\.fatG), AyuvoPalette.fat)
                    }
                }
                ForEach(data.food) { f in
                    HStack {
                        Text(verbatim: [f.emoji, f.name].compactMap { $0 }.joined(separator: " "))
                            .font(.system(.subheadline, design: .rounded))
                            .lineLimit(1)
                        Spacer()
                        Text("\(PartnerDisplay.number(f.calories)) kcal")
                            .font(.ayuvoNumber(.subheadline))
                            .foregroundStyle(.secondary)
                    }
                }
                if let water = data.waterMl {
                    Label {
                        Text("Water \(PartnerDisplay.number(water)) ml")
                    } icon: {
                        Image(systemName: "drop.fill").foregroundStyle(AyuvoPalette.hydration)
                    }
                    .font(.system(.subheadline, design: .rounded))
                }
            }
        }
        .ayuvoCard()
    }

    private func macro(_ letter: String, _ grams: Double?, _ tint: Color) -> some View {
        Group {
            if let grams {
                Text(verbatim: "\(letter) \(PartnerDisplay.number(grams)) g")
                    .font(.system(.caption, design: .rounded, weight: .semibold))
                    .foregroundStyle(tint)
            }
        }
    }

    private func workoutCard(_ data: PartnerDashboardData) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            cardTitle(PartnerCategoryInfo.title("workouts"), category: "workouts", systemImage: "figure.run", tint: AyuvoPalette.activity, state: data.state("workouts"))
            if data.workouts.isEmpty {
                if data.showsRestDay { emptyLine("Rest day — no workouts logged.") } else { emptyLine("No workouts on this day.") }
            }
            ForEach(data.workouts) { w in
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: PartnerDisplay.workoutTitle(w))
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    Text(verbatim: workoutDetail(w))
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
        }
        .ayuvoCard()
    }

    private func workoutDetail(_ w: PartnerDashboardData.Workout) -> String {
        var parts = [PartnerDisplay.duration(minutes: Int((w.durationS / 60).rounded()))]
        if let kcal = w.kcal { parts.append(String(localized: "\(PartnerDisplay.number(kcal)) kcal")) }
        if let d = w.distanceM, d > 0 {
            parts.append(Measurement(value: d, unit: UnitLength.meters).formatted(.measurement(width: .abbreviated, usage: .road)))
        }
        if let hr = w.avgHR { parts.append(PartnerDisplay.value(key: "heart_rate", value: hr, unit: nil)) }
        return parts.joined(separator: " · ")
    }

    private func medicinesCard(_ data: PartnerDashboardData) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            cardTitle(PartnerCategoryInfo.title("medicines"), category: "medicines", systemImage: "pills.fill", tint: AyuvoPalette.medications, state: data.state("medicines"))
            if data.doses.isEmpty {
                emptyLine("No doses logged on this day.")
            } else {
                Text(verbatim: PartnerDisplay.metricValue(PartnerSummaryMetric(key: "medicines", category: "medicines", value: data.dosesTaken,
                                                                               unit: "of:\(data.doses.count)", day: data.day, shared: true)))
                    .font(.ayuvoNumber(.title3))
                ForEach(data.doses) { d in
                    HStack {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(verbatim: d.medication ?? String(localized: "partner.dose.unknown_medicine", defaultValue: "Medicine", comment: "Partner dose whose medicine is not stored"))
                                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            if let ms = d.scheduledMs {
                                Text(verbatim: PartnerDisplay.time(ms: ms))
                                    .font(.system(.footnote, design: .rounded))
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        Text(verbatim: PartnerDisplay.doseStatus(d.status))
                            .font(.system(.footnote, design: .rounded, weight: .semibold))
                            .foregroundStyle(PartnerDisplay.doseTint(d.status))
                    }
                }
            }
        }
        .ayuvoCard()
    }

    @ViewBuilder
    private func vitalsSection(_ data: PartnerDashboardData) -> some View {
        if data.state("vitals") != .hidden {
            VStack(alignment: .leading, spacing: 12) {
                AyuvoSectionHeader("Latest vitals") {
                    if data.state("vitals") == .revoked { PartnerNoLongerSharedBadge() }
                }
                VStack(spacing: 12) {
                    if data.vitals.isEmpty {
                        emptyLine("No vitals shared yet.")
                    }
                    ForEach(data.vitals) { v in
                        MetricRow(systemImage: PartnerDisplay.vitalSystemImage(v.key), tint: PartnerDisplay.vitalTint(v.key),
                                  title: PartnerDisplay.vitalTitle(v.key), subtitle: PartnerDisplay.dayTitle(v.day, today: today),
                                  value: PartnerDisplay.value(key: v.key, value: v.value, value2: v.value2, unit: v.unit))
                    }
                }
                .ayuvoCard()
            }
        }
    }

    @ViewBuilder
    private func trendsSection(_ data: PartnerDashboardData) -> some View {
        let cutoff = PartnerDay.adding(-(trendDays - 1), to: today)
        let trends = data.trends.filter { data.state($0.category) != .hidden }
        if !trends.isEmpty {
            VStack(alignment: .leading, spacing: 12) {
                AyuvoSectionHeader("Trends")
                Picker("Range", selection: $trendDays) {
                    Text("7 days").tag(7)
                    Text("30 days").tag(30)
                }
                .pickerStyle(.segmented)
                VStack(spacing: 14) {
                    ForEach(trends) { t in
                        let points = t.points(from: cutoff)
                        HStack(spacing: 12) {
                            CategoryIconView(systemImage: PartnerDisplay.vitalSystemImage(t.key), tint: PartnerDisplay.vitalTint(t.key))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: PartnerDisplay.vitalTitle(t.key))
                                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                                if let last = points.last {
                                    Text(verbatim: PartnerDisplay.value(key: t.key, value: last.value, unit: t.unit) + " · " + PartnerDisplay.dayTitle(last.day, today: today))
                                        .font(.system(.caption, design: .rounded))
                                        .foregroundStyle(.secondary)
                                } else {
                                    Text("No data in this range")
                                        .font(.system(.caption, design: .rounded))
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Spacer(minLength: 8)
                            if points.count >= 2 {
                                SparklineView(values: points.map(\.value), tint: PartnerDisplay.vitalTint(t.key))
                                    .frame(width: 96, height: 32)
                            }
                        }
                        .opacity(data.state(t.category) == .revoked ? 0.55 : 1)
                    }
                }
                .ayuvoCard()
            }
        }
    }

    @ViewBuilder
    private func reportsSection(_ item: PartnerManager.PartnerItem, _ data: PartnerDashboardData) -> some View {
        let state = data.state("report_overviews")
        if state != .hidden {
            VStack(alignment: .leading, spacing: 12) {
                AyuvoSectionHeader("Health reports") {
                    if state == .revoked { PartnerNoLongerSharedBadge() }
                }
                VStack(alignment: .leading, spacing: 12) {
                    if data.reports.isEmpty {
                        emptyLine("No report overviews shared yet.")
                    }
                    ForEach(Array(data.reports.enumerated()), id: \.element.id) { index, r in
                        if index > 0 { Divider() }
                        NavigationLink(value: PartnerRoute.report(ownerID, r.id)) {
                            HStack(spacing: 12) {
                                CategoryIconView(systemImage: "doc.text.fill", tint: AyuvoPalette.records)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(verbatim: r.title)
                                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                                        .foregroundStyle(.primary)
                                        .multilineTextAlignment(.leading)
                                    Text(verbatim: reportCaption(r))
                                        .font(.system(.caption, design: .rounded))
                                        .foregroundStyle(.secondary)
                                }
                                Spacer(minLength: 4)
                                Image(systemName: "chevron.right")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(.tertiary)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("partner.report.\(r.id)")
                    }
                    Text("Overview only — the original documents stay on \(item.name)'s phone.")
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                .ayuvoCard()
            }
        }
    }

    private func reportCaption(_ r: PartnerDashboardData.Report) -> String {
        var parts: [String] = []
        if let d = r.reportDate, let date = PartnerDay.date(d) { parts.append(date.formatted(date: .abbreviated, time: .omitted)) }
        if r.abnormalCount > 0 {
            parts.append(String(localized: "partner.report.flagged", defaultValue: "\(r.abnormalCount) flagged", comment: "Number of out-of-range results in a partner's report"))
        } else if r.resultCount > 0 {
            parts.append(String(localized: "partner.report.results", defaultValue: "\(r.resultCount) results", comment: "Number of results in a partner's report"))
        }
        return parts.joined(separator: " · ")
    }

    private func syncStatusSection(_ item: PartnerManager.PartnerItem) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            AyuvoSectionHeader("Sync status")
            VStack(alignment: .leading, spacing: 8) {
                Text(verbatim: item.peer.isTrusted ? PartnerStatusText.status(item.status) : PartnerDisplay.shortStatus(item.status, trusted: false) ?? "")
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                Text(verbatim: PartnerDisplay.updated(lastSyncMs: item.state?.lastSyncMs))
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
                if item.state?.lastTransport == "package" {
                    Text("Last update came from a shared file.")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                Text("Phones sync directly over the same Wi-Fi or hotspot while both are awake, usually when Ayuvo is open. There's no cloud copy, so data can be a while old when your partner is away.")
                    .font(.system(.footnote, design: .rounded))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .ayuvoCard()
        }
    }
}

/// Deep / REM / light / awake minutes as one proportional bar with a legend.
struct PartnerSleepStagesBar: View {
    let sleep: PartnerDashboardData.Sleep

    private var stages: [(label: String, minutes: Int, color: Color)] {
        [
            (String(localized: "partner.sleep.deep", defaultValue: "Deep", comment: "Sleep stage"), sleep.deepMin ?? 0, AyuvoPalette.hypnogramDeep),
            (String(localized: "partner.sleep.rem", defaultValue: "REM", comment: "Sleep stage"), sleep.remMin ?? 0, AyuvoPalette.hypnogramREM),
            (String(localized: "partner.sleep.light", defaultValue: "Light", comment: "Sleep stage (core)"), sleep.lightMin ?? 0, AyuvoPalette.hypnogramLight),
            (String(localized: "partner.sleep.awake", defaultValue: "Awake", comment: "Sleep stage"), sleep.awakeMin ?? 0, AyuvoPalette.hypnogramAwake),
        ].filter { $0.1 > 0 }
    }

    var body: some View {
        let total = max(1, stages.reduce(0) { $0 + $1.minutes })
        VStack(alignment: .leading, spacing: 8) {
            GeometryReader { geo in
                HStack(spacing: 2) {
                    ForEach(stages, id: \.label) { s in
                        RoundedRectangle(cornerRadius: 3, style: .continuous)
                            .fill(s.color)
                            .frame(width: max(2, (geo.size.width - CGFloat(stages.count - 1) * 2) * CGFloat(s.minutes) / CGFloat(total)))
                    }
                }
            }
            .frame(height: 12)
            LazyVGrid(columns: [GridItem(.flexible(), alignment: .leading), GridItem(.flexible(), alignment: .leading)], alignment: .leading, spacing: 6) {
                ForEach(stages, id: \.label) { s in
                    HStack(spacing: 4) {
                        Circle().fill(s.color).frame(width: 8, height: 8)
                        Text(verbatim: "\(s.label) \(PartnerDisplay.duration(minutes: s.minutes))")
                            .font(.system(.caption2, design: .rounded))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}
