import Charts
import SwiftUI

/// Home card: header → hub, horizontal strip of pinned mini tiles (value, unit, relative
/// time, 7-day sparkline). Always renders an entry point — a "Connect Apple Health" tile
/// when sync is off, "Grant access" / "Importing…" / "Nothing shared yet" otherwise.
struct HealthHubHomeTile: View {
    @Environment(HealthDataStore.self) private var store

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            NavigationLink(value: HealthRoute.hub) {
                HStack(spacing: 6) {
                    Label {
                        Text("Health Data")
                            .font(.system(.headline, design: .rounded, weight: .semibold))
                    } icon: {
                        Image(systemName: "heart.text.square.fill")
                            .foregroundStyle(.pink)
                    }
                    Spacer()
                    if store.isSyncing {
                        ProgressView()
                            .controlSize(.small)
                    }
                    // The hosting list row draws the disclosure chevron; none is added here.
                }
            }
            .buttonStyle(.plain)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 10) {
                    content
                }
                .padding(.horizontal, 2)
            }
            .scrollClipDisabled()
        }
        .padding(14)
        .progressCardStyle()
        .task(id: store.snapshotRevision) {
            if store.homeSnapshot == nil, store.isEnabled {
                await store.refreshSnapshots()
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        if !store.isEnabled {
            HealthStateTile(
                title: "Connect Apple Health",
                subtitle: "Steps, heart, sleep & more",
                systemImage: "heart.fill",
                tint: .pink,
                route: .hub
            )
        } else if store.isDegraded {
            HealthStateTile(title: "Health database unavailable", subtitle: "Open the hub for details", systemImage: "exclamationmark.triangle.fill", tint: .orange, route: .hub)
        } else if store.needsGrant == true, !store.hasAnyData {
            HealthStateTile(title: "Grant access", subtitle: "Health › Sharing › Apps › Ayuvo", systemImage: "lock.open.fill", tint: .pink, route: .hub)
        } else if let snapshot = store.homeSnapshot, store.hasAnyData {
            ForEach(snapshot.tiles) { tile in
                NavigationLink(value: HealthRoute.metric(tile.typeID)) {
                    HealthMiniTile(model: tile, type: store.metricType(for: tile.typeID))
                }
                .buttonStyle(.plain)
            }
        } else if store.isSyncing || store.isImportingHistory {
            HealthStateTile(title: "Importing…", subtitle: "History arrives over the next minutes", systemImage: "arrow.triangle.2.circlepath", tint: .pink, route: .hub)
        } else {
            HealthStateTile(title: "Nothing shared yet", subtitle: "Health › Sharing › Apps › Ayuvo", systemImage: "heart.slash", tint: .secondary, route: .hub)
        }
    }
}

struct HealthMiniTile: View {
    let model: HealthHomeTileModel
    let type: HealthMetricType

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 4) {
                Image(systemName: type.category.systemImage)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(type.category.tint)
                Text(type.displayName)
                    .font(.system(.caption, design: .rounded, weight: .medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(model.valueText)
                    .font(.system(.title3, design: .rounded, weight: .semibold))
                    .contentTransition(.numericText())
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                if !model.unitText.isEmpty {
                    Text(model.unitText)
                        .font(.system(.caption2, design: .rounded))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 0)
            HStack(alignment: .bottom) {
                Text(model.at.map { HealthUnitFormatting.relativeText($0) } ?? String(localized: "No data"))
                    .font(.system(.caption2, design: .rounded))
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
                Spacer()
                if model.sparkline.count >= 2 {
                    HealthSparkline(values: model.sparkline, tint: type.category.tint)
                        .frame(width: 44, height: 18)
                }
            }
        }
        .padding(10)
        .frame(width: 132, height: 96, alignment: .topLeading)
        .background(type.category.tint.opacity(0.07), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text(verbatim: "\(type.displayName): \(model.valueText) \(model.unitText)"))
    }
}

struct HealthSparkline: View {
    let values: [Double]
    let tint: Color

    var body: some View {
        Chart(Array(values.enumerated()), id: \.offset) { item in
            LineMark(x: .value("Index", item.offset), y: .value("Value", item.element))
                .interpolationMethod(.catmullRom)
                .lineStyle(StrokeStyle(lineWidth: 1.5, lineCap: .round))
                .foregroundStyle(tint)
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .chartLegend(.hidden)
        .accessibilityHidden(true)
    }
}

struct HealthStateTile: View {
    let title: LocalizedStringKey
    let subtitle: LocalizedStringKey
    let systemImage: String
    let tint: Color
    let route: HealthRoute

    var body: some View {
        NavigationLink(value: route) {
            VStack(alignment: .leading, spacing: 6) {
                Image(systemName: systemImage)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(tint)
                Text(title)
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .lineLimit(2)
                Text(subtitle)
                    .font(.system(.caption2, design: .rounded))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            .padding(10)
            .frame(width: 200, height: 96, alignment: .topLeading)
            .background(tint.opacity(0.07), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}
