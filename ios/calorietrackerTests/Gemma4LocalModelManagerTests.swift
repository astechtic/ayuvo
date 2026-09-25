import Foundation
import LiteRTLM
import Testing
@testable import calorietracker

@MainActor
struct Gemma4LocalModelManagerTests {
    @Test func approvedArtifactMetadataIsImmutable() {
        #expect(Gemma4LocalModelManager.modelID == "gemma-4-E2B-it")
        #expect(Gemma4LocalModelManager.artifactFilename == "gemma-4-E2B-it.litertlm")
        #expect(Gemma4LocalModelManager.sourceRevision == "6b78abd019e61a1ca4cbe3b212d2c9ce8ff38a94")
        #expect(Gemma4LocalModelManager.artifactByteCount == 2_588_147_712)
        #expect(Gemma4LocalModelManager.artifactSHA256 == "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c")
        #expect(Gemma4LocalModelManager.artifactURL.absoluteString.contains(Gemma4LocalModelManager.sourceRevision))
        #expect(Gemma4LocalModelManager.sourceURL.absoluteString.contains(Gemma4LocalModelManager.sourceRevision))
        #expect(Gemma4LocalModelManager.licenseURL.host == "ai.google.dev")
    }

    @Test func contextWindowMatchesStableMobileLimit() {
        #expect(Gemma4LocalModelManager.maxContextTokens == 4_096)
    }

    @Test func iosVisionEncoderUsesCPUWhileTextKeepsRequestedBackend() throws {
        let gpuConfig = try Gemma4LocalModelManager.engineConfig(
            modelPath: "/model.litertlm",
            backend: .gpu,
            cacheDir: "/cache"
        )
        let cpuConfig = try Gemma4LocalModelManager.engineConfig(
            modelPath: "/model.litertlm",
            backend: .cpu(threadCount: 2),
            cacheDir: "/cache"
        )

        #expect(gpuConfig.backend == .gpu)
        #expect(gpuConfig.visionBackend == .cpu(threadCount: 4))
        #expect(cpuConfig.backend == .cpu(threadCount: 2))
        #expect(cpuConfig.visionBackend == .cpu(threadCount: 4))
    }

    @Test func eligibilityUsesMarketedMemoryClass() {
        let gib: UInt64 = 1_024 * 1_024 * 1_024

        #expect(Gemma4LocalModelManager.memoryClassGB(physicalMemoryBytes: 0) == 0)
        #expect(Gemma4LocalModelManager.memoryClassGB(physicalMemoryBytes: 7 * gib) == 7)
        #expect(Gemma4LocalModelManager.memoryClassGB(physicalMemoryBytes: 7 * gib + 1) == 8)
        #expect(!Gemma4LocalModelManager.isEligible(physicalMemoryBytes: 7 * gib))
        #expect(Gemma4LocalModelManager.isEligible(physicalMemoryBytes: 7 * gib + 1))
        #expect(Gemma4LocalModelManager.isEligible(physicalMemoryBytes: 8 * gib))
    }

