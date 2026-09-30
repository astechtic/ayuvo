import MapKit
import SwiftUI

/// Full-screen live view of a GPS workout (or of a mirrored Apple Watch workout): time, distance,
/// pace or speed, heart rate, elevation and the current split, with Pause/Resume, Lap and End. After
/// the workout is saved it shows the summary.
struct OutdoorWorkoutLiveView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(StrengthWorkoutStore.self) private var workoutStore
    @State private var recorder = OutdoorWorkoutRecorder.shared
    @State private var mirror = WatchWorkoutMirror.shared
    @State private var confirmEnd = false
    @State private var confirmDiscard = false

    private var useMetric: Bool { Locale.current.measurementSystem == .metric }

    var body: some View {
        NavigationStack {
            Group {
                if let id = recorder.lastFinishedSessionID, !recorder.isActive {
                    if let session = workoutStore.session(id: id) {
                        OutdoorWorkoutSummaryView(session: session)
                    } else {
                        ContentUnavailableView("Workout not saved", systemImage: "exclamationmark.triangle",
                                               description: Text("Your diary could not be updated."))
                    }
                } else if recorder.isActive {
                    recordingContent
                } else if mirror.isMirroring {
                    watchContent
                } else {
                    ContentUnavailableView("No workout running", systemImage: "figure.run")
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(recorder.isActive || mirror.isMirroring ? "Minimize" : "Done") {
                        if !recorder.isActive { recorder.clearFinishedSession() }
                        dismiss()
                    }
                }
            }
        }
        .confirmationDialog("End workout?", isPresented: $confirmEnd, titleVisibility: .visible) {
            Button("End and Save") { recorder.end() }
            Button("Discard Workout", role: .destructive) { recorder.discard() }
            Button("Keep Going", role: .cancel) {}
        }
        .confirmationDialog("Discard this workout?", isPresented: $confirmDiscard, titleVisibility: .visible) {
            Button("Discard", role: .destructive) { recorder.discard() }
            Button("Cancel", role: .cancel) {}
        }
    }

    // MARK: - Phone recording

    private var recordingContent: some View {
        ScrollView {
            VStack(spacing: 16) {
                header
                if recorder.locationDenied {
                    Label("Location access was turned off. Turn it on in Settings to keep recording.", systemImage: "location.slash")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    VStack(spacing: 16) {
                        Text(WorkoutFormat.duration(recorder.activeElapsed(at: context.date)))
                            .font(.system(size: 64, weight: .bold, design: .rounded).monospacedDigit())
                            .contentTransition(.numericText())
                        if recorder.cooperTest {
                            let left = max(0, OutdoorWorkoutRecorder.cooperDurationSeconds - recorder.activeElapsed(at: context.date))
                            Text("Cooper test · \(WorkoutFormat.duration(left)) left")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Color.workoutAccent)
                        }
                        metricsGrid
                    }
                }
                liveMap
                controls
            }
            .padding()
        }
        .background(Color.workoutBackground.ignoresSafeArea())
    }

    private var header: some View {
        HStack {
            Label(recorder.sport.title, systemImage: recorder.sport.systemImage)
                .font(.headline)
            Spacer()
            Text(stateTitle)
                .font(.caption.weight(.bold))
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background(stateColor.opacity(0.18), in: Capsule())
                .foregroundStyle(stateColor)
            if recorder.watchSessionActive {
                Image(systemName: "applewatch").foregroundStyle(.secondary)
            }
        }
    }

    private var stateTitle: String {
        switch recorder.phase {
        case .recording: return "Recording"
        case .paused: return "Paused"
        case .recovery: return "Recovery"
        case .saving: return "Saving"
        case .interrupted: return "Interrupted"
        case .idle: return ""
        }
    }

    private var stateColor: Color {
        switch recorder.phase {
        case .recording: return .green
        case .paused, .interrupted: return .orange
        default: return .blue
        }
    }

    private var metricsGrid: some View {
        let live = recorder.live
        return LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
            tile("Distance", WorkoutFormat.distance(live?.distanceM ?? 0, useMetric: useMetric), "map")
            if recorder.sport.showsSpeed {
                tile("Speed", WorkoutFormat.speed(mps: recorder.currentSpeedMps ?? live?.avgSpeedMps, useMetric: useMetric),
                     "speedometer")
            } else {
                tile("Pace", WorkoutFormat.pace(secondsPerKm: recorder.currentPaceSecondsPerKm, useMetric: useMetric),
                     "stopwatch")
            }
            tile("Avg pace", WorkoutFormat.pace(secondsPerKm: live?.avgPaceSPerKm, useMetric: useMetric), "gauge.with.dots.needle.33percent")
            tile("Heart rate", recorder.currentHeartRate.map { "\($0) bpm" } ?? "--", "heart.fill")
            tile("Elevation", String(format: "+%.0f / −%.0f m", live?.elevationGainM ?? 0, live?.elevationLossM ?? 0),
                 "mountain.2")
            if let split = recorder.currentSplit {
                tile("Km \(split.index)", WorkoutFormat.duration(split.seconds) + String(format: " · %.0f m", max(0, split.meters)),
                     "flag.checkered")
            }
            if let lap = recorder.currentLap, !recorder.laps.isEmpty {
                tile("Lap \(lap.index)", WorkoutFormat.duration(lap.seconds), "flag.fill")
            }
        }
    }

    private func tile(_ title: String, _ value: String, _ icon: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Label(title, systemImage: icon)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.title3.monospacedDigit().weight(.semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color.workoutCard, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    @ViewBuilder
    private var liveMap: some View {
        let coordinates = recorder.points.suffix(3000).map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
        if coordinates.count >= 2 {
            Map {
                MapPolyline(coordinates: coordinates).stroke(Color.workoutAccent, lineWidth: 4)
                if let last = coordinates.last {
                    Annotation("", coordinate: last) {
                        Circle().fill(Color.workoutAccent).frame(width: 14, height: 14)
                            .overlay(Circle().stroke(.white, lineWidth: 2))
                    }
                }
            }
            .frame(height: 200)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .allowsHitTesting(false)
        }
    }

    @ViewBuilder
    private var controls: some View {
        switch recorder.phase {
        case .recording, .paused:
            HStack(spacing: 12) {
                if recorder.phase == .paused {
                    controlButton("Resume", "play.fill", .green) { recorder.resume() }
                } else {
                    controlButton("Pause", "pause.fill", .orange) { recorder.pause() }
                }
                controlButton("Lap", "flag.fill", .blue) { recorder.lap() }
                controlButton("End", "stop.fill", .red) { confirmEnd = true }
            }
        case .recovery(let until):
            VStack(spacing: 10) {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    Text("Measuring heart-rate recovery · \(WorkoutFormat.duration(max(0, until.timeIntervalSince(context.date))))")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Text("Stand or walk slowly. The recovery one minute after you stopped is saved with the workout.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                Button("Skip and Save") { recorder.skipRecovery() }
                    .buttonStyle(.borderedProminent)
            }
        case .saving:
            ProgressView("Saving workout…")
        case .interrupted:
            VStack(spacing: 10) {
                Text("This workout stopped recording when Ayuvo closed.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                HStack(spacing: 12) {
                    controlButton("Resume", "play.fill", .green) { recorder.resume() }
                    controlButton("Save", "square.and.arrow.down", .blue) { recorder.end() }
                    controlButton("Discard", "trash", .red) { confirmDiscard = true }
                }
            }
        case .idle:
            EmptyView()
        }
    }

    private func controlButton(_ title: String, _ icon: String, _ color: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: icon).font(.title2.weight(.bold))
                Text(title).font(.caption.weight(.semibold))
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .foregroundStyle(.white)
            .background(color, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }

    // MARK: - Mirrored Apple Watch workout

    private var watchContent: some View {
        let m = mirror.latest
        return ScrollView {
            VStack(spacing: 16) {
                HStack {
                    Label(OutdoorSport(rawValue: m?.sport ?? "")?.title ?? "Workout", systemImage: "applewatch")
                        .font(.headline)
                    Spacer()
                    Text(m?.state == "paused" ? "Paused" : "On Apple Watch")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.secondary)
                }
                Text(WorkoutFormat.duration(m?.elapsed ?? 0))
                    .font(.system(size: 64, weight: .bold, design: .rounded).monospacedDigit())
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                    tile("Distance", WorkoutFormat.distance(m?.distance ?? 0, useMetric: useMetric), "map")
                    tile("Heart rate", m?.heartRate.map { "\(Int($0.rounded())) bpm" } ?? "--", "heart.fill")
                    tile("Energy", m?.energy.map { "\(Int($0.rounded())) kcal" } ?? "--", "flame.fill")
                    tile("Laps", "\(m?.laps ?? 0)", "flag.fill")
                }
                HStack(spacing: 12) {
                    if m?.state == "paused" {
                        controlButton("Resume", "play.fill", .green) { mirror.send(.resume) }
                    } else {
                        controlButton("Pause", "pause.fill", .orange) { mirror.send(.pause) }
                    }
                    controlButton("Lap", "flag.fill", .blue) { mirror.send(.lap) }
                    controlButton("End", "stop.fill", .red) { mirror.send(.end) }
                }
            }
            .padding()
        }
        .background(Color.workoutBackground.ignoresSafeArea())
    }
}
