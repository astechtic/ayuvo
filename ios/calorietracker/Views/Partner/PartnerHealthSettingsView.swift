import SwiftUI
import UniformTypeIdentifiers

/// Settings › Data & Privacy › Partner Health: partners, Add partner (Show / Scan code), Import a partner file, this
/// device's fingerprint and an honest explainer of how syncing works.
struct PartnerHealthSettingsView: View {
    @State private var manager = PartnerManager.shared
    @State private var pairing: PartnerPairingView.Start?
    @State private var showImporter = false

    var body: some View {
        List {
            Section {
                PartnerExplainer()
            }
            .listRowBackground(AppColors.appCard)

            Section {
                if manager.partners.isEmpty {
                    Text("No partners yet. Add one with the other phone next to you.")
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                ForEach(manager.partners) { item in
                    NavigationLink(value: PartnerRoute.settings(item.id)) {
                        PartnerSettingsRow(item: item)
                    }
                    .accessibilityIdentifier("settings.partner.\(item.id)")
                }
            } header: {
                Text("Partners")
            }
            .listRowBackground(AppColors.appCard)

            Section {
                Button {
                    pairing = .show
                } label: {
                    SettingsLabel("Show my code", systemImage: "qrcode", tint: AyuvoPalette.partner)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("settings.partner.showCode")
                Button {
                    pairing = .scan
                } label: {
                    SettingsLabel("Scan partner's code", systemImage: "qrcode.viewfinder", tint: AyuvoPalette.partner)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("settings.partner.scanCode")
            } header: {
                Text("Add partner")
            } footer: {
                Text("One phone shows a code, the other scans it, then you both check the same 6 digits. Nothing is shared until you choose.")
            }
            .listRowBackground(AppColors.appCard)

            Section {
                Button {
                    showImporter = true
                } label: {
                    SettingsLabel("Import a partner file", systemImage: "square.and.arrow.down.fill", tint: SettingsTint.importData)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("settings.partner.import")
            } footer: {
                Text("When the phones can't reach each other, your partner can send an .ayuvo.zip file from their partner settings. Files are only accepted from paired partners.")
            }
            .listRowBackground(AppColors.appCard)

            Section {
                if let fingerprint = manager.deviceFingerprint {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(verbatim: fingerprint)
                            .font(.system(.footnote, design: .monospaced))
                            .textSelection(.enabled)
                        Text("Your partner sees this fingerprint for your phone.")
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                    .accessibilityIdentifier("settings.partner.fingerprint")
                } else {
                    Text("Created the first time you pair.")
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("This device")
            } footer: {
                Text("Pairing keys are stored on this device only. A new or restored phone has to pair again.")
            }
            .listRowBackground(AppColors.appCard)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle(Text("Partner Health"))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await manager.reload()
            #if DEBUG
            if UserDefaults.standard.string(forKey: "ayuvoPartnerScreen") == "showCode", pairing == nil { pairing = .show }
            #endif
        }
        .sheet(item: $pairing) { start in
            PartnerPairingView(start: start)
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.zip]) { result in
            guard case .success(let url) = result else { return }
            Task {
                if await !manager.handleIncomingFile(url) {
                    manager.reportNotPartnerFile()
                }
            }
        }
        .accessibilityIdentifier("settings.partnerHealth")
    }
}

extension PartnerPairingView.Start: Identifiable {
    var id: Self { self }
}

/// What Partner Health does and doesn't do, in plain words.
struct PartnerExplainer: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                CategoryIconView(systemImage: "person.2.fill", tint: AyuvoPalette.partner, style: .filled, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Share health with a partner")
                        .font(.system(.headline, design: .rounded))
                    Text("Phone to phone, no account")
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            VStack(alignment: .leading, spacing: 6) {
                bullet("wifi", "Phones sync directly over the same Wi-Fi or hotspot. There's no cloud and no Ayuvo server.")
                bullet("iphone.radiowaves.left.and.right", "Both phones need to be awake. iOS limits background time, so syncs mostly happen while Ayuvo is open.")
                bullet("hand.raised.fill", "You choose what each partner sees. Their data is read-only here and stored on this device, apart from yours.")
                bullet("doc.zipper", "Files you send are signed so Ayuvo can check who made them, but not encrypted: anyone with the file can read it.")
            }
        }
        .padding(.vertical, 6)
    }

    private func bullet(_ systemImage: String, _ text: LocalizedStringKey) -> some View {
        Label {
            Text(text).fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: systemImage).foregroundStyle(AyuvoPalette.partner)
        }
        .font(.system(.footnote, design: .rounded))
        .foregroundStyle(.secondary)
    }
}

/// A partner in the Settings list: name, short fingerprint, freshness and badges.
struct PartnerSettingsRow: View {
    let item: PartnerManager.PartnerItem

