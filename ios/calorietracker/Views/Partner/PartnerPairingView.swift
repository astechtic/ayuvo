import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

/// Add partner (docs/partner-sync.md §4): Show code (QR + expiry) or Scan code, then both phones compare the same
/// 6-digit code, each user picks what to share (all off by default), and the initial sync runs.
struct PartnerPairingView: View {
    enum Start { case show, scan }

    let start: Start
    @Environment(\.dismiss) private var dismiss
    @State private var manager = PartnerManager.shared
    @State private var name = ""
    @State private var codeMatched = false
    @State private var grants: Set<String> = []
    @State private var scannerEpoch = 0
    @State private var started = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    content
                }
                .padding(20)
            }
            .ayuvoScreenBackground()
            .navigationTitle(Text("Add partner"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if !isFinished {
                        Button("Cancel") { close() }
                            .accessibilityIdentifier("partner.pair.cancel")
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    if isFinished {
                        Button("Done") { close() }
                            .accessibilityIdentifier("partner.pair.done")
                    }
                }
            }
        }
        .interactiveDismissDisabled(isBusy)
        .task {
            guard !started else { return }
            started = true
            name = manager.myName
            await manager.cancelPairing()
            if start == .show { await manager.showCode() }
        }
    }

    private var isFinished: Bool {
        switch manager.pairingState {
        case .paired, .declined: true
        default: false
        }
    }

    private var isBusy: Bool {
        switch manager.pairingState {
        case .waitingForPartner, .syncing: true
        default: false
        }
    }

    private func close() {
        Task {
            if !isFinished, !isBusy { await manager.cancelPairing() }
            dismiss()
        }
    }

    // MARK: Steps

    @ViewBuilder
    private var content: some View {
        switch manager.pairingState {
        case .idle:
            if start == .scan { scanStep } else { progress("Preparing your code…") }
        case .showingCode(let qr, let expiresMs):
            showCodeStep(qr: qr, expiresMs: expiresMs)
        case .connecting:
            progress("Connecting to your partner's phone…")
        case .confirmCode(let sas, let peerName, let fingerprint):
            if codeMatched {
                chooseSharingStep(peerName: peerName)
            } else {
                confirmCodeStep(sas: sas, peerName: peerName, fingerprint: fingerprint)
            }
        case .waitingForPartner:
            progress("Waiting for your partner to confirm…")
        case .syncing(let peer):
            progress("Paired with \(peer). Syncing what you both share…")
        case .paired(_, let peer):
            doneStep(peer: peer)
        case .declined:
            failure(PartnerStatusText.pairingError("declined"), retry: false)
        case .failed(let code):
            failure(PartnerStatusText.pairingError(code), retry: true)
        }
    }

    private func progress(_ text: LocalizedStringKey) -> some View {
        VStack(spacing: 14) {
            ProgressView().controlSize(.large)
            Text(text)
                .font(.system(.headline, design: .rounded))
                .multilineTextAlignment(.center)
            Text("Keep both phones awake, with Ayuvo open.")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 60)
    }

    private func showCodeStep(qr: String, expiresMs: Int64) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Scan this with your partner's phone")
                .font(.system(.title3, design: .rounded, weight: .bold))
            Text("On their phone: Settings › Partner Health › Add partner › Scan partner's code.")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            PartnerQRCodeView(text: qr)
                .frame(maxWidth: 260)
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("partner.pair.qr")
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let left = max(0, Int((Double(expiresMs) / 1000 - context.date.timeIntervalSince1970).rounded()))
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Waiting for your partner… Code expires in \(Duration.seconds(left).formatted(.time(pattern: .minuteSecond)))")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
                .frame(maxWidth: .infinity)
            }
            VStack(alignment: .leading, spacing: 6) {
                Text("Your name for partners")
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                HStack {
                    TextField("Name", text: $name)
                        .textInputAutocapitalization(.words)
                        .submitLabel(.done)
                        .onSubmit { Task { await updateName() } }
                        .accessibilityIdentifier("partner.pair.name")
                    if PartnerRef.cleanName(name) != manager.myName {
                        Button("Update code") { Task { await updateName() } }
                            .font(.system(.footnote, design: .rounded, weight: .semibold))
                    }
                }
                .padding(12)
                .background(AyuvoPalette.panel, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                Text("Shown on your partner's phone. The code carries no health data.")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            PartnerNetworkNote()
        }
    }

    private func updateName() async {
        manager.myName = name
        name = manager.myName
        await manager.showCode()
    }

    @ViewBuilder
    private var scanStep: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Scan your partner's code")
                .font(.system(.title3, design: .rounded, weight: .bold))
            Text("On their phone: Settings › Partner Health › Add partner › Show my code. Ayuvo asks for the camera only to read the code.")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            BarcodeScannerView(mode: .qr, embedded: true, onScan: { text in
                Task { await manager.scan(text) }
            }, onCancel: { close() })
            .id(scannerEpoch)
            .frame(maxWidth: .infinity)
            .aspectRatio(0.9, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: AyuvoPalette.cardRadius, style: .continuous))
            .accessibilityIdentifier("partner.pair.scanner")
            PartnerNetworkNote()
        }
    }

    private func confirmCodeStep(sas: String, peerName: String?, fingerprint: String?) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Do both phones show this code?")
                .font(.system(.title3, design: .rounded, weight: .bold))
            Text(verbatim: sas.count == 6 ? "\(sas.prefix(3)) \(sas.suffix(3))" : sas)
                .font(.system(size: 52, weight: .bold, design: .monospaced))
                .tracking(4)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 18)
                .ayuvoCard(padding: nil)
                .accessibilityLabel(Text(verbatim: sas.map(String.init).joined(separator: " ")))
                .accessibilityIdentifier("partner.pair.sas")
            if let peerName {
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: peerName).font(.system(.subheadline, design: .rounded, weight: .semibold))
                        if let fingerprint {
                            Text(verbatim: fingerprint).font(.system(.caption2, design: .monospaced)).foregroundStyle(.secondary)
                        }
                    }
                } icon: {
                    PartnerAvatar(name: peerName, size: 30)
                }
            }
            Text("Only continue if the 6 digits are exactly the same on your partner's screen. A different code means you're connected to the wrong phone.")
                .font(.system(.footnote, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Button {
                codeMatched = true
            } label: {
                Text("Codes match").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(AyuvoPalette.partner)
            .controlSize(.large)
            .accessibilityIdentifier("partner.pair.match")
            Button(role: .destructive) {
                Task { await manager.confirmPairing(accepted: false, grants: []) }
            } label: {
                Text("Doesn't match").frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .accessibilityIdentifier("partner.pair.mismatch")
        }
    }

    private func chooseSharingStep(peerName: String?) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Choose what to share")
                .font(.system(.title3, design: .rounded, weight: .bold))
            Text("Everything is off until you turn it on. You can change this any time, separately for each partner.")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            PartnerGrantToggles(granted: grants) { category, on in
                if on { grants.insert(category) } else { grants.remove(category) }
            }
            .ayuvoCard()
            Button {
                Task {
                    await manager.confirmPairing(accepted: true, grants: PartnerCatalog.shared.categories.filter(grants.contains), name: manager.myName)
                }
            } label: {
                Text(grants.isEmpty ? "Pair without sharing" : "Pair and share").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(AyuvoPalette.partner)
            .controlSize(.large)
            .accessibilityIdentifier("partner.pair.continue")
        }
    }

    private func doneStep(peer: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 48))
                .foregroundStyle(AyuvoPalette.nutrition)
            Text("You're paired with \(peer)")
                .font(.system(.title3, design: .rounded, weight: .bold))
                .multilineTextAlignment(.center)
            Text("What they share appears on your Summary. Phones sync when both are on the same network and awake.")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 40)
    }

    private func failure(_ message: String, retry: Bool) -> some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 40))
                .foregroundStyle(AyuvoPalette.activity)
            Text(verbatim: message)
                .font(.system(.body, design: .rounded))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("partner.pair.error")
            if retry {
                Button {
                    Task {
                        codeMatched = false
                        grants = []
                        await manager.cancelPairing()
                        scannerEpoch += 1
                        if start == .show { await manager.showCode() }
                    }
                } label: {
                    Text(start == .scan ? "Scan again" : "Show a new code").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(AyuvoPalette.partner)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 40)
    }
}

