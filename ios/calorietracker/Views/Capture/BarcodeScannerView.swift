import SwiftUI
import Photos
import PhotosUI
import PDFKit
import UIKit
import HealthKit
import StoreKit
import WidgetKit
import AVFoundation
import Speech
import UniformTypeIdentifiers

// MARK: - Barcode Scanner

/// What the camera scanner looks for: product barcodes (food) or a QR code (Partner Health pairing).
enum CodeScannerMode {
    case barcode
    case qr
}

struct BarcodeScannerView: UIViewControllerRepresentable {
    var mode: CodeScannerMode = .barcode
    /// Embedded inside a screen that already has its own Cancel and instructions (Partner pairing):
    /// no Cancel button, no hint line, the scan frame and caption sized to fit the card.
    var embedded: Bool = false
    let onScan: (String) -> Void
    let onCancel: () -> Void

    func makeUIViewController(context: Context) -> BarcodeScannerViewController {
        BarcodeScannerViewController(mode: mode, embedded: embedded, onScan: onScan, onCancel: onCancel)
    }

    func updateUIViewController(_ uiViewController: BarcodeScannerViewController, context: Context) {}
}

final class BarcodeScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let mode: CodeScannerMode
    private let embedded: Bool
    private let onScan: (String) -> Void
    private let onCancel: () -> Void
    private var session: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var didScan = false

    init(mode: CodeScannerMode = .barcode, embedded: Bool = false, onScan: @escaping (String) -> Void,
         onCancel: @escaping () -> Void) {
        self.mode = mode
        self.embedded = embedded
        self.onScan = onScan
        self.onCancel = onCancel
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        buildOverlay()
        checkCameraAccess()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        let session = session
        DispatchQueue.global(qos: .userInitiated).async {
            session?.stopRunning()
        }
    }

    private func checkCameraAccess() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted {
                        self?.configureSession()
                    } else {
                        self?.showCameraUnavailable(self?.accessMessage ?? "")
                    }
                }
            }
        case .denied, .restricted:
            showCameraUnavailable(accessMessage)
        @unknown default:
            showCameraUnavailable(String(localized: "Camera is unavailable.", comment: "Barcode scanner camera error"))
        }
    }

    private var accessMessage: String {
        switch mode {
        case .barcode: String(localized: "Camera access is needed to scan barcodes.", comment: "Barcode scanner camera error")
        case .qr: String(localized: "Camera access is needed to scan your partner's code. Turn it on in Settings › Ayuvo › Camera.", comment: "Partner pairing QR scanner camera error")
        }
    }

    private func configureSession() {
        let session = AVCaptureSession()
        session.beginConfiguration()

        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: camera),
              session.canAddInput(input) else {
            session.commitConfiguration()
            showCameraUnavailable(String(localized: "Camera is unavailable.", comment: "Barcode scanner camera error"))
            return
        }
        session.addInput(input)
        configureCameraForBarcodeScanning(camera)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            session.commitConfiguration()
            showCameraUnavailable(String(localized: "Barcode scanning is unavailable.", comment: "Barcode scanner camera error"))
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)

        let supportedTypes: [AVMetadataObject.ObjectType] = mode == .qr ? [.qr] : [
            .ean13,
            .ean8,
            .upce,
            .code128,
            .code39,
            .code93,
            .itf14,
            .interleaved2of5
        ]
        let availableTypes = supportedTypes.filter { output.availableMetadataObjectTypes.contains($0) }
        guard !availableTypes.isEmpty else {
            session.commitConfiguration()
            showCameraUnavailable(String(localized: "Barcode scanning is unavailable.", comment: "Barcode scanner camera error"))
            return
        }
        output.metadataObjectTypes = availableTypes
        session.commitConfiguration()

        let previewLayer = AVCaptureVideoPreviewLayer(session: session)
        previewLayer.videoGravity = .resizeAspectFill
        previewLayer.frame = view.bounds
        view.layer.insertSublayer(previewLayer, at: 0)

        self.session = session
        self.previewLayer = previewLayer

        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }
    }

    /// Food photo camera uses UIKit/system capture with autofocus. This scanner
    /// previously left the device on its default focus, which often stays soft
    /// at barcode distance on multi-camera iPhones.
    private func configureCameraForBarcodeScanning(_ camera: AVCaptureDevice) {
        do {
            try camera.lockForConfiguration()
            defer { camera.unlockForConfiguration() }

            if camera.isFocusModeSupported(.continuousAutoFocus) {
                camera.focusMode = .continuousAutoFocus
            } else if camera.isFocusModeSupported(.autoFocus) {
                camera.focusMode = .autoFocus
            }

            if camera.isFocusPointOfInterestSupported {
                camera.focusPointOfInterest = CGPoint(x: 0.5, y: 0.5)
            }

            if camera.isAutoFocusRangeRestrictionSupported {
                camera.autoFocusRangeRestriction = .near
            }

            if camera.isSmoothAutoFocusSupported {
                camera.isSmoothAutoFocusEnabled = true
            }

            if camera.isExposureModeSupported(.continuousAutoExposure) {
                camera.exposureMode = .continuousAutoExposure
            }

            if camera.isExposurePointOfInterestSupported {
                camera.exposurePointOfInterest = CGPoint(x: 0.5, y: 0.5)
            }

            if camera.isLowLightBoostSupported {
                camera.automaticallyEnablesLowLightBoostWhenAvailable = true
            }
        } catch {
            // Keep scanning available even if focus tuning fails.
        }
    }

    private func buildOverlay() {
        if embedded {
            buildEmbeddedOverlay()
            return
        }
        let closeButton = UIButton(type: .system)
        closeButton.setTitle(String(localized: "Cancel", comment: "Barcode scanner close button"), for: .normal)
        closeButton.setTitleColor(.white, for: .normal)
        closeButton.titleLabel?.font = .systemFont(ofSize: 17, weight: .semibold)
        closeButton.translatesAutoresizingMaskIntoConstraints = false
        closeButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        view.addSubview(closeButton)

        let isQR = mode == .qr
        let scanBox = makeScanBox()
        view.addSubview(scanBox)

        let label = makeInstructionLabel()
        view.addSubview(label)

        let hint = UILabel()
        hint.text = isQR
            ? String(localized: "On their phone: Settings › Partner Health › Add partner › Show my code. The camera only reads the code; nothing is recorded.", comment: "Partner pairing QR scanner hint")
            : String(localized: "If the product is not found, scan the nutrition label instead.", comment: "Barcode scanner hint")
        hint.textColor = UIColor.white.withAlphaComponent(0.72)
        hint.font = .systemFont(ofSize: 14, weight: .medium)
        hint.textAlignment = .center
        hint.numberOfLines = 0
        hint.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hint)

        NSLayoutConstraint.activate([
            closeButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 14),
            closeButton.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),

            scanBox.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            scanBox.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: -34),
            scanBox.widthAnchor.constraint(equalTo: view.widthAnchor, multiplier: 0.76),
            isQR ? scanBox.heightAnchor.constraint(equalTo: scanBox.widthAnchor) : scanBox.heightAnchor.constraint(equalToConstant: 190),

            label.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 28),
            label.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -28),
            label.topAnchor.constraint(equalTo: scanBox.bottomAnchor, constant: 28),

            hint.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 36),
            hint.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -36),
            hint.topAnchor.constraint(equalTo: label.bottomAnchor, constant: 10)
        ])
    }

    /// Card-sized layout: a square frame centred above a caption on a dark scrim at the bottom edge,
    /// both always inside the card whatever its size.
    private func buildEmbeddedOverlay() {
        let scrim = UIView()
        scrim.backgroundColor = UIColor.black.withAlphaComponent(0.45)
        scrim.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scrim)

        let label = makeInstructionLabel()
        label.font = .systemFont(ofSize: 15, weight: .semibold)
        scrim.addSubview(label)

        let area = UILayoutGuide()
        view.addLayoutGuide(area)

        let scanBox = makeScanBox()
        view.addSubview(scanBox)

        let preferredSide = scanBox.widthAnchor.constraint(equalTo: area.widthAnchor, multiplier: 0.62)
        preferredSide.priority = .defaultHigh
        let preferredHeight = scanBox.heightAnchor.constraint(equalTo: area.heightAnchor, multiplier: 0.78)
        preferredHeight.priority = .defaultHigh

        NSLayoutConstraint.activate([
            scrim.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            scrim.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            scrim.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            label.leadingAnchor.constraint(equalTo: scrim.leadingAnchor, constant: 20),
            label.trailingAnchor.constraint(equalTo: scrim.trailingAnchor, constant: -20),
            label.topAnchor.constraint(equalTo: scrim.topAnchor, constant: 12),
            label.bottomAnchor.constraint(equalTo: scrim.bottomAnchor, constant: -14),

            area.topAnchor.constraint(equalTo: view.topAnchor),
            area.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            area.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            area.bottomAnchor.constraint(equalTo: scrim.topAnchor),

            scanBox.centerXAnchor.constraint(equalTo: area.centerXAnchor),
            scanBox.centerYAnchor.constraint(equalTo: area.centerYAnchor),
            scanBox.heightAnchor.constraint(equalTo: scanBox.widthAnchor),
            scanBox.widthAnchor.constraint(lessThanOrEqualTo: area.widthAnchor, multiplier: 0.62),
            scanBox.heightAnchor.constraint(lessThanOrEqualTo: area.heightAnchor, multiplier: 0.78),
            preferredSide,
            preferredHeight
        ])
    }

    private func makeScanBox() -> UIView {
        let scanBox = UIView()
        scanBox.layer.borderColor = UIColor.white.withAlphaComponent(0.9).cgColor
        scanBox.layer.borderWidth = 3
        scanBox.layer.cornerRadius = 22
        scanBox.backgroundColor = UIColor.clear
        scanBox.translatesAutoresizingMaskIntoConstraints = false
        return scanBox
    }

    private func makeInstructionLabel() -> UILabel {
        let label = UILabel()
        label.text = mode == .qr
            ? String(localized: "Point the camera at the code on your partner's phone", comment: "Partner pairing QR scanner instruction")
            : String(localized: "Point the camera at the barcode", comment: "Barcode scanner instruction")
        label.textColor = .white
        label.font = .systemFont(ofSize: 18, weight: .semibold)
        label.textAlignment = .center
        label.numberOfLines = 0
        label.translatesAutoresizingMaskIntoConstraints = false
        return label
    }

    private func showCameraUnavailable(_ message: String) {
        let label = UILabel()
        label.text = message
        label.textColor = .white
        label.font = .systemFont(ofSize: 18, weight: .semibold)
        label.textAlignment = .center
        label.numberOfLines = 0
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)

        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            label.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 32),
            label.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -32)
        ])
    }

    @objc private func cancelTapped() {
        onCancel()
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !didScan,
              let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let code = object.stringValue,
              !code.isEmpty else { return }

        didScan = true
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        let session = session
        DispatchQueue.global(qos: .userInitiated).async {
            session?.stopRunning()
        }
        onScan(code)
    }
}
