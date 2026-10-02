import AVFoundation
import SwiftUI
import UIKit

/// One scan flow for both modes (docs/camera-vitals.md §7.1 Scan flow): intro with instructions and context →
/// camera permission → live → analysing → results with Save / Discard. `sessionID` links a compare pair and
/// `onFinished` reports the saved record (nil when discarded or cancelled), so later flows can chain scans.
struct ScanFlowView: View {
    /// The step of a "Compare finger & face" flow (docs/camera-vitals.md §6).
    enum CompareStep: Equatable {
        case finger
        /// The face scan joins the finger scan's session only if it starts within `compare.max_session_gap_s`.
        case face(fingerSavedAt: Date)
    }

    let mode: VitalsMode
    var sessionID: String? = nil
    var compareStep: CompareStep? = nil
    var onFinished: ((VitalScanRecord?) -> Void)? = nil
    /// Compare flow: "Next: face scan" / "Compare" after the scan is saved.
    var onNext: ((VitalScanRecord) -> Void)? = nil

    @Environment(\.dismiss) private var dismiss
    @State private var session: ScanSession?
    @State private var context = "resting"
    @State private var indicators: RJ = .null
    @State private var isSaving = false
    @State private var saveFailed = false
    @State private var showCancelConfirm = false
    @State private var saved: VitalScanRecord?
    @State private var showReference = false
    /// The face scan started too long after the finger scan was saved, so the two are not linked.
    @State private var unlinked = false

    private var store: VitalsStore { VitalsStore.shared }

    var body: some View {
        NavigationStack {
            content
                .navigationTitle(mode == .finger ? Text("Finger scan") : Text("Face scan"))
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    if session?.phase != .finished {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cancel") { close(nil) }
                                .accessibilityIdentifier("vitals.cancel")
                        }
                    }
                }
        }
        .interactiveDismissDisabled(isLive)
        .onDisappear { session?.cancel() }
        .onChange(of: session?.phase) { _, phase in
            if phase == .finished { Task { await prepareResults() } }
        }
    }

    private var isLive: Bool {
        switch session?.phase {
        case .starting, .live, .analysing: true
        default: false
        }
    }

    @ViewBuilder
    private var content: some View {
        if let session {
            switch session.phase {
            case .ready, .starting, .live:
                ScanLiveView(session: session)
            case .analysing:
                ScanAnalysingView()
            case .finished:
                if let result = session.result {
                    ScanResultView(report: VitalScanReport(result: result, mode: mode, indicators: indicators),
                                   isSaving: isSaving, isSaved: saved != nil, nextTitle: nextTitle, notice: notice,
                                   onSave: { Task { await save(session) } }, onDiscard: { close(nil) },
                                   onReference: { showReference = true },
                                   onNext: {
                                       if let saved, nextTitle != nil, let onNext { onNext(saved) } else { close(saved) }
                                   })
                        .alert("Couldn't save the scan", isPresented: $saveFailed) {
                            Button("OK", role: .cancel) {}
                        }
                        .sheet(isPresented: $showReference) {
                            if let saved {
                                VitalsReferenceSheet(record: saved) { updated in
                                    if let updated { self.saved = updated }
                                }
                            }
                        }
                }
            case .cancelled:
                Color.clear
            case .failed(let error):
                ScanUnavailableView(permissionDenied: error == .permissionDenied)
            }
        } else {
            ScanIntroView(mode: mode, context: $context) { Task { await begin() } }
        }
    }

    /// "Next: face scan" after the compare flow's finger scan, "Compare" after its linked face scan; nil = Done.
    private var nextTitle: LocalizedStringKey? {
        guard onNext != nil else { return nil }
        switch compareStep {
        case .finger: return "Next: face scan"
        case .face: return unlinked ? nil : "Compare"
        case nil: return nil
        }
    }

    private var notice: String? {
        guard unlinked else { return nil }
        let minutes = Int((VitalsConfig.shared.compare.maxSessionGapS / 60).rounded())
        return String(localized: "More than \(minutes) minutes passed after the finger scan, so this face scan is saved on its own and is not compared. Start a new comparison to compare them.")
    }

    private func begin() async {
        let replay = VitalsSettings.replayRequested() || Self.isSimulator
        let source: VitalsFrameSource = replay ? ReplayFrameSource(mode: mode) : CameraFrameSource(mode: mode)
        var linkedSession = sessionID
        if case .face(let fingerSavedAt) = compareStep, let sessionID {
            linkedSession = VitalsCompare.linkedSessionID(sessionID, fingerSavedAt: fingerSavedAt, faceStart: Date())
            unlinked = linkedSession == nil
        }
        let session = ScanSession(mode: mode, source: source, sessionID: linkedSession)
        session.context = context
        session.options = await store.analysisOptions()
        self.session = session
        await session.start()
        if let capability = source.capabilityJSON(), session.phase == .live {
            await store.upsertDeviceProfile(position: mode == .finger ? "back" : "front", capability: capability)
        }
    }

    private func prepareResults() async {
        guard let session, let result = session.result else { return }
        if !store.hasLoaded { await store.reload() }
        let startMs = Int64(((session.startedAt ?? Date()).timeIntervalSince1970 * 1000).rounded())
        indicators = VitalScanRecordBuilder.indicators(result, mode: mode, startMs: startMs, history: store.scans, session.config)
    }

    private func save(_ session: ScanSession) async {
        isSaving = true
        defer { isSaving = false }
        do {
            // Saved: the results stay up for "Add reference reading" and Done / the next compare step.
            saved = try await store.save(session: session)
        } catch {
            saveFailed = true
        }
    }

    private func close(_ record: VitalScanRecord?) {
        session?.cancel()
        dismiss()
        onFinished?(record)
    }

    static var isSimulator: Bool {
        #if targetEnvironment(simulator)
        true
        #else
        false
        #endif
    }
}

