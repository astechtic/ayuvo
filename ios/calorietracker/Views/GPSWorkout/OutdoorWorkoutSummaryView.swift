import MapKit
import SwiftUI

/// Summary of a GPS / Apple Watch outdoor workout: route map (coloured by heart-rate zone when heart rate
/// exists), totals, per-km splits, elevation, heart-rate zones, VO₂max and recovery.
struct OutdoorWorkoutSummaryView: View {
    @Environment(StrengthWorkoutStore.self) private var workoutStore
    @Environment(\.dismiss) private var dismiss
    let session: StrengthWorkoutSession
    @State private var route: OutdoorRoute?
    @State private var confirmDelete = false

    private var summary: OutdoorWorkoutSummary? { session.outdoor }
    private var useMetric: Bool { Locale.current.measurementSystem == .metric }
    private var sport: OutdoorSport? { summary.flatMap { OutdoorSport(rawValue: $0.sport) } }

    var body: some View {
        List {
            if let route, route.points.count >= 2 {
                Section {
                    OutdoorRouteMap(route: route, heartRate: session.heartRate != nil)
                        .frame(height: 260)
                        .listRowInsets(EdgeInsets())
                } footer: {
                    if session.heartRate != nil, !route.heartRate.isEmpty {
                        zoneLegend
                    }
                }
            }

            Section("Summary") {
                row("Date", session.startedAt.formatted(date: .abbreviated, time: .shortened))
                row("Time", WorkoutFormat.duration(Double(session.durationSeconds)))
                if let summary {
                    row("Distance", WorkoutFormat.distance(summary.distanceM, useMetric: useMetric))
                    if summary.movingSeconds > 0 { row("Moving time", WorkoutFormat.duration(summary.movingSeconds)) }
                    if sport?.showsSpeed == true {
                        row("Avg speed", WorkoutFormat.speed(mps: summary.avgSpeedMps, useMetric: useMetric))
                    } else {
                        row("Avg pace", WorkoutFormat.pace(secondsPerKm: summary.avgPaceSecondsPerKm, useMetric: useMetric))
                    }
                    if let max = summary.maxSpeedMps { row("Max speed", WorkoutFormat.speed(mps: max, useMetric: useMetric)) }
                    row("Elevation", String(format: "+%.0f m / −%.0f m", summary.elevationGainM, summary.elevationLossM))
                }
                if let kcal = session.caloriesBurned {
                    row("Energy", "\(kcal) kcal" + (summary?.energyMethod == "keytel" ? " (heart rate)" : " (estimate)"))
                }
                if summary?.recordedOn == "watch" { row("Recorded on", "Apple Watch") }
            }

            if let splits = summary?.splits, !splits.isEmpty {
                Section("Splits") {
                    ForEach(splits, id: \.km) { split in
                        HStack {
                            Text("Km \(split.km)").font(.body.monospacedDigit())
                            Spacer()
                            Text(WorkoutFormat.duration(split.seconds)).font(.body.monospacedDigit().weight(.semibold))
                            if sport?.showsSpeed == true {
                                Text(WorkoutFormat.speed(mps: split.seconds > 0 ? 1000 / split.seconds : nil, useMetric: true))
                                    .foregroundStyle(.secondary).frame(width: 90, alignment: .trailing)
                            }
                        }
                    }
                }
            }

            if let heart = session.heartRate {
                Section("Heart rate") {
                    if let avg = heart.avgHr { row("Average", "\(Int(avg.rounded())) bpm") }
                    if let max = heart.maxHr { row("Maximum", "\(Int(max.rounded())) bpm") }
                    if let trimp = heart.trimp { row("TRIMP", String(format: "%.0f", trimp)) }
                    if let hrr1 = heart.hrr1 {
                        row("Recovery (1 min)", "\(Int(hrr1)) bpm" + (heart.hrr1FlagLow == true ? " · low" : ""))
                    }
                    if heart.zoneSeconds.contains(where: { $0 > 0 }) {
                        HeartRateZoneBars(zoneSeconds: heart.zoneSeconds)
                    }
                    Text(String(format: "Coverage %.0f%%", heart.coveragePct))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }

            if let summary, summary.bestVO2max != nil {
                Section {
                    if let cooper = summary.cooperVO2max {
                        row("Cooper test", String(format: "%.1f mL/kg/min", cooper))
                        if let d = summary.cooperDistanceM { row("12-minute distance", WorkoutFormat.distance(d, useMetric: useMetric)) }
                    }
                    if let v = summary.vo2max {
                        row("Steady segments", String(format: "%.1f mL/kg/min", v))
                    }
                } header: {
                    Text("Cardio fitness (VO₂max)")
                } footer: {
                    Text(WorkoutConfig.shared.disclaimer)
                }
            }

            Section {
                Button("Delete Workout", role: .destructive) { confirmDelete = true }
            }
        }
        .navigationTitle(summary?.sportTitle ?? "Workout")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: session.id) { route = OutdoorRouteStore.load(sessionID: session.id) }
        .confirmationDialog("Delete this workout?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                workoutStore.deleteSession(session.id)
                OutdoorWorkoutRecorder.shared.clearFinishedSession()
                dismiss()
            }
        } message: {
            Text("It is removed from your diary and, when Ayuvo saved it there, from Apple Health.")
        }
    }

    private func row(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(value).foregroundStyle(.secondary).monospacedDigit()
        }
    }

    private var zoneLegend: some View {
        HStack(spacing: 8) {
            ForEach(0..<5, id: \.self) { zone in
                HStack(spacing: 3) {
                    Circle().fill(HeartRateZoneBars.color(zone)).frame(width: 8, height: 8)
                    Text("Z\(zone + 1)").font(.caption2)
                }
            }
        }
    }
}

