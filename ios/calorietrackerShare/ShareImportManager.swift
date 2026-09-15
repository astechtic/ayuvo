import Foundation
import UIKit

public struct ShareImportManager {
    public static var appGroupID: String {
        Bundle.main.object(forInfoDictionaryKey: "AppGroupIdentifier") as? String
            ?? "group.com.ayuvo.health"
    }
    
    /// File URL for the shared image. Defaults to a file in the App Group container.
    /// Override in tests to use a temp directory.
    public static var sharedImportURL: URL? = {
        let groupID = Bundle.main.object(forInfoDictionaryKey: "AppGroupIdentifier") as? String
            ?? "group.com.ayuvo.health"
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupID)?
            .appendingPathComponent("shared_import.jpg")
    }()
    
    /// Checks if a shared image is available in the App Group container.
    public static func hasSharedImage() -> Bool {
        guard let url = sharedImportURL else { return false }
        return FileManager.default.fileExists(atPath: url.path)
    }
    
    /// Reads and returns the shared image from the App Group container, then deletes it.
    public static func consumeSharedImage() -> UIImage? {
        guard let url = sharedImportURL else { return nil }
        guard let data = try? Data(contentsOf: url),
              let image = UIImage(data: data) else { return nil }
        
        // Clean up the file to keep it clean
        try? FileManager.default.removeItem(at: url)
        return image
    }
    
    /// Helper to write image data to the App Group container (used in Share Extension and Tests)
    @discardableResult
    public static func saveSharedImage(_ data: Data) -> Bool {
        guard let url = sharedImportURL else { return false }
        do {
            try data.write(to: url)
            return true
        } catch {
            return false
        }
    }
}