// MARK: - Intro

struct ScanIntroView: View {
    let mode: VitalsMode
    @Binding var context: String
    let onStart: () -> Void

    private var steps: [(icon: String, text: LocalizedStringKey)] {
        switch mode {
        case .finger:
            [("flashlight.on.fill", "Cover the rear camera and the flash completely with a fingertip."),
             ("hand.point.up.left.fill", "Press lightly. Pressing hard blocks the blood flow."),
             ("hand.raised.fill", "Rest your hand on a table and keep it still."),
             ("timer", "Stay still for about a minute. The scan extends if the signal needs more time.")]
        case .face:
            [("person.crop.circle", "Sit still and face the front camera."),
             ("sun.max.fill", "Use even lighting and avoid direct sunlight on your face."),
             ("mouth", "Avoid talking during the scan."),
             ("oval.portrait", "Keep your whole face inside the oval."),
             ("eyeglasses", "Where possible, remove anything that covers your forehead or cheeks.")]
        }
    }

    var body: some View {
        List {
            Section {
                VStack(spacing: 10) {
                    Image(systemName: mode.systemImage)
                        .font(.system(size: 44, weight: .semibold))
                        .foregroundStyle(AyuvoPalette.heart)
                    Text(mode == .finger ? "Measure your pulse with your fingertip" : "Measure your pulse from your face")
                        .font(.system(.title3, design: .rounded, weight: .semibold))
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
            }
            Section("How to measure") {
                ForEach(Array(steps.enumerated()), id: \.offset) { _, step in
                    Label(step.text, systemImage: step.icon)
                        .font(.system(.subheadline, design: .rounded))
                }
            }
            Section {
                Picker("Context", selection: $context) {
                    Text("Resting").tag("resting")
                    Text("After activity").tag("after_activity")
                    Text("Other").tag("other")
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("vitals.context")
            } header: {
                Text("When are you measuring?")
            } footer: {
                Text("Camera frames are processed on this device and are never saved. \(VitalsText.disclaimer)")
            }
        }
        .listStyle(.insetGrouped)
        .safeAreaInset(edge: .bottom) {
            Button(action: onStart) {
                Text("Start scan")
                    .font(.system(.headline, design: .rounded))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .tint(AyuvoPalette.heart)
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
            .accessibilityIdentifier("vitals.start")
        }
    }
}

// MARK: - Live

struct ScanLiveView: View {
    let session: ScanSession

    var body: some View {
        VStack(spacing: 18) {
            if session.mode == .face {
                ZStack {
                    if let preview = session.source?.previewSession {
                        CameraPreviewView(session: preview)
                    } else {
                        LinearGradient(colors: [Color(white: 0.18), Color(white: 0.08)], startPoint: .top, endPoint: .bottom)
                            .overlay(alignment: .bottom) {
                                Text("Replay: synthetic frames, no camera")
                                    .font(.system(.caption2, design: .rounded))
                                    .foregroundStyle(.white.opacity(0.6))
                                    .padding(8)
                            }
                    }
                    FaceOvalGuide(ok: session.guidanceKey == "ok")
                }
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                .padding(.horizontal, 16)
                .frame(maxHeight: 380)
            } else {
                Image(systemName: session.guidanceKey == "ok" ? "heart.fill" : "hand.point.up.left.fill")
                    .font(.system(size: 54, weight: .semibold))
                    .foregroundStyle(AyuvoPalette.heart)
                    .symbolEffect(.pulse, isActive: session.guidanceKey == "ok")
                    .padding(.top, 24)
            }

            Text(VitalsText.guidance(session.guidanceKey))
                .font(.system(.title3, design: .rounded, weight: .semibold))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
                .accessibilityIdentifier("vitals.guidance")

            VitalsLiveWaveform(values: session.waveform)
                .frame(height: 90)
                .padding(.horizontal, 16)
                .opacity(session.waveform.isEmpty ? 0.2 : 1)

            clock

            HStack(spacing: 8) {
                qualityChip
                if session.isPaused {
                    chip(String(localized: "Paused"), systemImage: "pause.fill", tint: AyuvoPalette.activity)
                } else if session.isExtending {
                    chip(String(localized: "Extending for a better signal"), systemImage: "plus.circle", tint: AyuvoPalette.hydration)
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity)
        .background(AyuvoPalette.screenBackground)
    }

    private var clock: some View {
        let target = session.isExtending ? session.maxS : session.targetS
        return VStack(spacing: 6) {
            Text(session.clockStarted ? Self.time(session.clockS) : String(localized: "Waiting for a steady signal"))
                .font(session.clockStarted ? .ayuvoNumber(.largeTitle) : .system(.headline, design: .rounded))
                .monospacedDigit()
                .accessibilityIdentifier("vitals.clock")
            ProgressView(value: min(session.clockS, target), total: target)
                .tint(AyuvoPalette.heart)
                .padding(.horizontal, 40)
            Text("of \(Self.time(target))")
                .font(.system(.caption, design: .rounded))
                .foregroundStyle(.secondary)
        }
    }

    private var qualityChip: some View {
        let share = session.signalShare
        let tint = share >= 0.9 ? AyuvoPalette.nutrition : (share >= 0.6 ? AyuvoPalette.activity : AyuvoPalette.heart)
        return chip(String(localized: "Signal \(Int((share * 100).rounded()))%"), systemImage: "waveform", tint: tint)
            .accessibilityIdentifier("vitals.signalChip")
    }

    private func chip(_ text: String, systemImage: String, tint: Color) -> some View {
        Label(text, systemImage: systemImage)
            .font(.system(.caption, design: .rounded, weight: .semibold))
            .foregroundStyle(tint)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(tint.opacity(0.14), in: Capsule())
    }

    static func time(_ s: Double) -> String {
        let total = Int(s.rounded(.down))
        return String(format: "%d:%02d", total / 60, total % 60)
    }
}

/// The oval the face should fill; green while the gates pass.
struct FaceOvalGuide: View {
    let ok: Bool

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width * 0.62, h = min(geo.size.height * 0.8, w * 1.35)
            ZStack {
                Rectangle()
                    .fill(.black.opacity(0.35))
                    .mask {
                        Rectangle()
                            .overlay(Ellipse().frame(width: w, height: h).blendMode(.destinationOut))
                            .compositingGroup()
                    }
                Ellipse()
                    .stroke(ok ? AyuvoPalette.nutrition : .white, style: StrokeStyle(lineWidth: 3, dash: ok ? [] : [8, 6]))
                    .frame(width: w, height: h)
            }
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Front-camera preview (frames are shown, never kept).
struct CameraPreviewView: UIViewRepresentable {
    let session: AVCaptureSession

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}
}

struct ScanAnalysingView: View {
    var body: some View {
        VStack(spacing: 16) {
            ProgressView()
                .controlSize(.large)
            Text("Analysing your pulse signal…")
                .font(.system(.headline, design: .rounded))
                .accessibilityIdentifier("vitals.analysing")
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(AyuvoPalette.screenBackground)
    }
}

struct ScanUnavailableView: View {
    let permissionDenied: Bool

    var body: some View {
        ContentUnavailableView {
            Label(permissionDenied ? "Camera access is off" : "Camera unavailable", systemImage: "camera.fill")
        } description: {
            Text(permissionDenied
                 ? "Ayuvo needs the camera to measure your pulse. Frames are processed on this device and are never saved."
                 : "This device's camera can't be used for a scan right now.")
        } actions: {
            if permissionDenied {
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("vitals.openSettings")
            }
        }
    }
}

// MARK: - Results

struct ScanResultView: View {
    let report: VitalScanReport
    let isSaving: Bool
    var isSaved = false
    /// "Next: face scan" / "Compare" in the compare flow; Done otherwise.
    var nextTitle: LocalizedStringKey? = nil
    var notice: String? = nil
    let onSave: () -> Void
    let onDiscard: () -> Void
    var onReference: () -> Void = {}
    var onNext: () -> Void = {}

    var body: some View {
        List {
            if let notice {
                Section {
                    Label(notice, systemImage: "link.badge.plus")
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(AyuvoPalette.activity)
                        .accessibilityIdentifier("vitals.unlinkedNotice")
                }
            }
            VitalsReportSections(report: report)
        }
        .listStyle(.insetGrouped)
        .accessibilityIdentifier("vitals.results")
        .safeAreaInset(edge: .bottom) {
            if isSaved { savedBar } else { saveBar }
        }
    }

    private var savedBar: some View {
        VStack(spacing: 8) {
            Label("Saved", systemImage: "checkmark.circle.fill")
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                .foregroundStyle(AyuvoPalette.nutrition)
                .accessibilityIdentifier("vitals.saved")
            HStack(spacing: 12) {
                Button(action: onReference) {
                    Text("Add reference reading")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("vitals.reference")
                Button(action: onNext) {
                    Text(nextTitle ?? "Done")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .tint(AyuvoPalette.heart)
                .accessibilityIdentifier(nextTitle == nil ? "vitals.done" : "vitals.next")
            }
        }
        .font(.system(.headline, design: .rounded))
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(.bar)
    }

    private var saveBar: some View {
            HStack(spacing: 12) {
                Button(role: .destructive, action: onDiscard) {
                    Text("Discard")
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("vitals.discard")
                Button(action: onSave) {
                    Group {
                        if isSaving { ProgressView() } else { Text("Save") }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .tint(AyuvoPalette.heart)
                .disabled(isSaving)
                .accessibilityIdentifier("vitals.save")
            }
            .font(.system(.headline, design: .rounded))
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(.bar)
    }
}
