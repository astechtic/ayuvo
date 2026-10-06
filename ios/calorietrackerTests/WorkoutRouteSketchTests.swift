import CoreGraphics
import Foundation
import Testing
@testable import calorietracker

/// The Live Activity route drawing: Douglas–Peucker simplification, quantization and payload size.
struct WorkoutRouteSketchTests {
    /// A 1 Hz loop of about 2 km around a park (lat/lon degrees).
    private func loop(count: Int = 3_600) -> [(lat: Double, lon: Double)] {
        (0..<count).map { i in
            let a: Double = Double(i) / Double(count) * 2 * .pi
            let lat: Double = 28.6 + 0.003 * sin(a) + 0.0004 * sin(7 * a)
            let lon: Double = 77.2 + 0.004 * cos(a)
            return (lat, lon)
        }
    }

    @Test func tooFewPointsHasNoSketch() {
        #expect(WorkoutRouteSketch.make(coordinates: []) == nil)
        #expect(WorkoutRouteSketch.make(coordinates: [(28.6, 77.2)]) == nil)
    }

    @Test func longTrackIsCappedAndKeepsEnds() throws {
        let coordinates = loop()
        let sketch = try #require(WorkoutRouteSketch.make(coordinates: coordinates))
        #expect(sketch.count >= 8)
        #expect(sketch.count <= WorkoutRouteSketch.maxPoints)
        #expect(sketch.xy.count == sketch.count * 2)
        // The first and last fixes are always kept; on a closed loop they nearly coincide.
        let first = sketch.point(0), last = sketch.point(sketch.count - 1)
        #expect(hypot(first.x - last.x, first.y - last.y) < 0.05)
    }

    @Test func quantizedBoxIsCentredAndNorthUp() throws {
        // Due north, 1 km: x stays centred, the start is at the bottom (larger y).
        let north: [(lat: Double, lon: Double)] = (0...100).map { i in (28.6 + Double(i) * 0.00009, 77.2) }
        let sketch = try #require(WorkoutRouteSketch.make(coordinates: north))
        #expect(sketch.count == 2)
        #expect(abs(sketch.point(0).x - 0.5) < 0.01)
        #expect(sketch.point(0).y > 0.99)
        #expect(sketch.point(1).y < 0.01)
    }

    @Test func gpsJitterStaysSmall() throws {
        // A few metres of noise while standing still is drawn inside the minimum 60 m box.
        let still: [(lat: Double, lon: Double)] = (0..<50).map { i in
            let lat: Double = 28.6 + Double(i % 3) * 0.00001
            let lon: Double = 77.2 + Double(i % 2) * 0.00001
            return (lat, lon)
        }
        let sketch = try #require(WorkoutRouteSketch.make(coordinates: still))
        let xs = (0..<sketch.count).map { sketch.point($0).x }
        #expect((xs.max()! - xs.min()!) < 0.1)
    }

    @Test func lapStartMapsToTheKeptPoint() throws {
        let coordinates = loop()
        let sketch = try #require(WorkoutRouteSketch.make(coordinates: coordinates, lapStartIndex: 1_801))
        let lapStart = try #require(sketch.lapStart)
        #expect(lapStart > 0 && lapStart < sketch.count - 1)
        // The forced lap point is kept exactly: half way round the loop is its westernmost point.
        #expect(sketch.point(lapStart).x < 0.05)
    }

    @Test func contentStateFitsTheActivityKitBudget() throws {
        let sketch = try #require(WorkoutRouteSketch.make(coordinates: loop()))
        let state = WorkoutActivityAttributes.ContentState(
            kind: "gps", sport: "run", state: "running", timerStart: Date(), pausedElapsed: nil,
            distanceM: 12_345, paceSecondsPerKm: 312, speedMps: 3.2, heartRate: 158, lapCount: 12,
            source: "watch", route: sketch, lapSplits: [301.5, 298.25, 305.75]
        )
        let data = try JSONEncoder().encode(state)
        #expect(data.count < 1_500)
        #expect(try JSONDecoder().decode(WorkoutActivityAttributes.ContentState.self, from: data) == state)
    }

    @Test func olderPayloadWithoutRouteStillDecodes() throws {
        let json = #"{"kind":"gps","sport":"run","state":"running","timerStart":0,"lapCount":0}"#
        let state = try JSONDecoder().decode(WorkoutActivityAttributes.ContentState.self, from: Data(json.utf8))
        #expect(state.route == nil)
        #expect(state.lapSplits == nil)
    }
}
