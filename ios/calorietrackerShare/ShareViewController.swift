import UIKit
import Social
import MobileCoreServices
import UniformTypeIdentifiers
import WebKit

/// Share sheet entry: "Save to Health Records" for any PDF, image, file or text, and
/// "Log as food" for exactly one image (the original `shared_import.jpg` food flow).
class ShareViewController: UIViewController {
    private let card = UIView()
    private let titleLabel = UILabel()
    private let detailLabel = UILabel()
    private let saveButton = UIButton(type: .system)
    private let foodButton = UIButton(type: .system)
    private let cancelButton = UIButton(type: .system)
    private let spinner = UIActivityIndicatorView(style: .medium)

    private var providers: [NSItemProvider] {
        (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
    }

    private var imageProviders: [NSItemProvider] {
        providers.filter { $0.hasItemConformingToTypeIdentifier(UTType.image.identifier) }
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.black.withAlphaComponent(0.25)
        guard !providers.isEmpty else {
            dismissWithError()
            return
        }
        buildInterface()
    }

    // MARK: - Interface

    private func buildInterface() {
        card.backgroundColor = .systemBackground
        card.layer.cornerRadius = 22
        card.layer.cornerCurve = .continuous
        card.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(card)

        titleLabel.text = "Ayuvo"
        titleLabel.font = .preferredFont(forTextStyle: .headline)
        titleLabel.textAlignment = .center

        let count = providers.count
        detailLabel.text = count == 1
            ? String(localized: "1 item")
            : String(localized: "\(count) items")
        detailLabel.font = .preferredFont(forTextStyle: .subheadline)
        detailLabel.textColor = .secondaryLabel
        detailLabel.textAlignment = .center
        detailLabel.numberOfLines = 0

        configure(saveButton, title: String(localized: "Save to Health Records"), systemImage: "list.clipboard.fill", filled: true)
        saveButton.addTarget(self, action: #selector(saveToRecords), for: .touchUpInside)
        saveButton.accessibilityIdentifier = "share.saveToRecords"

        configure(foodButton, title: String(localized: "Log as food"), systemImage: "fork.knife", filled: false)
        foodButton.addTarget(self, action: #selector(logAsFood), for: .touchUpInside)
        foodButton.accessibilityIdentifier = "share.logFood"
        // Food logging reads exactly one photo.
        foodButton.isHidden = !(providers.count == 1 && imageProviders.count == 1)

        cancelButton.setTitle(String(localized: "Cancel"), for: .normal)
        cancelButton.addTarget(self, action: #selector(cancel), for: .touchUpInside)

        spinner.hidesWhenStopped = true

        let stack = UIStackView(arrangedSubviews: [titleLabel, detailLabel, spinner, saveButton, foodButton, cancelButton])
        stack.axis = .vertical
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(stack)

        NSLayoutConstraint.activate([
            card.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            card.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            card.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -16),
            stack.topAnchor.constraint(equalTo: card.topAnchor, constant: 20),
            stack.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -20),
            stack.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -16),
            saveButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 50),
            foodButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 50),
        ])
    }

    private func configure(_ button: UIButton, title: String, systemImage: String, filled: Bool) {
        var configuration = filled ? UIButton.Configuration.filled() : UIButton.Configuration.tinted()
        configuration.title = title
        configuration.image = UIImage(systemName: systemImage)
        configuration.imagePadding = 8
        configuration.cornerStyle = .large
        configuration.baseBackgroundColor = UIColor(red: 1.0, green: 55.0 / 255.0, blue: 95.0 / 255.0, alpha: 1.0)
        configuration.baseForegroundColor = filled ? .white : UIColor(red: 1.0, green: 55.0 / 255.0, blue: 95.0 / 255.0, alpha: 1.0)
        button.configuration = configuration
    }

    private func setBusy(_ busy: Bool) {
        busy ? spinner.startAnimating() : spinner.stopAnimating()
        saveButton.isEnabled = !busy
        foodButton.isEnabled = !busy
        cancelButton.isEnabled = !busy
    }

    // MARK: - Health Records

    @objc private func saveToRecords() {
        setBusy(true)
        let providers = providers
        RecordsInboxWriter.save(providers: providers) { [weak self] saved in
            DispatchQueue.main.async {
                self?.didSaveToRecords(saved: saved, total: providers.count)
            }
        }
    }

    private func didSaveToRecords(saved: Int, total: Int) {
        setBusy(false)
        guard saved > 0 else {
            dismissWithError(message: String(localized: "These items couldn't be saved to Health Records."))
            return
        }
        titleLabel.text = String(localized: "Saved to Health Records")
        detailLabel.text = saved == total ? nil : String(localized: "\(saved) of \(total) items saved")
        saveButton.isHidden = true
        foodButton.isHidden = true
        cancelButton.isHidden = true
        openMainAppAndComplete(url: URL(string: "ayuvo://records-inbox")!, delay: 0.8)
    }

    // MARK: - Food (unchanged flow)

    @objc private func logAsFood() {
        setBusy(true)
        handleShare()
    }

    @objc private func cancel() {
        extensionContext?.cancelRequest(withError: NSError(domain: "ShareError", code: NSUserCancelledError, userInfo: nil))
    }

    private func handleShare() {
        // Find the first attachment that conforms to image
        let imageType = UTType.image.identifier
        guard let provider = providers.first(where: { $0.hasItemConformingToTypeIdentifier(imageType) }) else {
            dismissWithError()
            return
        }

        provider.loadItem(forTypeIdentifier: imageType, options: nil) { [weak self] (item, error) in
            guard let self = self else { return }

            var imageData: Data? = nil

            if let url = item as? URL {
                imageData = try? Data(contentsOf: url)
            } else if let image = item as? UIImage {
                imageData = image.jpegData(compressionQuality: 0.8)
            } else if let data = item as? Data {
                imageData = data
            }

            guard let data = imageData else {
                DispatchQueue.main.async {
                    self.dismissWithError()
                }
                return
            }

            // Save to shared App Group container using ShareImportManager
            let success = ShareImportManager.saveSharedImage(data)
            if success {
                DispatchQueue.main.async {
                    self.openMainAppAndComplete(url: URL(string: "ayuvo://import-share-image")!, delay: 0.5)
                }
            } else {
                DispatchQueue.main.async {
                    self.dismissWithError()
                }
            }
        }
    }

    private func openMainAppAndComplete(url: URL, delay: TimeInterval) {
        // In iOS 18, the old openURL: selector silently fails. We must use the modern 3-argument selector.
        // Furthermore, the UIApplication singleton is not in the responder chain of an extension.
        // We must fetch it dynamically via NSClassFromString to bypass the APPLICATION_EXTENSION_API_ONLY ban.

        if let applicationClass = NSClassFromString("UIApplication") as? NSObject.Type {
            let sharedAppSelector = sel_registerName("sharedApplication")
            if applicationClass.responds(to: sharedAppSelector) {
                if let sharedApp = applicationClass.perform(sharedAppSelector)?.takeUnretainedValue() {
                    let openURLSelector = sel_registerName("openURL:options:completionHandler:")
                    if sharedApp.responds(to: openURLSelector) {

                        // We have the shared UIApplication instance, and it responds to the modern openURL selector.
                        // Since it takes 3 arguments, we must use unsafeBitCast to call it.
                        typealias OpenURLMethod = @convention(c) (AnyObject, Selector, URL, NSDictionary, ((Bool) -> Void)?) -> Void
                        let method = sharedApp.method(for: openURLSelector)
                        let openURL = unsafeBitCast(method, to: OpenURLMethod.self)

                        let options: NSDictionary = [:]
                        openURL(sharedApp, openURLSelector, url, options, nil)
                    }
                }
            }
        }

        // Let the app launch before completing the extension request
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
            self.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
        }
    }

    private func dismissWithError(message: String = "Unable to process the shared image.") {
        let alert = UIAlertController(
            title: "Error",
            message: message,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "OK", style: .default) { [weak self] _ in
            self?.extensionContext?.cancelRequest(withError: NSError(domain: "ShareError", code: 1, userInfo: nil))
        })
        present(alert, animated: true)
    }
}