/// Seconds per heart-rate zone as horizontal bars.
struct HeartRateZoneBars: View {
    let zoneSeconds: [Double]

    static func color(_ zone: Int) -> Color {
        [Color.gray, .blue, .green, .orange, .red][min(max(zone, 0), 4)]
    }

    var body: some View {
        let total = max(1, zoneSeconds.reduce(0, +))
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(zoneSeconds.enumerated()), id: \.offset) { index, seconds in
                HStack(spacing: 8) {
                    Text("Zone \(index + 1)").font(.caption).frame(width: 56, alignment: .leading)
                    GeometryReader { geo in
                        RoundedRectangle(cornerRadius: 3)
                            .fill(Self.color(index))
                            .frame(width: max(2, geo.size.width * seconds / total))
                    }
                    .frame(height: 10)
                    Text(WorkoutFormat.duration(seconds)).font(.caption.monospacedDigit()).frame(width: 56, alignment: .trailing)
                }
            }
        }
        .padding(.vertical, 4)
    }
}

/// Route polyline; coloured by heart-rate zone when heart-rate samples cover the route.
struct OutdoorRouteMap: View {
    let route: OutdoorRoute
    let heartRate: Bool

    private struct Segment: Identifiable {
        let id: Int
        let coordinates: [CLLocationCoordinate2D]
        let color: Color
    }

    private var segments: [Segment] {
        let points = route.points.filter { ($0.acc ?? 100) <= WorkoutConfig.shared.thresholds.maxHAccuracyM }
        guard points.count >= 2 else { return [] }
        let samples = route.heartRate
        guard heartRate, !samples.isEmpty else {
            return [Segment(id: 0, coordinates: points.map { .init(latitude: $0.lat, longitude: $0.lon) }, color: .workoutAccent)]
        }
        let profile = UserProfile.load()
        let hrMax = WorkoutHeartRateSource.tanakaHRMax(age: profile.map { Double($0.age) })
        let th = WorkoutConfig.shared.thresholds
        var index = 0
        func zone(at t: Int64) -> Int? {
            while index + 1 < samples.count, samples[index + 1].t <= t { index += 1 }
            let s = samples[index]
            guard abs(s.t - t) <= 120_000 else { return nil }
            return min(4, HeartRateWorkout.zone(s.bpm, hrMax: hrMax, rhr: nil, th))
        }
        var out: [Segment] = []
        var current: [CLLocationCoordinate2D] = []
        var currentZone: Int?? = .none
        for p in points {
            let z = zone(at: p.t)
            let c = CLLocationCoordinate2D(latitude: p.lat, longitude: p.lon)
            if case .some(let existing) = currentZone, existing != z {
                current.append(c)
                out.append(Segment(id: out.count, coordinates: current,
                                   color: existing.map { HeartRateZoneBars.color($0) } ?? .gray))
                current = [c]
            } else {
                current.append(c)
            }
            currentZone = .some(z)
        }
        if current.count >= 2, case .some(let z) = currentZone {
            out.append(Segment(id: out.count, coordinates: current, color: z.map { HeartRateZoneBars.color($0) } ?? .gray))
        }
        return out
    }

    var body: some View {
        let segs = segments
        Map(initialPosition: .automatic) {
            ForEach(segs) { seg in
                MapPolyline(coordinates: seg.coordinates).stroke(seg.color, lineWidth: 5)
            }
            if let first = route.points.first {
                Marker("Start", systemImage: "flag", coordinate: .init(latitude: first.lat, longitude: first.lon)).tint(.green)
            }
            if let last = route.points.last {
                Marker("End", systemImage: "flag.checkered", coordinate: .init(latitude: last.lat, longitude: last.lon)).tint(.red)
            }
        }
    }
}
