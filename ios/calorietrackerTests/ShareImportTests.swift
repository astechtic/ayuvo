import Testing
import Foundation
import UIKit
@testable import calorietracker

struct ShareImportTests {
    
    @Test func testShareImportFlow() async throws {
        // Point to a temp file instead of the App Group container (unavailable in test target)
        let testURL = FileManager.default.temporaryDirectory.appendingPathComponent("shared_import_test.jpg")
        defer { try? FileManager.default.removeItem(at: testURL) }
        ShareImportManager.sharedImportURL = testURL
        
        // 1. Prepare dummy image data
        let dummyImage = UIImage(systemName: "fork.knife")!
        guard let dummyData = dummyImage.jpegData(compressionQuality: 0.8) else {
            Issue.record("Failed to generate dummy image data")
            return
        }
        
        // 2. Save it
        let success = ShareImportManager.saveSharedImage(dummyData)
        #expect(success, "Should save the image to the shared file URL")
        
        // 3. Verify it is detected
        #expect(ShareImportManager.hasSharedImage(), "Should detect shared image")
        
        // 4. Consume it
        let consumedImage = ShareImportManager.consumeSharedImage()
        #expect(consumedImage != nil, "Should retrieve the consumed image")
        
        // 5. Verify it is cleaned up
        #expect(!ShareImportManager.hasSharedImage(), "Should clean up/delete the image after consuming")
    }
}
