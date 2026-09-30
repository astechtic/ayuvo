import CoreLocation
import SwiftUI
import WatchConnectivity

/// Sport picker for a GPS workout: walk, run, cycle or hike, an optional Cooper 12-minute test, and
/// "Record on Apple Watch" when the watch app is installed.
struct OutdoorWorkoutStartSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var sport: OutdoorSport = .run
    @State private var cooperTest = false
    @State private var watchError: String?
    let onStart: (OutdoorSport, Bool) -> Void
    let onStartOnWatch: (OutdoorSport) async -> Bool

    private var watchAvailable: Bool {
        WCSession.isSupported() && WCSession.default.activationState == .activated
            && WCSession.default.isPaired && WCSession.default.isWatchAppInstalled
    }

    private var locationDenied: Bool {
        let status = CLLocationManager().authorizationStatus
        return status == .denied || status == .restricted
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Workout", selection: $sport) {
                        ForEach(OutdoorSport.allCases) { sport in
                            Label(sport.title, systemImage: sport.systemImage).tag(sport)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } header: {
                    Text("Outdoor workout")
                } footer: {
                    Text("Distance, pace, splits, elevation and your route are measured with GPS. Location is used only while this workout records.")
                }

                if sport.supportsVO2max {
                    Section {
                        Toggle("Cooper 12-minute test", isOn: $cooperTest)
                    } footer: {
                        Text("Cover as much distance as you can in 12 minutes; the workout ends automatically and estimates your VO₂max (Cooper 1968). Warm up first and stop if you feel unwell.")
                    }
                }

                if locationDenied {
                    Section {
                        Label("Location access is off for Ayuvo. Turn it on in Settings to record GPS workouts.",
                              systemImage: "location.slash")
                        .foregroundStyle(.secondary)
                        Button("Open Settings") {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        }
                    }
                }

                Section {
                    Button {
                        onStart(sport, cooperTest)
                        dismiss()
                    } label: {
                        Label("Start on iPhone", systemImage: "location.fill")
                            .frame(maxWidth: .infinity)
                            .font(.headline)
                    }
                    .disabled(locationDenied)

                    if watchAvailable {
                        Button {
                            Task {
                                if await onStartOnWatch(sport) {
                                    dismiss()
                                } else {
                                    watchError = "Couldn't open Ayuvo on your Apple Watch. Open it on the watch and start the workout there."
                                }
                            }
                        } label: {
                            Label("Record on Apple Watch", systemImage: "applewatch")
                                .frame(maxWidth: .infinity)
                        }
                    }
                } footer: {
                    if let watchError { Text(watchError).foregroundStyle(.red) }
                }
            }
            .navigationTitle("GPS Workout")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onChange(of: sport) { _, newValue in
                if !newValue.supportsVO2max { cooperTest = false }
            }
        }
    }
}
