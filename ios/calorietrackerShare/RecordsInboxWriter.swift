import Foundation
import UIKit
import UniformTypeIdentifiers

/// Copies shared items into the app-group `RecordsInbox/<uuid>/` (docs/health-records.md §7):
/// `payload.<ext>` + `item.json` (`format`, `version`, `original_filename`, `uti`,
/// `received_at_ms`, `text`). Each item is written into `.tmp-<uuid>/` and the folder is
/// renamed when complete, so the app never drains half an item.
/// The app imports the inbox on launch, on scene-active and on `ayuvo://records-inbox`.
nonisolated enum RecordsInboxWriter {
    static let inboxDirectoryName = "RecordsInbox"

    private struct Item: Encodable {
        var format = "ayuvo-records-inbox"
        var version = 1
        var original_filename: String?
        var uti: String?
        var received_at_ms: Int64
        var text: String?
    }

    static var appGroupID: String {
        Bundle.main.object(forInfoDictionaryKey: "AppGroupIdentifier") as? String ?? "group.com.ayuvo.health"
    }

    static func inboxRoot() -> URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupID)?
            .appendingPathComponent(inboxDirectoryName, isDirectory: true)
    }

    /// Saves every attachment. Calls `completion` (on an arbitrary queue) with the number saved.
    static func save(providers: [NSItemProvider], completion: @escaping @Sendable (Int) -> Void) {
        guard let root = inboxRoot() else {
            completion(0)
            return
        }
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let group = DispatchGroup()
        let counter = Counter()
        for provider in providers {
            group.enter()
            saveOne(provider, root: root) { saved in
                if saved { counter.increment() }
                group.leave()
            }
        }
        group.notify(queue: .global(qos: .userInitiated)) {
            completion(counter.value)
        }
    }

    /// NSItemProvider is thread-safe for loading but not annotated Sendable.
    private struct ProviderBox: @unchecked Sendable {
        let provider: NSItemProvider
    }

    private final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        func increment() { lock.lock(); count += 1; lock.unlock() }
        var value: Int { lock.lock(); defer { lock.unlock() }; return count }
    }

    private static func saveOne(_ provider: NSItemProvider, root: URL, done: @escaping @Sendable (Bool) -> Void) {
        let identifiers = provider.registeredTypeIdentifiers
        let conforming: (UTType) -> String? = { type in
            identifiers.first { UTType($0)?.conforms(to: type) == true }
        }
        // Originals first (PDF, image, any other file), then plain text and links.
        if let pdf = conforming(.pdf) {
            copyFile(provider, typeIdentifier: pdf, root: root, done: done)
        } else if let image = conforming(.image) {
            let box = ProviderBox(provider: provider)
            copyFile(provider, typeIdentifier: image, root: root) { saved in
                if saved { done(true) } else { saveImageObject(box.provider, root: root, done: done) }
            }
        } else if conforming(.fileURL) != nil {
            _ = provider.loadObject(ofClass: URL.self) { url, _ in
                guard let url, url.isFileURL else { return done(false) }
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                done(write(root: root, payload: url, filename: url.lastPathComponent, uti: UTType(filenameExtension: url.pathExtension)?.identifier, text: nil))
            }
        } else if let text = conforming(.plainText) ?? conforming(.text) {
            provider.loadItem(forTypeIdentifier: text, options: nil) { item, _ in
                switch item {
                case let string as String:
                    done(write(root: root, payload: nil, filename: nil, uti: text, text: string))
                case let url as URL where url.isFileURL:
                    done(write(root: root, payload: url, filename: url.lastPathComponent, uti: text, text: nil))
                case let data as Data:
                    done(write(root: root, payload: nil, filename: nil, uti: text, text: String(data: data, encoding: .utf8)))
                default:
                    done(false)
                }
            }
        } else if conforming(.url) != nil {
            _ = provider.loadObject(ofClass: URL.self) { url, _ in
                guard let url else { return done(false) }
                done(write(root: root, payload: nil, filename: nil, uti: UTType.url.identifier, text: url.absoluteString))
            }
        } else if let data = conforming(.data) ?? identifiers.first {
            copyFile(provider, typeIdentifier: data, root: root, done: done)
        } else {
            done(false)
        }
    }

    /// `loadFileRepresentation` streams to a temporary file that disappears after the handler
    /// returns, so the copy happens inside it. Original bytes are preserved.
    private static func copyFile(_ provider: NSItemProvider, typeIdentifier: String, root: URL, done: @escaping @Sendable (Bool) -> Void) {
        let suggestedName = provider.suggestedName
        _ = provider.loadFileRepresentation(forTypeIdentifier: typeIdentifier) { url, _ in
            guard let url else { return done(false) }
            let filename = suggestedName.map { name in
                (name as NSString).pathExtension.isEmpty && !url.pathExtension.isEmpty ? "\(name).\(url.pathExtension)" : name
            } ?? url.lastPathComponent
            done(write(root: root, payload: url, filename: filename, uti: typeIdentifier, text: nil))
        }
    }

    /// Fallback for image providers that only hand out `UIImage` objects.
    private static func saveImageObject(_ provider: NSItemProvider, root: URL, done: @escaping @Sendable (Bool) -> Void) {
        provider.loadItem(forTypeIdentifier: UTType.image.identifier, options: nil) { item, _ in
            switch item {
            case let url as URL:
                done(write(root: root, payload: url, filename: url.lastPathComponent, uti: UTType.image.identifier, text: nil))
            case let data as Data:
                done(write(root: root, data: data, filename: nil, uti: UTType.image.identifier))
            case let image as UIImage:
                done(write(root: root, data: image.jpegData(compressionQuality: 0.95), filename: nil, uti: UTType.jpeg.identifier))
            default:
                done(false)
            }
        }
    }

    private static func write(root: URL, data: Data?, filename: String?, uti: String) -> Bool {
        guard let data else { return false }
        let temp = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: temp) }
        guard (try? data.write(to: temp)) != nil else { return false }
        return write(root: root, payload: temp, filename: filename, uti: uti, text: nil)
    }

    private static func write(root: URL, payload: URL?, filename: String?, uti: String?, text: String?) -> Bool {
        guard payload != nil || !(text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        let fileManager = FileManager.default
        let id = UUID().uuidString.lowercased()
        let temp = root.appendingPathComponent(".tmp-\(id)", isDirectory: true)
        do {
            try fileManager.createDirectory(at: temp, withIntermediateDirectories: true)
            if let payload {
                let name = "payload.\(payloadExtension(filename: filename ?? payload.lastPathComponent, uti: uti))"
                try fileManager.copyItem(at: payload, to: temp.appendingPathComponent(name))
            }
            let item = Item(
                original_filename: filename,
                uti: uti,
                received_at_ms: Int64(Date().timeIntervalSince1970 * 1000),
                text: payload == nil ? text : nil
            )
            try JSONEncoder().encode(item).write(to: temp.appendingPathComponent("item.json"))
            try fileManager.moveItem(at: temp, to: root.appendingPathComponent(id, isDirectory: true))
            return true
        } catch {
            try? fileManager.removeItem(at: temp)
            return false
        }
    }

    /// Extension from the source filename, else from the UTI, else `bin`.
    static func payloadExtension(filename: String?, uti: String?) -> String {
        let allowed = CharacterSet.alphanumerics
        if let ext = filename.map({ ($0 as NSString).pathExtension.lowercased() }),
           !ext.isEmpty, ext.count <= 10, ext.unicodeScalars.allSatisfy({ allowed.contains($0) }) {
            return ext
        }
        if let uti, let ext = UTType(uti)?.preferredFilenameExtension {
            return ext
        }
        return "bin"
    }
}
