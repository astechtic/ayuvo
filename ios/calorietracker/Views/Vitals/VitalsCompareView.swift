import SwiftUI

/// "Compare finger & face" (docs/camera-vitals.md §6, §7.1): a finger scan, then a face scan with the same
/// `session_id`, then the compare screen. Runs sequentially because the rear and front cameras can't capture at once.
struct CompareFlowView: View {
    /// Called when the flow closes; `true` when at least one scan was saved.
    var onFinished: ((Bool) -> Void)? = nil

    enum Stage: Hashable {
        case finger
        case face
        case compare(String)
    }

    @Environment(\.dismiss) private var dismiss
    @State private var sessionID = UUID().uuidString.lowercased()
    @State private var stage: Stage = .finger
    @State private var fingerSavedAt = Date()
    @State private var savedAny = false

    var body: some View {
        Group {
            switch stage {
            case .finger:
                ScanFlowView(mode: .finger, sessionID: sessionID, compareStep: .finger,
                             onFinished: { record in onFinished?(savedAny || record != nil) },
                             onNext: { _ in
                                 savedAny = true
                                 fingerSavedAt = Date()
                                 stage = .face
                             })
            case .face:
                ScanFlowView(mode: .face, sessionID: sessionID, compareStep: .face(fingerSavedAt: fingerSavedAt),
                             onFinished: { _ in onFinished?(true) },
                             onNext: { record in stage = .compare(record.sessionID ?? sessionID) })
            case .compare(let id):
                NavigationStack {
                    VitalsCompareView(sessionID: id)
                        .toolbar {
                            ToolbarItem(placement: .confirmationAction) {
                                Button("Done") {
                                    dismiss()
                                    onFinished?(true)
                                }
                                .accessibilityIdentifier("vitals.compare.done")
                            }
                        }
                }
            }
        }
        .id(stage)
    }
}

/// Both results of a compare session side by side with their differences and the `compare` status. Never prefers
/// one result: finger PPG and face rPPG are different kinds of measurement.
struct VitalsCompareView: View {
    let sessionID: String

    @State private var pair: (finger: VitalScanRecord, face: VitalScanRecord)?
    @State private var loaded = false

    private var store: VitalsStore { VitalsStore.shared }

    var body: some View {
        List {
            if let pair {
                let result = VitalsCompare.result(finger: pair.finger, face: pair.face)
                let status = result["status"].string
                Section {
                    Label {
                        Text(VitalsCompare.statusText(status))
                            .font(.system(.headline, design: .rounded))
                    } icon: {
                        Image(systemName: status == "consistent" ? "checkmark.circle.fill"
                              : status == "inconsistent" ? "arrow.triangle.2.circlepath" : "minus.circle")
                            .foregroundStyle(status == "consistent" ? AyuvoPalette.nutrition : AyuvoPalette.activity)
                    }
                    .accessibilityIdentifier("vitals.compare.status")
                } footer: {
                    Text("Agreement means heart rate within \(Int(VitalsConfig.shared.compare.maxHrDiffBpm)) bpm and RMSSD within \(Int(VitalsConfig.shared.compare.maxRmssdDiffMs)) ms.")
                }

                Section {
                    Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 10) {
                        GridRow {
                            Text("")
                            header("Finger", VitalsMode.finger)
                            header("Face", VitalsMode.face)
                            Text("Difference").font(.system(.caption, design: .rounded, weight: .semibold)).foregroundStyle(.secondary)
                        }
                        Divider().gridCellUnsizedAxes(.horizontal)
                        row("heart_rate", unit: String(localized: "bpm"), diff: result["hr_diff"].double)
                        row("hrv_rmssd", unit: "ms", diff: result["rmssd_diff"].double)
                        row("ibi_mean", unit: "ms", diff: result["ibi_mean_diff"].double)
                        Divider().gridCellUnsizedAxes(.horizontal)
                        GridRow {
                            Text("Signal quality").font(.system(.subheadline, design: .rounded))
                            qualityText(pair.finger)
                            qualityText(pair.face)
                            Text("")
                        }
                    }
                    .padding(.vertical, 4)
                    .accessibilityIdentifier("vitals.compare.table")
                } header: {
                    Text("Results")
                } footer: {
                    Text("Finger and face scans are different kinds of measurement. Both results are kept as they are; neither is preferred.")
                }

                Section {
                    NavigationLink {
                        ScanDetailView(scanID: pair.finger.id)
                    } label: {
                        VitalsScanRow(record: pair.finger, showsDate: true)
                    }
                    NavigationLink {
                        ScanDetailView(scanID: pair.face.id)
                    } label: {
                        VitalsScanRow(record: pair.face, showsDate: true)
                    }
                } header: {
                    Text("Scans")
                } footer: {
                    Text(VitalsText.disclaimer)
                }
            } else if loaded {
                ContentUnavailableView("Nothing to compare", systemImage: "rectangle.on.rectangle.slash",
                                       description: Text("This comparison needs a saved finger scan and a saved face scan."))
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Compare")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("vitals.compare")
        .task(id: store.revision) {
            pair = VitalsCompare.pair(await store.scans(sessionID: sessionID))
            loaded = true
        }
    }

    private func header(_ title: LocalizedStringKey, _ mode: VitalsMode) -> some View {
        Label(title, systemImage: mode.systemImage)
            .font(.system(.caption, design: .rounded, weight: .semibold))
            .foregroundStyle(.secondary)
            .labelStyle(.titleAndIcon)
    }

    private func row(_ metric: String, unit: String, diff: Double?) -> some View {
        GridRow {
            Text(VitalsText.metricTitle(metric))
                .font(.system(.subheadline, design: .rounded))
                .lineLimit(2)
                .minimumScaleFactor(0.8)
            valueText(pair.map { VitalScanValues($0.finger).valid(metric) } ?? nil, unit: unit)
            valueText(pair.map { VitalScanValues($0.face).valid(metric) } ?? nil, unit: unit)
            Text(diff.map { $0.formatted(.number.precision(.fractionLength(0...1))) + " " + unit } ?? "—")
                .font(.ayuvoNumber(.subheadline))
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }

    private func valueText(_ value: Double?, unit: String) -> some View {
        Text(value.map { $0.formatted(.number.precision(.fractionLength(0...1))) } ?? String(localized: "Unavailable"))
            .font(value == nil ? .system(.caption, design: .rounded) : .ayuvoNumber(.subheadline))
            .foregroundStyle(value == nil ? .secondary : .primary)
    }

    private func qualityText(_ record: VitalScanRecord) -> some View {
        let grade = (try? VitalsJSON.parse(record.qualityJSON))?["grade"].string
        return Text(record.rejectReason == nil ? vitalsQualityText(score: record.qualityScore, grade: grade) : String(localized: "Not usable"))
            .font(.system(.caption, design: .rounded))
            .foregroundStyle(vitalsGradeColor(grade))
    }
}
