import SwiftUI

/// Start / Finish control for a strength session with a real start and end, and a banner for a
/// running GPS or Apple Watch workout.
struct StrengthSessionCard: View {
    @Environment(StrengthWorkoutStore.self) private var workoutStore
    let selectedDate: Date
    let openGPS: () -> Void
    @State private var coordinator = WorkoutSessionCoordinator.shared
    @State private var recorder = OutdoorWorkoutRecorder.shared
    @State private var mirror = WatchWorkoutMirror.shared
    @State private var confirmDiscard = false

    private var selectedKey: String { StrengthWorkoutDate.key(for: selectedDate) }

    var body: some View {
        VStack(spacing: 10) {
            if recorder.isActive || mirror.isMirroring {
                Button(action: openGPS) {
                    HStack(spacing: 10) {
                        Image(systemName: mirror.isMirroring && !recorder.isActive ? "applewatch" : recorder.sport.systemImage)
                            .foregroundStyle(Color.workoutAccent)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(recorder.isActive ? recorder.sport.title : "Apple Watch workout")
                                .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            Text(gpsStatus).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text("Open").font(.caption.weight(.bold)).foregroundStyle(Color.workoutAccent)
                    }
                    .padding(14)
                    .background(Color.workoutCard, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
                .buttonStyle(.plain)
            }

            if let active = workoutStore.activeSession {
                if active.diaryDateKey == selectedKey {
                    HStack(spacing: 12) {
                        Image(systemName: "dumbbell.fill").foregroundStyle(Color.workoutAccent)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Session running").font(.system(.subheadline, design: .rounded, weight: .semibold))
                            Text(timerInterval: active.startedAt...Date.distantFuture, countsDown: false)
                                .font(.title3.monospacedDigit().weight(.bold))
                        }
                        Spacer()
                        Button("Discard") { confirmDiscard = true }
                            .font(.caption.weight(.semibold))
                            .tint(.secondary)
                        Button {
                            Task { await coordinator.finishStrengthSession() }
                        } label: {
                            if coordinator.isFinishingStrength {
                                ProgressView()
                            } else {
                                Text("Finish").font(.subheadline.weight(.bold))
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(Color.workoutAccent)
                        .disabled(coordinator.isFinishingStrength)
                    }
                    .padding(14)
                    .background(Color.workoutCard, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .confirmationDialog("Discard this session?", isPresented: $confirmDiscard, titleVisibility: .visible) {
                        Button("Discard Session", role: .destructive) { coordinator.discardStrengthSession() }
                    } message: {
                        Text("Your logged sets stay in the diary; only the session timer is dropped.")
                    }
                }
            } else if Calendar.current.isDateInToday(selectedDate) {
                Button {
                    coordinator.startStrengthSession(on: selectedDate)
                } label: {
                    Label("Start session", systemImage: "play.circle.fill")
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .foregroundStyle(Color.workoutAccent)
                        .background(Color.workoutCard, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityHint("Times your workout so calories and heart rate use its real start and end")
            }
        }
    }

    private var gpsStatus: String {
        if !recorder.isActive { return "Recording on your watch" }
        switch recorder.phase {
        case .paused: return "Paused"
        case .recovery: return "Measuring recovery"
        case .interrupted: return "Interrupted — resume or save"
        case .saving: return "Saving"
        default: return WorkoutFormat.distance(recorder.live?.distanceM ?? 0)
        }
    }
}

/// Outdoor workouts on the selected day; a row opens the summary.
struct OutdoorWorkoutDaySection: View {
    let sessions: [StrengthWorkoutSession]
    let open: (StrengthWorkoutSession) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "location.fill").foregroundStyle(Color.workoutAccent)
                Text("GPS workouts").font(.system(.subheadline, design: .rounded, weight: .semibold))
                Spacer()
            }
            ForEach(sessions) { session in
                Button { open(session) } label: {
                    HStack {
                        Image(systemName: OutdoorSport(rawValue: session.outdoor?.sport ?? "")?.systemImage ?? "figure.walk")
                            .frame(width: 24)
                            .foregroundStyle(Color.workoutAccent)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(session.displayTitle).font(.system(.body, design: .rounded, weight: .medium))
                            Text(detail(session)).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        if let kcal = session.caloriesBurned {
                            Text("\(kcal) kcal").font(.subheadline.weight(.semibold)).foregroundStyle(Color.workoutAccent)
                        }
                        Image(systemName: "chevron.right").font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(14)
        .background(Color.workoutCard, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private func detail(_ session: StrengthWorkoutSession) -> String {
        var parts: [String] = []
        if let o = session.outdoor { parts.append(WorkoutFormat.distance(o.distanceM)) }
        parts.append(WorkoutFormat.duration(Double(session.durationSeconds)))
        parts.append(session.startedAt.formatted(date: .omitted, time: .shortened))
        if session.outdoor?.recordedOn == "watch" { parts.append("Apple Watch") }
        return parts.joined(separator: " · ")
    }
}

/// A heart-rate window proposed for the Calculate button.
struct StrengthWindowProposal: Identifiable {
    let id = UUID()
    let date: Date
    let windows: [HeartRateWorkout.Window]
}

/// Lets the user confirm or edit the start and end of a strength session detected from heart rate.
struct StrengthWindowConfirmSheet: View {
    @Environment(\.dismiss) private var dismiss
    let proposal: StrengthWindowProposal
    let onConfirm: (DateInterval) -> Void
    let onSkip: () -> Void
    @State private var start: Date
    @State private var end: Date

    init(proposal: StrengthWindowProposal, onConfirm: @escaping (DateInterval) -> Void, onSkip: @escaping () -> Void) {
        self.proposal = proposal
        self.onConfirm = onConfirm
        self.onSkip = onSkip
        let best = proposal.windows.max { $0.minutes < $1.minutes } ?? proposal.windows[0]
        _start = State(initialValue: Date(timeIntervalSince1970: Double(best.startMs) / 1000))
        _end = State(initialValue: Date(timeIntervalSince1970: Double(best.endMs) / 1000))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(Array(proposal.windows.enumerated()), id: \.offset) { _, window in
                        let s = Date(timeIntervalSince1970: Double(window.startMs) / 1000)
                        let e = Date(timeIntervalSince1970: Double(window.endMs) / 1000)
                        Button {
                            start = s
                            end = e
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text("\(s.formatted(date: .omitted, time: .shortened)) – \(e.formatted(date: .omitted, time: .shortened))")
                                    Text("\(window.minutes) min · avg \(Int(window.avgHr.rounded())) bpm · max \(Int(window.maxHr.rounded())) bpm")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if abs(s.timeIntervalSince(start)) < 1 && abs(e.timeIntervalSince(end)) < 1 {
                                    Image(systemName: "checkmark").foregroundStyle(Color.workoutAccent)
                                }
                            }
                        }
                        .foregroundStyle(.primary)
                    }
                } header: {
                    Text("Detected from heart rate")
                } footer: {
                    Text("Sustained heart rate at or above 50% of your heart-rate reserve (or 100 bpm). Pick the window of your workout and adjust it if needed.")
                }
                Section("Workout window") {
                    DatePicker("Start", selection: $start, displayedComponents: [.hourAndMinute])
                    DatePicker("End", selection: $end, in: start..., displayedComponents: [.hourAndMinute])
                }
                Section {
                    Button("Use This Window") {
                        onConfirm(DateInterval(start: start, end: max(end, start.addingTimeInterval(60))))
                        dismiss()
                    }
                    .font(.headline)
                    Button("Skip — Calculate Without a Window") {
                        onSkip()
                        dismiss()
                    }
                } footer: {
                    Text("With a window, calories use your heart rate when it covers at least 70% of it, and the workout is saved to Apple Health with its real start and end.")
                }
            }
            .navigationTitle("When Did You Train?")
            .navigationBarTitleDisplayMode(.inline)
        }
        .interactiveDismissDisabled()
    }
}
