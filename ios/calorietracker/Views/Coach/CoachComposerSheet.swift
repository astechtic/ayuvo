import SwiftUI

/// What the composer's `+` opens (docs/coach.md §3, §6): what to attach, and which of the user's
/// data Coach may read in this conversation.
///
/// The switches only ever narrow. A source the user has not connected, or has not consented to,
/// shows "Not connected" and opens its own consent screen instead of flipping here — turning a
/// switch on never grants access.
struct CoachComposerSheet: View {
    enum Attachment: String, Identifiable {
        case camera
        case photos
        case files
        case record
        case note

        var id: String { rawValue }

        var title: String {
            switch self {
            case .camera: String(localized: "Camera")
            case .photos: String(localized: "Photo Library")
            case .files: String(localized: "Files")
            case .record: String(localized: "Health record")
            case .note: String(localized: "Note")
            }
        }

        var subtitle: String {
            switch self {
            case .camera: String(localized: "Take a photo")
            case .photos: String(localized: "Up to 4 pictures")
            case .files: String(localized: "PDF or text; read on this device")
            case .record: String(localized: "Pick a report to talk about")
            case .note: String(localized: "Type or paste something")
            }
        }

        var systemImage: String {
            switch self {
            case .camera: "camera.fill"
            case .photos: "photo.on.rectangle"
            case .files: "doc.text"
            case .record: "list.clipboard"
            case .note: "note.text"
            }
        }
    }

    /// Why a source cannot simply be switched on here.
    enum SourceState: Equatable {
        case on
        case off
        /// The user has no data of that kind yet.
        case unavailable
        /// The data exists but Coach has not been allowed to read it.
        case notConnected
    }

    var states: [CoachSource: SourceState]
    var onAttach: (Attachment) -> Void
    var onToggle: (CoachSource, Bool) -> Void
    var onConnect: (CoachSource) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach([Attachment.camera, .photos, .files, .record, .note]) { item in
                        Button {
                            dismiss()
                            onAttach(item)
                        } label: {
                            attachmentRow(item)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("coach.attach.\(item.rawValue)")
                    }
                } header: {
                    Text("Attach")
                }
                .listRowBackground(AppColors.appCard)

                Section {
                    ForEach(CoachSource.allCases, id: \.self) { source in
                        sourceRow(source)
                    }
                } header: {
                    Text("What Coach can use")
                } footer: {
                    Text("Turning a source off applies to this chat only. Coach never reads anything you have not connected.")
                }
                .listRowBackground(AppColors.appCard)
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Add")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .accessibilityIdentifier("coach.attach")
    }

    private func attachmentRow(_ item: Attachment) -> some View {
        HStack(spacing: 14) {
            Image(systemName: item.systemImage)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(AppColors.calorie)
                .frame(width: 34, height: 34)
                .background(AppColors.calorie.opacity(0.12), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(item.title)
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .foregroundStyle(.primary)
                Text(item.subtitle)
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 3)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func sourceRow(_ source: CoachSource) -> some View {
        let state = states[source] ?? .unavailable
        HStack(spacing: 14) {
            Image(systemName: source.systemImage)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(state == .on ? AppColors.calorie : Color.secondary)
                .frame(width: 30, height: 30)
                .background((state == .on ? AppColors.calorie : Color.secondary).opacity(0.12),
                            in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(source.title)
                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    .foregroundStyle(.primary)
                if let note = stateNote(state) {
                    Text(note)
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)

            switch state {
            case .on, .off:
                Toggle("", isOn: Binding(
                    get: { state == .on },
                    set: { onToggle(source, $0) }
                ))
                .labelsHidden()
                .tint(AppColors.calorie)
            case .notConnected:
                Button("Connect") { onConnect(source) }
                    .font(.system(.footnote, design: .rounded, weight: .semibold))
                    .buttonStyle(.bordered)
                    .tint(AppColors.calorie)
            case .unavailable:
                Text("No data")
                    .font(.system(.caption, design: .rounded))
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 3)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("coach.source.\(source.rawValue)")
    }

    private func stateNote(_ state: SourceState) -> String? {
        switch state {
        case .on: nil
        case .off: String(localized: "Off for this chat")
        case .unavailable: String(localized: "Nothing to read yet")
        case .notConnected: String(localized: "Not connected")
        }
    }
}

/// The one-line summary above the composer when a source is off, so the user can see at a glance
/// what this answer was allowed to use.
struct CoachSourceSummaryRow: View {
    var effective: Set<CoachSource>
    var onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 6) {
                Image(systemName: "line.3.horizontal.decrease.circle")
                    .font(.system(size: 12, weight: .semibold))
                Text(label)
                    .font(.system(.caption, design: .rounded, weight: .medium))
                    .lineLimit(1)
            }
            .foregroundStyle(.secondary)
            .padding(.horizontal, 12)
            .padding(.vertical, 5)
            .background(Capsule().fill(.ultraThinMaterial))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("coach.sources.summary")
    }

    private var label: String {
        if effective.isEmpty { return String(localized: "Using: nothing") }
        let names = CoachSource.allCases.filter { effective.contains($0) }.map(\.title)
        return String(localized: "Using: \(names.joined(separator: ", "))")
    }
}
