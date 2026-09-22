import SwiftUI

struct WorkoutTextView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(StrengthWorkoutStore.self) private var workoutStore
    let selectedDate: Date
    let unit: WeightUnit
    let bodyWeightKg: Double
    let onAdded: (Date) -> Void
    var startsWithVoice = false
    @State private var description = ""
    @State private var draft: WorkoutTextDraft?
    @State private var followUps: [WorkoutFollowUp] = []
    @State private var clarification: WorkoutClarification?
    @State private var reply = ""
    @State private var voiceReply = false
    @State private var requestID = UUID()
    @FocusState private var inputFocused: Bool
    @State private var busy = false
    @State private var error: String?
    @State private var request: Task<Void, Never>?
    /// Library exercises behind the clarification options (by option text), resolved once per question.
    @State private var optionItems: [String: ExerciseLibraryItem] = [:]
    private var library: [ExerciseLibraryItem] { workoutStore.exerciseLibrary.exercises }

    var body: some View {
        VStack(spacing: 0) {
            if voiceReply {
                VoiceInputView(onCancel: { voiceReply = false }, onSubmit: { text in
                    voiceReply = false
                    answer(text)
                })
            } else if draft == nil && !busy && error == nil && description.isEmpty {
                if startsWithVoice {
                    VoiceInputView(onCancel: { dismiss() }, onSubmit: submit)
                } else {
                    TextFoodInputView(onCancel: { dismiss() }, onSubmit: submit, placeholders: [
                        "20 minutes of rope skipping",
                        "3 sets of 10 bench presses at 40 kg",
                        "Standing calf raise machine, 3 sets of 20, RPE 6"
                    ])
                }
            } else {
                reviewContent
                    .transaction { $0.animation = nil }
            }
        }
        .onDisappear { request?.cancel() }
        .onChange(of: clarification?.options) { _, _ in
            optionItems = WorkoutClarificationOptions.items(for: clarification?.options ?? [], library: library)
        }
    }

    private func submit(_ text: String) {
        description = String(text.prefix(4000))
        analyze()
    }

    private var reviewContent: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let binding = Binding($draft) {
                        WorkoutTextReview(draft: binding)
                        if let planned = try? binding.wrappedValue.planned(library: library),
                           let estimate = StrengthWorkoutBurnEstimator.estimate(exercises: planned, bodyWeightKg: bodyWeightKg,
                               defaultWeightUnit: unit, defaultRPEScale: workoutStore.preferences.rpeScale) {
                            Text("Estimated burn: \(estimate.calories) kcal").font(.headline)
                        }
                        Text("Calories are estimates. Use Calculate in the diary after adding your workout.")
                            .font(.footnote).foregroundStyle(.secondary)
                        HStack {
                            Button("Edit description") { draft = nil; error = nil; clarification = nil; followUps = [] }
                                .buttonStyle(.bordered)
                            Button("Add to diary", action: save).buttonStyle(.borderedProminent)
                                .disabled(binding.wrappedValue.exercises.isEmpty)
                        }
                    } else if clarification != nil || !followUps.isEmpty {
                        followUpContent(clarification)
                    } else {
                        Text("Describe what you did. Review the details before adding them to your workout diary.")
                        TextField("20 minutes of rope skipping, then 3 sets of 10 bench presses at 40 kg",
                                  text: $description, axis: .vertical)
                            .lineLimit(2...5).textFieldStyle(.plain)
                            .autocorrectionDisabled().focused($inputFocused)
                            .padding(18)
                            .background(Color(.quaternarySystemFill), in: RoundedRectangle(cornerRadius: 12))
                            .accessibilityLabel("Workout description")
                            .onChange(of: description) { _, value in
                                if value.count > 4000 { description = String(value.prefix(4000)) }
                                error = nil
                                followUps = []
                                clarification = nil
                            }
                        Button { analyze() } label: {
                            Text(busy ? "Finding exercises…" : (error == nil ? "Analyze" : "Retry"))
                                .font(.headline).frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent).controlSize(.large)
                        .disabled(description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        Button("Cancel") { dismiss() }.foregroundStyle(.secondary)
                        if busy { ProgressView() }
                    }
                    if let error { Text(error).foregroundStyle(.red) }
                }
                .padding(20)
                .disabled(busy)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(Color(.systemGroupedBackground))
            .navigationTitle(navigationTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    // Icon-only so the title keeps its room.
                    Button(action: startOver) {
                        Image(systemName: "arrow.counterclockwise")
                    }
                    .accessibilityLabel(Text("Start over"))
                    .accessibilityIdentifier("workout.text.startOver")
                }
            }
        }
    }

    private var navigationTitle: String {
        if draft != nil { return String(localized: "Review workout") }
        if clarification != nil { return String(localized: "Quick question") }
        return startsWithVoice ? String(localized: "Voice workout") : String(localized: "Describe workout")
    }

    @ViewBuilder
    private func followUpContent(_ question: WorkoutClarification?) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("You described")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(description)
                .font(.subheadline)
                .lineLimit(3)
            ForEach(followUps.indices, id: \.self) { index in
                Label(followUps[index].answer, systemImage: "arrowshape.turn.up.right")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12, style: .continuous))

        if let question {
            Text(question.question)
                .font(.headline)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("workout.clarification.question")
            if !question.options.isEmpty {
                optionsRow(question.options)
            }
            TextField("Your answer", text: $reply, axis: .vertical)
                .lineLimit(2, reservesSpace: true)
                .textFieldStyle(.plain)
                .padding(12)
                .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .accessibilityLabel("Your answer")
                .onChange(of: reply) { _, value in
                    if value.count > 500 { reply = String(value.prefix(500)) }
                }
            HStack {
                Button("Voice reply", systemImage: "mic") { voiceReply = true }.buttonStyle(.bordered)
                Spacer()
                Button("Continue") { answer(reply) }.buttonStyle(.borderedProminent)
                    .disabled(reply.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        } else {
            Button { analyze() } label: {
                Text(busy ? "Finding exercises…" : (error == nil ? "Analyze" : "Retry"))
                    .font(.headline).frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).controlSize(.large)
            if busy { ProgressView() }
        }
    }

    /// Exercise candidates as a horizontal strip of image cards; other answers ("3x10", "Barbell")
    /// as a row of chips.
    @ViewBuilder
    private func optionsRow(_ options: [String]) -> some View {
        let showsCards = options.contains { optionItems[$0] != nil }
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(alignment: .top, spacing: showsCards ? 12 : 8) {
                ForEach(Array(options.enumerated()), id: \.element) { index, option in
                    Group {
                        if showsCards {
                            WorkoutClarificationOptionCard(title: option, item: optionItems[option]) { answer(option) }
                        } else {
                            Button(option) { answer(option) }
                                .buttonStyle(.bordered)
                                .buttonBorderShape(.capsule)
                                .lineLimit(1)
                        }
                    }
                    .accessibilityIdentifier("workout.clarification.option.\(index)")
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 2)
        }
        .padding(.horizontal, -20)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("Suggested answers"))
    }

    private func answer(_ text: String) {
        guard let clarification else { return }
        do {
            // An option card, or a typed exact library name, is a final exercise choice.
            let key = WorkoutClarificationOptions.key(text)
            let chosen = optionItems[text] ?? library.first { WorkoutClarificationOptions.key($0.name) == key }
            let conversation = try WorkoutConversation(original: description, turns: followUps)
                .answering(question: clarification.question, answer: text, exerciseID: chosen?.id)
            followUps = conversation.turns
            self.clarification = nil
            reply = ""
            analyze()
        } catch { self.error = error.localizedDescription }
    }

    private func startOver() {
        requestID = UUID()
        request?.cancel()
        request = nil
        description = ""
        followUps = []
        clarification = nil
        reply = ""
        draft = nil
        error = nil
        busy = false
        voiceReply = false
    }

    private func analyze(repeatedQuestion: String? = nil) {
        inputFocused = false
        busy = true; error = nil
        let id = UUID()
        requestID = id
        request = Task { @MainActor in
            defer { if requestID == id { busy = false } }
            do {
                let conversation = WorkoutConversation(original: description, turns: followUps)
                let result = try await GeminiService.analyzeWorkout(description: try conversation.requestDescription(),
                                                                     date: selectedDate, unit: unit, library: library,
                                                                     chosenExerciseIDs: conversation.chosenExerciseIDs,
                                                                     repeatedQuestion: repeatedQuestion)
                try Task.checkCancellation()
                guard requestID == id else { return }
                clarification = nil
                draft = result
            } catch is CancellationError { }
            catch let question as WorkoutClarification {
                guard requestID == id, !Task.isCancelled else { return }
                if WorkoutConversation(original: description, turns: followUps).alreadyAsked(question) {
                    // Never show a question the user already answered: retry once with an explicit
                    // "already answered" note, then stop with guidance instead of looping.
                    if repeatedQuestion == nil {
                        analyze(repeatedQuestion: question.question)
                    } else {
                        self.error = String(localized: "Couldn't finish from your answers. Add the missing detail to your description (for example sets and reps) or start over.")
                    }
                    return
                }
                clarification = question
                reply = ""
            }
            catch {
                guard requestID == id, !Task.isCancelled else { return }
                self.error = error.localizedDescription
            }
        }
    }

    private func save() {
        guard let draft else { return }
        do {
            try workoutStore.addTextWorkout(draft, library: library)
            if let date = StrengthWorkoutDate.date(for: draft.date) { onAdded(date) }
            dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

/// Resolves clarification options to library exercises by name (case, diacritics and spacing ignored).
enum WorkoutClarificationOptions {
    static func key(_ name: String) -> String {
        name.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
            .lowercased()
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
    }

    static func items(for options: [String], library: [ExerciseLibraryItem]) -> [String: ExerciseLibraryItem] {
        guard !options.isEmpty else { return [:] }
        let wanted = Dictionary(options.map { (key($0), $0) }, uniquingKeysWith: { first, _ in first })
        var result: [String: ExerciseLibraryItem] = [:]
        for item in library {
            guard let option = wanted[key(item.name)], result[option] == nil else { continue }
            result[option] = item
            if result.count == wanted.count { break }
        }
        return result
    }
}

/// One exercise candidate: the library thumbnail (placeholder when missing) with its name below.
private struct WorkoutClarificationOptionCard: View {
    let title: String
    let item: ExerciseLibraryItem?
    let action: () -> Void

    private static let width: CGFloat = 128

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                AnimatedExerciseVisual(
                    exerciseName: title,
                    imagePaths: item?.imagePaths ?? [],
                    imageURL: item?.imageURL,
                    gifURL: item?.gifURL,
                    height: 96,
                    fillsWidth: true,
                    animatesFrames: false,
                    fallbackSystemImage: item?.isCardio == true ? "figure.run" : "dumbbell.fill",
                    fallbackTitle: String(localized: "No image")
                )
                .frame(width: Self.width - 16, height: 96)
                .accessibilityHidden(true)
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.leading)
                    .lineLimit(2, reservesSpace: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(8)
            .frame(width: Self.width)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(title))
        .accessibilityHint(Text("Uses this exercise as your answer"))
    }
}

