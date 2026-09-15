import CoreGraphics
import Foundation
import ImageIO
import Testing
import UIKit
@testable import calorietracker

@Suite struct ExerciseCatalogLoaderTests {
    @Test func bundledCatalogueDecodesEveryExercise() throws {
        let url = try #require(ExerciseCatalogLoader.exercisesJSONURL())
        let items = try ExerciseCatalogLoader.items(from: Data(contentsOf: url))

        #expect(items.count == 1_326)
        #expect(Set(items.map(\.id)).count == items.count)
        #expect(items.allSatisfy { !$0.name.isEmpty && !$0.instructions.isEmpty && !$0.primaryMuscles.isEmpty })
        #expect(items.map(\.name) == items.map(\.name).sorted { $0.localizedCaseInsensitiveCompare($1) == .orderedAscending })
    }

    @Test func catalogueMediaIsPinnedToTheDatasetCommit() throws {
        let url = try #require(ExerciseCatalogLoader.exercisesJSONURL())
        let items = try ExerciseCatalogLoader.items(from: Data(contentsOf: url))
        let prefix = "https://raw.githubusercontent.com/hasaneyldrm/exercises-dataset/"

        let withMedia = items.filter { $0.imageURL != nil }
        #expect(withMedia.count == 1_324)
        #expect(withMedia.allSatisfy {
            $0.imageURL?.absoluteString.hasPrefix(prefix) == true
                && $0.imageURL?.pathExtension == "jpg"
                && $0.gifURL?.absoluteString.hasPrefix(prefix) == true
                && $0.gifURL?.pathExtension == "gif"
                && $0.mediaAttribution == ExerciseCatalogLoader.mediaAttribution
        })
    }

    @Test func quickLogActivitiesStayInTheCatalogue() throws {
        let url = try #require(ExerciseCatalogLoader.exercisesJSONURL())
        let items = try ExerciseCatalogLoader.items(from: Data(contentsOf: url))

        for id in [OutdoorActivitySettings.walkingExerciseID, OutdoorActivitySettings.runningExerciseID] {
            let item = try #require(items.first { $0.id == id })
            #expect(item.isCardio)
            #expect(item.imageURL == nil && item.gifURL == nil)
            #expect(item.mediaAttribution == nil)
        }
    }

    @Test func everyCatalogueMuscleBelongsToASplitGroup() throws {
        let url = try #require(ExerciseCatalogLoader.exercisesJSONURL())
        let items = try ExerciseCatalogLoader.items(from: Data(contentsOf: url))
        let muscles = Set(items.flatMap { $0.primaryMuscles + $0.secondaryMuscles })

        #expect(items.allSatisfy { $0.primaryMuscles.count == 1 && $0.target == $0.primaryMuscles[0] })
        for split in [StrengthWorkoutSplit.pushPullLegs, .upperLower] {
            let grouped = Set(StrengthWorkoutSplitGroup.groups(for: split, availableMuscles: muscles.sorted()).flatMap(\.muscles))
            #expect(muscles.subtracting(grouped).isEmpty, "\(split) misses: \(muscles.subtracting(grouped).sorted())")
        }
    }

    @Test func recordMapsToLibraryItem() throws {
        let json = #"""
        [{"id":"0001","name":"3/4 sit-up","bodyPart":"waist","target":"abs","equipment":"body weight",
          "secondaryMuscles":["hip flexors","lower back","abs"],"instructions":[" Lie flat. ",""],
          "gifUrl":"https://example.com/videos/0001-2gPfomN.gif","imageUrl":"https://example.com/images/0001-2gPfomN.jpg"},
         {"id":"0002","name":"air bike","bodyPart":"waist","target":"abs","equipment":"body weight",
          "secondaryMuscles":[],"instructions":["Pedal."],"gifUrl":"http://insecure.example.com/a.gif","imageUrl":null}]
        """#
        let items = try ExerciseCatalogLoader.items(from: Data(json.utf8))
        let situp = try #require(items.first { $0.id == "0001" })

        #expect(situp.name == "3/4 Sit-Up")
        #expect(situp.bodyPart == "Waist")
        #expect(situp.rawEquipment == "Body Weight")
        #expect(situp.primaryMuscles == ["Abs"])
        #expect(situp.target == "Abs")
        #expect(situp.secondaryMuscles == ["Hip Flexors", "Lower Back", "Abs"])
        #expect(situp.instructions == ["Lie flat."])
        #expect(situp.imageURL?.lastPathComponent == "0001-2gPfomN.jpg")
        #expect(situp.gifURL?.lastPathComponent == "0001-2gPfomN.gif")

        let airBike = try #require(items.first { $0.id == "0002" })
        #expect(airBike.gifURL == nil, "non-HTTPS media is rejected")
        #expect(airBike.imageURL == nil)
    }

    @Test func searchableTextCoversIdentityButNotInstructions() {
        let item = ExerciseLibraryItem(
            id: "0025",
            name: "Barbell Bench Press",
            bodyPart: "chest",
            rawEquipment: "barbell",
            primaryMuscles: ["Chest"],
            secondaryMuscles: ["Triceps"],
            instructions: ["Unrack the bar."]
        )
        #expect(item.searchableText.contains("bench"))
        #expect(item.searchableText.contains("triceps"))
        #expect(!item.searchableText.contains("unrack"))
    }
}

@Suite struct ExerciseMediaStoreTests {
    @Test func cacheFileNamesAreStableAndTyped() throws {
        let gif = try #require(URL(string: "https://raw.githubusercontent.com/hasaneyldrm/exercises-dataset/sha/videos/0001-2gPfomN.gif"))
        let jpg = try #require(URL(string: "https://raw.githubusercontent.com/hasaneyldrm/exercises-dataset/sha/images/0001-2gPfomN.jpg"))

        #expect(ExerciseMediaStore.cacheFileName(for: gif) == ExerciseMediaStore.cacheFileName(for: gif))
        #expect(ExerciseMediaStore.cacheFileName(for: gif).hasSuffix(".gif"))
        #expect(ExerciseMediaStore.cacheFileName(for: jpg).hasSuffix(".jpg"))
        #expect(ExerciseMediaStore.cacheFileName(for: gif) != ExerciseMediaStore.cacheFileName(for: jpg))
    }

    @Test func onlyHTTPSMediaIsFetched() throws {
        #expect(ExerciseMediaStore.isAllowedRemoteURL(try #require(URL(string: "https://example.com/a.gif"))))
        #expect(!ExerciseMediaStore.isAllowedRemoteURL(try #require(URL(string: "http://example.com/a.gif"))))
        #expect(!ExerciseMediaStore.isAllowedRemoteURL(URL(fileURLWithPath: "/tmp/a.gif")))
    }

    @Test func mediaPayloadValidation() {
        #expect(ExerciseMediaStore.isValidMediaData(Data("GIF89a....".utf8)))
        #expect(ExerciseMediaStore.isValidMediaData(Data([0xFF, 0xD8, 0xFF, 0xE0, 0x00])))
        #expect(ExerciseMediaStore.isValidMediaData(Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00])))
        #expect(!ExerciseMediaStore.isValidMediaData(Data()))
        #expect(!ExerciseMediaStore.isValidMediaData(Data("<html>rate limited</html>".utf8)))
        var oversized = Data("GIF89a".utf8)
        oversized.append(Data(repeating: 0, count: ExerciseMediaStore.maxMediaBytes))
        #expect(!ExerciseMediaStore.isValidMediaData(oversized))
    }

    @Test func cachedMediaIsServedWithoutNetwork() async throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ExerciseMediaStoreTests-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)

        let remote = try #require(URL(string: "https://example.invalid/images/0001-x.jpg"))
        let cached = directory.appendingPathComponent(ExerciseMediaStore.cacheFileName(for: remote))
        try Data([0xFF, 0xD8, 0xFF, 0xE0]).write(to: cached)

        let store = ExerciseMediaStore(cacheDirectory: directory)
        #expect(await store.localURL(for: remote) == cached)
        #expect(await store.localURL(for: try #require(URL(string: "http://example.invalid/a.jpg"))) == nil)
    }

    @Test func retryBackoffMatchesAndroid() {
        #expect(ExerciseMediaRetryPolicy.delay(attempt: 0) == .seconds(15))
        #expect(ExerciseMediaRetryPolicy.delay(attempt: 1) == .seconds(30))
        #expect(ExerciseMediaRetryPolicy.delay(attempt: 2) == .seconds(60))
        #expect(ExerciseMediaRetryPolicy.delay(attempt: 9) == .seconds(60))
        #expect(ExerciseMediaRetryPolicy.delay(attempt: -1) == .seconds(15))
    }

    @Test func gifFramesDecodeIntoAnAnimatedImage() throws {
        let data = try #require(Self.twoFrameGIF())
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("anim-\(UUID().uuidString).gif")
        defer { try? FileManager.default.removeItem(at: url) }
        try data.write(to: url)

        let animated = try #require(ExerciseMediaImageCache.shared.image(fileURL: url, animated: true, maxPixelSize: 64))
        #expect(animated.images?.count == 2)
        #expect(abs(animated.duration - 0.5) < 0.01)

        let still = try #require(ExerciseMediaImageCache.shared.image(fileURL: url, animated: false, maxPixelSize: 64))
        #expect(still.images == nil)
    }

    @Test func bundleShipsNoLegacyWorkoutFrames() throws {
        let resources = try #require(Bundle.main.resourceURL)
        let enumerator = FileManager.default.enumerator(at: resources, includingPropertiesForKeys: nil)
        let leaked = (enumerator?.allObjects as? [URL] ?? []).filter {
            $0.lastPathComponent.contains("_v2_") && $0.pathExtension == "png"
                || $0.lastPathComponent == "workout-vectors"
                || $0.lastPathComponent == "FreeExerciseDB"
        }
        #expect(leaked.isEmpty, "legacy workout assets in bundle: \(leaked.map(\.lastPathComponent))")
    }

    private static func twoFrameGIF() -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, "com.compuserve.gif" as CFString, 2, nil) else { return nil }
        for (index, delay) in [0.2, 0.3].enumerated() {
            guard let context = CGContext(
                data: nil, width: 8, height: 8, bitsPerComponent: 8, bytesPerRow: 0,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return nil }
            context.setFillColor(index == 0 ? CGColor(red: 1, green: 0, blue: 0, alpha: 1) : CGColor(red: 0, green: 0, blue: 1, alpha: 1))
            context.fill(CGRect(x: 0, y: 0, width: 8, height: 8))
            guard let image = context.makeImage() else { return nil }
            let properties = [kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFDelayTime: delay]] as CFDictionary
            CGImageDestinationAddImage(destination, image, properties)
        }
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }
}
