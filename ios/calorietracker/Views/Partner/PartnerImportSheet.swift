import SwiftUI

/// App-wide confirmation for an incoming `.ayuvo.zip` (Open in, share extension, file importer, launch hook):
/// "Health data from <name>" with the total and per-category counts, Cancel / Import, then the result.
struct PartnerImportSheet: View {
    @State private var manager = PartnerManager.shared

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    switch manager.importSheetPhase {
                    case .preview:
                        if let preview = manager.pendingImport { previewContent(preview) }
                    case .importing:
                        VStack(spacing: 12) {
                            ProgressView()
                            Text("Importing…").foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 60)
                    case .result, nil:
                        resultContent
                    }
                }
                .padding(20)
            }
            .ayuvoScreenBackground()
            .navigationTitle(Text("Partner health data"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if manager.importSheetPhase == .preview {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { manager.dismissImportSheet() }
                            .accessibilityIdentifier("partner.import.cancel")
                    }
                } else if manager.importSheetPhase == .result {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Done") { manager.dismissImportSheet() }
                            .accessibilityIdentifier("partner.import.done")
                    }
                }
            }
        }
        .interactiveDismissDisabled(manager.importSheetPhase == .importing)
        .accessibilityIdentifier("partner.importSheet")
    }

    private func previewContent(_ preview: PartnerPackagePreview) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    PartnerAvatar(name: preview.senderName, size: 44)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Health data from \(preview.senderName)")
                            .font(.system(.headline, design: .rounded))
                        Text(verbatim: String(localized: "partner.import.records", defaultValue: "\(preview.total) records", comment: "Number of records in a partner package"))
                            .font(.system(.subheadline, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }
                if preview.createdMs > 0 {
                    Text("Created \(Date(timeIntervalSince1970: Double(preview.createdMs) / 1000).formatted(date: .abbreviated, time: .shortened))")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            .ayuvoCard()

            VStack(spacing: 12) {
                ForEach(preview.perCategory, id: \.category) { entry in
                    HStack(spacing: 12) {
                        CategoryIconView(systemImage: PartnerCategoryInfo.systemImage(entry.category), tint: PartnerCategoryInfo.tint(entry.category))
                        Text(verbatim: PartnerCategoryInfo.title(entry.category))
                            .font(.system(.body, design: .rounded))
                        Spacer()
                        Text(verbatim: entry.count.formatted())
                            .font(.ayuvoNumber(.subheadline))
                            .foregroundStyle(.secondary)
                    }
                }
                if preview.perCategory.isEmpty {
                    Text("This file has no records for the categories shared with you.")
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            .ayuvoCard()

            Text("It's saved on this phone as \(preview.senderName)'s data, separate from yours and read-only. It is never added to Apple Health or your backups.")
                .font(.system(.footnote, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)

            Button {
                Task { await manager.confirmImport() }
            } label: {
                Text("Import").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(AyuvoPalette.partner)
            .controlSize(.large)
            .accessibilityIdentifier("partner.import.confirm")
        }
    }

    @ViewBuilder
    private var resultContent: some View {
        if let error = manager.importError {
            VStack(spacing: 14) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 40))
                    .foregroundStyle(AyuvoPalette.activity)
                Text("Couldn't import this file")
                    .font(.system(.headline, design: .rounded))
                Text(verbatim: PartnerStatusText.importError(error))
                    .font(.system(.subheadline, design: .rounded))
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 30)
            .accessibilityIdentifier("partner.import.error")
        } else if let outcome = manager.lastImport {
            VStack(spacing: 14) {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 40))
                    .foregroundStyle(AyuvoPalette.nutrition)
                if outcome.alreadyUpToDate {
                    Text(verbatim: PartnerStatusText.upToDate())
                        .font(.system(.headline, design: .rounded))
                    Text("Nothing in this file was new.")
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                } else {
                    Text("Imported")
                        .font(.system(.headline, design: .rounded))
                    VStack(spacing: 4) {
                        countLine("New", outcome.counts["inserted"] ?? 0)
                        countLine("Updated", outcome.counts["updated"] ?? 0)
                        countLine("Removed", outcome.counts["deleted"] ?? 0)
                        if outcome.rejected > 0 { countLine("Skipped", outcome.rejected) }
                    }
                }
                if let name = manager.importSenderName {
                    Text("Open \(name) from the Summary to see it.")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 30)
            .accessibilityIdentifier("partner.import.result")
        }
    }

    private func countLine(_ label: LocalizedStringKey, _ count: Int) -> some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            Text(verbatim: count.formatted()).font(.ayuvoNumber(.body))
        }
        .font(.system(.subheadline, design: .rounded))
        .frame(maxWidth: 220)
    }
}