private struct WorkoutTextReview: View {
    @Binding var draft: WorkoutTextDraft

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Review workout").font(.headline)
            TextField("Date (YYYY-MM-DD)", text: $draft.date).textFieldStyle(.roundedBorder)
                .accessibilityLabel("Date (YYYY-MM-DD)")
            ForEach($draft.exercises) { $exercise in
                VStack(alignment: .leading, spacing: 10) {
                    Text(exercise.name).font(.headline)
                    if exercise.exerciseID == nil {
                        Text("Custom timed activity · calorie estimate uses a general activity rate")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    if !exercise.minutes.isEmpty || exercise.sets.isEmpty {
                        HStack {
                            Text("Minutes")
                            TextField("Minutes", text: $exercise.minutes).keyboardType(.decimalPad)
                                .textFieldStyle(.roundedBorder).accessibilityLabel("Minutes")
                        }
                    }
                    if !exercise.minutes.isEmpty {
                        Text("Effort (moderate if unspecified)").font(.caption)
                        Picker("Effort", selection: $exercise.intensity) {
                            Text("Light").tag("light")
                            Text("Moderate").tag("moderate")
                            Text("Vigorous").tag("vigorous")
                        }.pickerStyle(.segmented)
                    }
                    if !exercise.sets.isEmpty {
                        Picker("Weight unit", selection: $exercise.unit) {
                            Text("kg").tag("kg")
                            Text("lbs").tag("lbs")
                        }.pickerStyle(.segmented)
                        ForEach($exercise.sets) { $set in
                            HStack {
                                Text("Set \((exercise.sets.firstIndex { $0.id == set.id } ?? 0) + 1)")
                                    .font(.caption)
                                TextField("Weight (\(exercise.unit))", text: $set.weight)
                                    .keyboardType(.decimalPad).accessibilityLabel("Weight (\(exercise.unit))")
                                TextField("Reps", text: $set.reps).keyboardType(.numberPad).accessibilityLabel("Reps")
                                TextField("RPE 1–10", text: $set.rpe).keyboardType(.decimalPad).accessibilityLabel("RPE 1–10")
                            }.textFieldStyle(.roundedBorder)
                        }
                    }
                    Button("Remove exercise", role: .destructive) {
                        let id = exercise.id
                        draft.exercises.removeAll { $0.id == id }
                    }
                }
                .padding(14)
                .background(.quaternary, in: RoundedRectangle(cornerRadius: 16))
            }
        }
    }
}
