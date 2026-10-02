import Foundation
import Testing
@testable import calorietracker

/// The live scan state machine (docs/camera-vitals.md §7.1 Live) driven with injected frames: the clock follows frame
/// timestamps, so a 90 s scan runs in the time the engine needs.
@MainActor
struct VitalsScanSessionTests {
    static func fingerFrames(_ json: String) throws -> [VitalsFrame] {
        VitalsSynth.synthFinger(VitalsSynth.Spec(json: try VitalsJSON.parse(json))).map { .finger($0) }
    }

    /// Feeds frames in order, waiting for every analysis, until `stop` is true or the frames run out.
    static func feed(_ session: ScanSession, _ frames: [VitalsFrame]) async {
        await feed(session, frames, until: { $0.phase == .finished })
    }

    static func feed(_ session: ScanSession, _ frames: [VitalsFrame], until stop: (ScanSession) -> Bool) async {
        for frame in frames {
            if stop(session) { return }
            session.ingest(frame)
            if let task = session.analysisTask { await task.value }
        }
    }

    @Test func finishesAtTargetWithAGoodSignal() async throws {
        let frames = try Self.fingerFrames(#"{"ac": 0.02, "duration_s": 95, "fs": 30, "hr_bpm": 72, "jitter_ms": 3, "noise": 0.0005, "resp_bpm": 15, "rsa_ms": 30, "seed": 7}"#)
        let session = ScanSession(mode: .finger, source: nil)
        session.beginWithoutSource()
        await Self.feed(session, frames)
        #expect(session.phase == .finished)
        #expect(!session.isExtending)
        #expect(session.clockS >= session.targetS && session.clockS < session.targetS + 0.2)
        let result = try #require(session.result)
        #expect((result.qualityScore ?? 0) >= 70)
        #expect(abs((result.metric("heart_rate").value ?? 0) - 72) < 1.5)
        // The analysed buffer starts with the 2 s settle before the clock.
        let buffer = try #require(session.analysedBuffer)
        #expect(abs((result.durationS ?? 0) - (session.targetS + 2)) < 0.2)
        #expect(buffer.fingerFrames.first?[0] == 0)
        #expect(!result.signals.isNull)
    }

    @Test func extendsWhileQualityIsLowUpToMax() async throws {
        let frames = try Self.fingerFrames(#"{"ac": 0.002, "duration_s": 100, "fs": 30, "hr_bpm": 72, "jitter_ms": 3, "noise": 0.004, "resp_bpm": 15, "rsa_ms": 30, "seed": 10}"#)
        let session = ScanSession(mode: .finger, source: nil)
        session.beginWithoutSource()
        var sawExtend = false
        await Self.feed(session, frames) { s in
            if s.isExtending { sawExtend = true }
            return s.phase == .finished
        }
        #expect(sawExtend)
        #expect(session.phase == .finished)
        #expect(session.clockS >= session.maxS && session.clockS < session.maxS + 0.2)
        #expect((session.lastQuality ?? 100) < VitalsConfig.shared.scan.extendBelowQuality)
    }

    @Test func pausesWhenTheFingerIsLiftedAndResumes() async throws {
        let frames = try Self.fingerFrames(#"{"ac": 0.02, "duration_s": 40, "events": [{"amp": 0, "end_s": 27, "kind": "no_finger", "start_s": 20}], "fs": 30, "hr_bpm": 70, "jitter_ms": 3, "noise": 0.0005, "resp_bpm": 15, "rsa_ms": 30, "seed": 4}"#)
        let session = ScanSession(mode: .finger, source: nil)
        session.beginWithoutSource()
        #expect(session.guidanceKey == "no_finger")
        await Self.feed(session, frames.filter { $0.tMs < 19_500 }, until: { _ in false })
        #expect(session.clockStarted && !session.isPaused && session.guidanceKey == "ok")
        let beforeLift = session.clockS
        // Lifted for 2 s: the clock keeps running (pauses only after 3 s of failing gates).
        await Self.feed(session, frames.filter { $0.tMs >= 19_500 && $0.tMs < 22_000 }, until: { _ in false })
        #expect(session.guidanceKey == "no_finger" && !session.isPaused)
        await Self.feed(session, frames.filter { $0.tMs >= 22_000 && $0.tMs < 26_500 }, until: { _ in false })
        #expect(session.isPaused)
        let paused = session.clockS
        #expect(paused > beforeLift && paused < beforeLift + 4)
        await Self.feed(session, frames.filter { $0.tMs >= 26_500 && $0.tMs < 26_900 }, until: { _ in false })
        #expect(session.clockS == paused, "the clock is frozen while paused")
        // Finger back: the clock runs again.
        await Self.feed(session, frames.filter { $0.tMs >= 27_500 }, until: { _ in false })
        #expect(!session.isPaused && session.guidanceKey == "ok")
        #expect(session.clockS > paused + 10)
        #expect(session.phase == .live)
    }

    @Test func clockWaitsForTwoSecondsOfPassingGates() async throws {
        let frames = try Self.fingerFrames(#"{"ac": 0.02, "duration_s": 10, "events": [{"amp": 0, "end_s": 4, "kind": "no_finger", "start_s": 0}], "fs": 30, "hr_bpm": 70, "jitter_ms": 3, "noise": 0.0005, "resp_bpm": 15, "rsa_ms": 30, "seed": 4}"#)
        let session = ScanSession(mode: .finger, source: nil)
        session.beginWithoutSource()
        await Self.feed(session, frames.filter { $0.tMs < 5_800 }, until: { _ in false })
        #expect(!session.clockStarted && session.clockS == 0)
        await Self.feed(session, frames.filter { $0.tMs >= 5_800 }, until: { _ in false })
        #expect(session.clockStarted && session.clockS > 3)
        #expect(!session.waveform.isEmpty)
        #expect(session.signalShare > 0.9)
    }

    @Test func faceGatesShowGuidance() throws {
        let session = ScanSession(mode: .face, source: nil)
        session.beginWithoutSource()
        var rois: [String: [Double]] = [:]
        for name in VitalsConfig.shared.face.rois { rois[name] = [160, 115, 95, 0.9] }
        let good = FaceFrameSample(tMs: 0, motion: 0.005, yaw: 2, pitch: 1, luma: 120, faceCount: 1, faceFraction: 0.4, rois: rois)
        #expect(session.gate(.face(good)) == "ok")
        var two = good; two.faceCount = 2
        #expect(session.gate(.face(two)) == "face_multiple")
        var far = good; far.faceFraction = 0.1
        #expect(session.gate(.face(far)) == "face_far")
        var dark = good; dark.luma = 20
        #expect(session.gate(.face(dark)) == "light_dark")
        var moving = good; moving.motion = 0.1
        #expect(session.gate(.face(moving)) == "hold_still")
        #expect(session.gate(.face(.noFace(tMs: 0, count: 0, luma: 80, rois: VitalsConfig.shared.face.rois))) == "face_none")
    }

    @Test func faceReplayFramesFinishAtTarget() async throws {
        let frames = ReplayFrameSource.frames(mode: .face, durationS: 66)
        let session = ScanSession(mode: .face, source: nil)
        session.beginWithoutSource()
        await Self.feed(session, frames)
        #expect(session.phase == .finished)
        let result = try #require(session.result)
        #expect(result.rejectReason == nil)
        #expect((result.qualityScore ?? 0) >= 70)
        #expect(abs((result.metric("heart_rate").value ?? 0) - 70) < 2)
        #expect(result.metric("spo2").reason == "face_not_supported")
    }

    @Test func cancelStopsTheScan() throws {
        let session = ScanSession(mode: .finger, source: nil)
        session.beginWithoutSource()
        session.cancel()
        #expect(session.phase == .cancelled)
        session.ingest(.finger([0, 200, 60, 25, 8, 0.02]))
        #expect(session.clockS == 0 && session.bufferedFrameCount == 0)
    }
}