    /// The path the Settings rows and the provider picker actually read.
    ///
    /// The static check above rounds the device's memory up to its marketed class; the per-model
    /// check has to do the same. Comparing raw bytes instead rejected every 8 GB iPhone — which
    /// reports about 7.6 GiB — against an 8 GiB model, including one already downloaded and running.
    @Test func perModelEligibilityUsesTheMarketedMemoryClassToo() {
        let gib: UInt64 = 1_024 * 1_024 * 1_024
        // What an "8 GB" iPhone actually reports.
        let eightGBPhone: UInt64 = 7 * gib + 600_000_000

        for descriptor in LocalModelCatalog.chatModels {
            let manager = Gemma4LocalModelManager(
                descriptor: descriptor,
                rootDirectory: makeTemporaryDirectory(),
                physicalMemoryBytes: eightGBPhone
            )
            let expected = descriptor.minimumMemoryClassGB <= 8
            #expect(manager.isEligible == expected,
                    "\(descriptor.displayName) gated at \(descriptor.minimumMemoryClassGB) GB")
        }
    }

    /// MedGemma passes the catalogue's 8 GiB gate but crashed an 8 GB iPhone 17 with an uncatchable
    /// `std::bad_alloc` while loading, so iOS holds it to 12 GB phones.
    @Test func medGemmaIsOfferedOnTwelveGBIPhonesOnly() throws {
        let gib: UInt64 = 1_024 * 1_024 * 1_024
        let eightGBPhone: UInt64 = 7 * gib + 600_000_000
        let twelveGBPhone: UInt64 = 11 * gib + 200_000_000
        let medGemma = try #require(LocalModelCatalog.chatModels.first {
            $0.id == "medgemma-1.5-4b-it-litertlm"
        })
        #expect(medGemma.minimumMemoryClassGB == 12)

        let small = Gemma4LocalModelManager(
            descriptor: medGemma, rootDirectory: makeTemporaryDirectory(), physicalMemoryBytes: eightGBPhone
        )
        let large = Gemma4LocalModelManager(
            descriptor: medGemma, rootDirectory: makeTemporaryDirectory(), physicalMemoryBytes: twelveGBPhone
        )
        #expect(!small.isEligible)
        #expect(large.isEligible)

        // Gemma keeps its 8 GB gate: the floor is per model, not a re-gate of what is installed.
        let gemma = try #require(LocalModelCatalog.gemma)
        #expect(gemma.minimumMemoryClassGB == 8)
    }

    @Test func aModelGatedAboveThisPhoneStaysBlocked() {
        let gib: UInt64 = 1_024 * 1_024 * 1_024
        let eightGBPhone: UInt64 = 7 * gib + 600_000_000
        let big = LocalModelCatalog.chatModels.first { $0.minimumMemoryClassGB > 8 }
        // The catalogue ships at least one desktop-class entry; if that ever stops being true the
        // test has nothing to prove and says so rather than passing silently.
        #expect(big != nil, "no model in the catalogue is gated above 8 GB")
        if let big {
            let manager = Gemma4LocalModelManager(
                descriptor: big,
                rootDirectory: makeTemporaryDirectory(),
                physicalMemoryBytes: eightGBPhone
            )
            #expect(!manager.isEligible)
        }
    }

    /// A gated model with no token must fail *visibly*, before any bytes move.
    ///
    /// The refusal reaching `.failed` is the whole point: a download that ends in `.failed` with
    /// nobody rendering it looks exactly like a button that does nothing.
    @Test func aGatedModelWithoutATokenFailsWithAMessage() async throws {
        let gated = LocalModelCatalog.chatModels.first { $0.requiresAuth }
        #expect(gated != nil, "no gated model in the catalogue")
        guard let gated else { return }

        let previousToken = Gemma4LocalModelManager.huggingFaceToken
        Gemma4LocalModelManager.huggingFaceToken = nil
        defer { Gemma4LocalModelManager.huggingFaceToken = previousToken }

        let manager = Gemma4LocalModelManager(
            descriptor: gated,
            rootDirectory: makeTemporaryDirectory(),
            physicalMemoryBytes: gated.minimumMemoryBytes == 0
                ? Gemma4LocalModelManager.minimumPhysicalMemoryBytes
                : UInt64(gated.minimumMemoryBytes)
        )
        await manager.download()

        guard case .failed(let message) = manager.state else {
            Issue.record("expected a visible failure, got \(manager.state)")
            return
        }
        #expect(message.localizedCaseInsensitiveContains("token"))
        // "Accept the terms" is useless without saying where, so the page is named.
        if let page = gated.repositoryURL {
            #expect(message.contains(page.absoluteString))
        }
    }

    /// Every gated catalogue entry must be able to name the page where its terms are accepted.
    @Test func everyGatedModelNamesItsTermsPage() {
        for descriptor in LocalModelCatalog.chatModels where descriptor.requiresAuth {
            let page = descriptor.repositoryURL
            #expect(page != nil, "\(descriptor.displayName) has no repository page")
            #expect(page?.absoluteString.hasPrefix("https://huggingface.co/") == true)
        }
    }

    /// The headroom check must size itself on the model being downloaded, not on Gemma.
    @Test func storageHeadroomUsesTheModelBeingDownloaded() {
        let biggest = LocalModelCatalog.chatModels.max { $0.sizeBytes < $1.sizeBytes }
        guard let biggest, biggest.sizeBytes > Gemma4LocalModelManager.artifactByteCount else {
            return
        }
        let justEnoughForGemma = Gemma4LocalModelManager.artifactByteCount
            + Gemma4LocalModelManager.installationHeadroomBytes
        #expect(Gemma4LocalModelManager.hasRequiredStorage(availableBytes: justEnoughForGemma))
        #expect(!Gemma4LocalModelManager.hasRequiredStorage(
            availableBytes: justEnoughForGemma, artifactBytes: biggest.sizeBytes
        ))
    }

    @Test func storageCheckIncludesInstallationHeadroom() {
        let required = Gemma4LocalModelManager.artifactByteCount
            + Gemma4LocalModelManager.installationHeadroomBytes
        #expect(!Gemma4LocalModelManager.hasRequiredStorage(availableBytes: required - 1))
        #expect(Gemma4LocalModelManager.hasRequiredStorage(availableBytes: required))
    }

    @Test func verificationStreamsExactBytesAndSHA256() throws {
        let root = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let file = root.appendingPathComponent("fixture.bin")
        try Data("abc".utf8).write(to: file)

        let verification = try Gemma4LocalModelManager.verifyArtifact(
            at: file,
            chunkSize: 2
        )

        #expect(verification.byteCount == 3)
        #expect(verification.sha256 == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test func installRequiresBothExactSizeAndVerifiedMarker() throws {
        let root = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let model = root.appendingPathComponent("model.litertlm")
        let marker = root.appendingPathComponent("verified.sha256")
        try Data([0x01, 0x02, 0x03]).write(to: model)

        #expect(!Gemma4LocalModelManager.hasVerifiedInstall(
            modelURL: model,
            verificationMarkerURL: marker,
            expectedByteCount: 3,
            expectedSHA256: "approved",
            fileManager: .default
        ))

        try Data("wrong".utf8).write(to: marker)
        #expect(!Gemma4LocalModelManager.hasVerifiedInstall(
            modelURL: model,
            verificationMarkerURL: marker,
            expectedByteCount: 3,
            expectedSHA256: "approved",
            fileManager: .default
        ))

        try Data("approved".utf8).write(to: marker)
        #expect(Gemma4LocalModelManager.hasVerifiedInstall(
            modelURL: model,
            verificationMarkerURL: marker,
            expectedByteCount: 3,
            expectedSHA256: "approved",
            fileManager: .default
        ))
    }

    @Test func providerSelectionAlsoRequiresSuccessfulRuntimeMarker() throws {
        let root = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let model = root.appendingPathComponent("model.litertlm")
        let verificationMarker = root.appendingPathComponent("verified.sha256")
        let preparedMarker = root.appendingPathComponent("prepared.version")
        try Data([0x01, 0x02, 0x03]).write(to: model)
        try Data("approved".utf8).write(to: verificationMarker)

        #expect(!Gemma4LocalModelManager.hasPreparedInstall(
            modelURL: model,
            verificationMarkerURL: verificationMarker,
            preparedMarkerURL: preparedMarker,
            expectedByteCount: 3,
            expectedSHA256: "approved",
            fileManager: .default
        ))

        try Data("wrong-runtime".utf8).write(to: preparedMarker)
        #expect(!Gemma4LocalModelManager.hasPreparedInstall(
            modelURL: model,
            verificationMarkerURL: verificationMarker,
            preparedMarkerURL: preparedMarker,
            expectedByteCount: 3,
            expectedSHA256: "approved",
            fileManager: .default
        ))

        try Data(Gemma4LocalModelManager.preparedMarkerContents.utf8).write(to: preparedMarker)
        #expect(Gemma4LocalModelManager.hasPreparedInstall(
            modelURL: model,
            verificationMarkerURL: verificationMarker,
            preparedMarkerURL: preparedMarker,
            expectedByteCount: 3,
            expectedSHA256: "approved",
            fileManager: .default
        ))
    }

    /// `ensureEngine` writes each model's own marker (`LiteRTLM-0.16.0-<its sha>`). The check used
    /// to compare against Gemma's, so a prepared MedGemma or Qwen3 never became selectable and
    /// never appeared in the model pickers.
    @Test func everyCatalogueModelIsSelectableOnceItsOwnMarkerIsWritten() throws {
        let gib: UInt64 = 1_024 * 1_024 * 1_024
        let hugePhone = 32 * gib
        for descriptor in LocalModelCatalog.chatModels {
            let root = makeTemporaryDirectory()
            defer { try? FileManager.default.removeItem(at: root) }
            let handle = try {
                FileManager.default.createFile(atPath: root.appendingPathComponent(descriptor.filename).path,
                                               contents: nil)
                return try FileHandle(forWritingTo: root.appendingPathComponent(descriptor.filename))
            }()
            try handle.truncate(atOffset: UInt64(descriptor.sizeBytes))
            try handle.close()
            try Data(descriptor.sha256.utf8).write(to: root.appendingPathComponent("verified.sha256"))

            let manager = Gemma4LocalModelManager(
                descriptor: descriptor, rootDirectory: root, physicalMemoryBytes: hugePhone
            )
            #expect(!manager.isSelectable, "\(descriptor.displayName) selectable before it was prepared")

            try Data(descriptor.preparedMarkerContents.utf8)
                .write(to: root.appendingPathComponent("prepared.version"))
            #expect(manager.isSelectable, "\(descriptor.displayName) not selectable after it was prepared")
        }
    }

    @Test func deletionRemovesOnlyTheDedicatedModelRoot() throws {
        let parent = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: parent) }
        let modelRoot = parent.appendingPathComponent("Gemma4E2B", isDirectory: true)
        let sibling = parent.appendingPathComponent("keep.txt")
        try FileManager.default.createDirectory(at: modelRoot, withIntermediateDirectories: true)
        try Data([0x01]).write(to: modelRoot.appendingPathComponent("partial.bin"))
        try Data([0x02]).write(to: sibling)

        let manager = Gemma4LocalModelManager(
            rootDirectory: modelRoot,
            physicalMemoryBytes: Gemma4LocalModelManager.minimumPhysicalMemoryBytes
        )
        #expect(manager.hasStoredData)

        try manager.delete()

        #expect(!FileManager.default.fileExists(atPath: modelRoot.path))
        #expect(FileManager.default.fileExists(atPath: sibling.path))
        #expect(manager.state == .notDownloaded)
    }

    @Test func cancellingOutsideAnActiveDownloadNeverDeletesStoredData() throws {
        let root = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let storedFile = root.appendingPathComponent("keep.partial")
        try Data([0x01]).write(to: storedFile)
        let manager = Gemma4LocalModelManager(
            rootDirectory: root,
            physicalMemoryBytes: Gemma4LocalModelManager.minimumPhysicalMemoryBytes
        )

        manager.cancelDownload()

        #expect(FileManager.default.fileExists(atPath: storedFile.path))
        #expect(manager.hasStoredData)
    }

    @Test func noticesAreBundledForOfflineViewing() throws {
        let url = try #require(LiteRTLMNoticesView.noticesURL(in: .main))
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        let size = try #require((attributes[.size] as? NSNumber)?.intValue)
        #expect(size > 1_000_000)
    }

    private func makeTemporaryDirectory() -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("Gemma4LocalModelManagerTests-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}