/// The six "What I share" switches (default off), used in pairing and in a partner's settings.
struct PartnerGrantToggles: View {
    let granted: Set<String>
    var disabled = false
    let onChange: (String, Bool) -> Void

    var body: some View {
        VStack(spacing: 14) {
            ForEach(PartnerCatalog.shared.categories, id: \.self) { category in
                Toggle(isOn: Binding(get: { granted.contains(category) }, set: { onChange(category, $0) })) {
                    HStack(spacing: 12) {
                        CategoryIconView(systemImage: PartnerCategoryInfo.systemImage(category), tint: PartnerCategoryInfo.tint(category))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(verbatim: PartnerCategoryInfo.title(category))
                                .font(.system(.body, design: .rounded))
                            Text(verbatim: PartnerCategoryInfo.subtitle(category))
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                .tint(AyuvoPalette.partner)
                .disabled(disabled)
                .accessibilityIdentifier("partner.grant.\(category)")
            }
        }
    }
}

/// Why Ayuvo asks for Local Network access, shown wherever pairing or syncing starts.
struct PartnerNetworkNote: View {
    var body: some View {
        Label {
            Text("Both phones need the same Wi-Fi or one phone's hotspot. iOS asks to allow Local Network access: Ayuvo uses it only to find and talk to your partner's phone.")
                .fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: "wifi")
                .foregroundStyle(AyuvoPalette.partner)
        }
        .font(.system(.footnote, design: .rounded))
        .foregroundStyle(.secondary)
    }
}

/// A QR code rendered with CoreImage (`CIQRCodeGenerator`, medium error correction), scaled without smoothing.
struct PartnerQRCodeView: View {
    let text: String

    var body: some View {
        Group {
            if let image = Self.image(for: text) {
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
            } else {
                Image(systemName: "qrcode").resizable().scaledToFit().foregroundStyle(.secondary)
            }
        }
        .padding(14)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityLabel(Text("Pairing code"))
    }

    static func image(for text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