    var body: some View {
        HStack(spacing: 12) {
            PartnerAvatar(name: item.name, size: 36)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(verbatim: item.name)
                        .font(.system(.body, design: .rounded, weight: .semibold))
                        .lineLimit(1)
                    Text(verbatim: PartnerDisplay.shortFingerprint(item.peer.fingerprint))
                        .font(.system(.caption2, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Text(verbatim: PartnerDisplay.freshness(item))
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                if item.grantsReceived.contains(where: { $0.revokedMs != nil && !$0.granted }) {
                    PartnerNoLongerSharedBadge()
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// One partner: what I share (6 switches), Sync Now, send a file, what they share, Unpair / Delete data / Remove.
struct PartnerSettingsDetailView: View {
    let ownerID: String
    @Environment(\.dismiss) private var dismiss
    @State private var manager = PartnerManager.shared
    @State private var confirm: Confirm?
    @State private var exporting = false
    @State private var exportFailed = false

    private enum Confirm: Identifiable {
        case unpair, deleteData, remove
        var id: Self { self }
    }

    private var item: PartnerManager.PartnerItem? { manager.partners.first { $0.id == ownerID } }

    var body: some View {
        Group {
            if let item {
                list(item)
            } else {
                EmptyStateView("Partner removed", systemImage: "person.crop.circle.badge.xmark")
            }
        }
        .navigationTitle(item?.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .alert("Couldn't make the file", isPresented: $exportFailed) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Try again in a moment.")
        }
        .confirmationDialog(confirmTitle, isPresented: Binding(get: { confirm != nil }, set: { if !$0 { confirm = nil } }),
                            titleVisibility: .visible, presenting: confirm) { action in
            switch action {
            case .unpair:
                Button("Unpair", role: .destructive) { Task { await manager.unpair(ownerID) } }
            case .deleteData:
                Button("Delete Data", role: .destructive) { Task { await manager.deleteData(ownerID) } }
            case .remove:
                Button("Remove Partner", role: .destructive) {
                    Task {
                        await manager.remove(ownerID)
                        dismiss()
                    }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: { action in
            switch action {
            case .unpair: Text("Syncing stops and \(item?.name ?? "") can no longer send you data. What's already on this phone is kept.")
            case .deleteData: Text("Removes everything \(item?.name ?? "") shared from this phone. If you stay paired, the next sync sends what they still share.")
            case .remove: Text("Unpairs and deletes everything \(item?.name ?? "") shared from this phone.")
            }
        }
    }

    private var confirmTitle: String {
        switch confirm {
        case .unpair: String(localized: "Unpair \(item?.name ?? "")?")
        case .deleteData: String(localized: "Delete \(item?.name ?? "")'s data?")
        case .remove: String(localized: "Remove \(item?.name ?? "")?")
        case nil: ""
        }
    }

    private func list(_ item: PartnerManager.PartnerItem) -> some View {
        List {
            Section {
                HStack(spacing: 12) {
                    PartnerAvatar(name: item.name, size: 44)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: item.name).font(.system(.headline, design: .rounded))
                        Text(verbatim: PartnerRef.formatFingerprint(item.peer.fingerprint))
                            .font(.system(.caption2, design: .monospaced))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                        Text(verbatim: PartnerDisplay.freshness(item))
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }
                NavigationLink(value: PartnerRoute.dashboard(item.id)) {
                    SettingsLabel("View \(item.name)'s health", systemImage: "heart.text.square.fill", tint: AyuvoPalette.partner)
                }
                .accessibilityIdentifier("settings.partner.openDashboard")
            }
            .listRowBackground(AppColors.appCard)

            if item.peer.isTrusted {
                Section {
                    PartnerGrantToggles(granted: Set(item.grantsOut)) { category, on in
                        Task { await manager.setGrant(ownerID, category: category, granted: on) }
                    }
                } header: {
                    Text("What I share with \(item.name)")
                } footer: {
                    Text("Off by default. Turning a category off stops new changes; what \(item.name) already received stays on their phone.")
                }
                .listRowBackground(AppColors.appCard)

                Section {
                    Button {
                        Task { await manager.syncNow() }
                    } label: {
                        HStack {
                            SettingsLabel(manager.isSyncing ? "Syncing…" : "Sync Now", systemImage: "arrow.triangle.2.circlepath", tint: SettingsTint.update)
                            if manager.isSyncing { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(manager.isSyncing)
                    .accessibilityIdentifier("settings.partner.syncNow")
                    Text(verbatim: PartnerStatusText.status(item.status))
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                } footer: {
                    Text("Works when both phones are on the same Wi-Fi or hotspot and awake.")
                }
                .listRowBackground(AppColors.appCard)

                Section {
                    Button {
                        export(sinceLastSync: false)
                    } label: {
                        SettingsLabel("Send everything I share", systemImage: "square.and.arrow.up.fill", tint: SettingsTint.export)
                    }
                    .accessibilityIdentifier("settings.partner.exportAll")
                    Button {
                        export(sinceLastSync: true)
                    } label: {
                        SettingsLabel("Send changes since last sync", systemImage: "square.and.arrow.up.on.square.fill", tint: SettingsTint.export)
                    }
                    .accessibilityIdentifier("settings.partner.exportChanges")
                    .disabled((item.state?.ackedRev ?? 0) == 0)
                } header: {
                    Text("Send a file instead")
                } footer: {
                    Text("Makes an .ayuvo.zip for \(item.name) only, to send with AirDrop, Messages or Files. It's signed, not encrypted: anyone who gets the file can read it.")
                }
                .listRowBackground(AppColors.appCard)
                .disabled(exporting || item.grantsOut.isEmpty)
            }

            Section {
                if item.grantsReceived.isEmpty {
                    Text("\(item.name) hasn't shared anything with you yet.")
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                ForEach(item.grantsReceived, id: \.category) { grant in
                    HStack(spacing: 12) {
                        CategoryIconView(systemImage: PartnerCategoryInfo.systemImage(grant.category), tint: PartnerCategoryInfo.tint(grant.category))
                        Text(verbatim: PartnerCategoryInfo.title(grant.category))
                        Spacer()
                        if grant.granted {
                            Text("Shared").font(.system(.footnote, design: .rounded)).foregroundStyle(.secondary)
                        } else if grant.revokedMs != nil {
                            PartnerNoLongerSharedBadge()
                        } else {
                            Text("Not shared").font(.system(.footnote, design: .rounded)).foregroundStyle(.secondary)
                        }
                    }
                }
            } header: {
                Text("What \(item.name) shares with me")
            } footer: {
                Text("\(item.recordCount) records stored on this device, read-only and separate from your own data.")
            }
            .listRowBackground(AppColors.appCard)

            Section {
                if item.peer.isTrusted {
                    Button(role: .destructive) { confirm = .unpair } label: {
                        SettingsLabel("Unpair", systemImage: "minus.circle.fill", tint: SettingsTint.warning)
                    }
                    .accessibilityIdentifier("settings.partner.unpair")
                }
                Button(role: .destructive) { confirm = .deleteData } label: {
                    SettingsLabel("Delete \(item.name)'s data", systemImage: "trash.fill", tint: SettingsTint.destructive)
                }
                .disabled(item.recordCount == 0)
                .accessibilityIdentifier("settings.partner.deleteData")
                Button(role: .destructive) { confirm = .remove } label: {
                    SettingsLabel("Remove partner", systemImage: "person.crop.circle.badge.minus", tint: SettingsTint.destructive)
                }
                .accessibilityIdentifier("settings.partner.remove")
            }
            .listRowBackground(AppColors.appCard)
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
    }

    private func export(sinceLastSync: Bool) {
        exporting = true
        Task {
            if await manager.exportPackage(for: ownerID, sinceLastSync: sinceLastSync) == nil { exportFailed = true }
            exporting = false
        }
    }
}
