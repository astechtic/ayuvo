import SwiftUI
import Photos
import PhotosUI
import PDFKit
import UIKit
import HealthKit
import StoreKit
import WidgetKit
import AVFoundation
import Speech
import UniformTypeIdentifiers

// MARK: - Siri Phrases
struct SiriPhrasesSettingsView: View {
    private let groups: [SiriPhraseGroup] = [
        SiriPhraseGroup(
            title: "Log Food",
            icon: "fork.knife",
            phrases: [
                "Log food in Ayuvo",
                "Add food in Ayuvo",
                "Track food in Ayuvo",
            ]
        ),
        SiriPhraseGroup(
            title: "Today's Calories",
            icon: "chart.bar.fill",
            phrases: [
                "Calories today in Ayuvo",
                "How many calories in Ayuvo",
                "Today's nutrition in Ayuvo",
            ]
        ),
        SiriPhraseGroup(
            title: "Log Weight",
            icon: "scalemass.fill",
            phrases: [
                "Log my weight in Ayuvo",
                "Record weight in Ayuvo",
            ]
        ),
    ]

    var body: some View {
        List {
            Section {
                Label {
                    Text("Say these phrases to Siri to use Ayuvo hands-free.")
                        .foregroundStyle(.secondary)
                } icon: {
                    Image(systemName: "waveform.circle.fill")
                        .foregroundStyle(AppColors.calorie)
                }
            }
            .listRowBackground(AppColors.appCard)

            ForEach(groups) { group in
                Section(group.title) {
                    ForEach(group.phrases, id: \.self) { phrase in
                        Text("Hey Siri, \(phrase)")
                            .foregroundStyle(.primary)
                    }
                }
                .listRowBackground(AppColors.appCard)
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle("Siri Phrases")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct SiriPhraseGroup: Identifiable {
    let title: String
    let icon: String
    let phrases: [String]

    var id: String { title }
}

// MARK: - Copy From Day
struct CopyFromDaySheet: View {
    let targetDate: Date

    @Environment(FoodStore.self) private var foodStore
    @Environment(\.dismiss) private var dismiss
    @State private var sourceDate: Date
    @State private var showFoodLoggingBlocked = false

    init(targetDate: Date) {
        self.targetDate = targetDate
        let previousDay = Calendar.current.date(byAdding: .day, value: -1, to: targetDate) ?? targetDate
        _sourceDate = State(initialValue: previousDay)
    }

    private var mealGroups: [FoodLogMealGroup] {
        foodStore.entriesByMeal(for: sourceDate)
    }

    private var sourceEntries: [FoodEntry] {
        mealGroups.flatMap(\.entries)
    }

    private var targetDateText: String {
        if Calendar.current.isDateInToday(targetDate) {
            return "today"
        }
        return targetDate.formatted(.dateTime.month(.abbreviated).day())
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    DatePicker("Copy From", selection: $sourceDate, displayedComponents: .date)
                        .tint(AppColors.calorie)
                } footer: {
                    Text("Foods will be copied to \(targetDateText). The original entries stay unchanged.")
                }
                .listRowBackground(AppColors.appCard)

                if sourceEntries.isEmpty {
                    Section {
                        VStack(spacing: 12) {
                            Image(systemName: "calendar.badge.exclamationmark")
                                .font(.system(size: 32))
                                .foregroundStyle(AppColors.calorie.opacity(0.45))
                            Text("No foods logged on this day")
                                .font(.system(.subheadline, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 24)
                    }
                    .listRowBackground(AppColors.appCard)
                } else {
                    Section {
                        Button {
                            copy(sourceEntries)
                        } label: {
                            Label("Copy All Foods", systemImage: "plus.circle.fill")
                                .font(.system(.body, design: .rounded, weight: .semibold))
                        }
                        .tint(AppColors.calorie)
                    } footer: {
                        Text("\(sourceEntries.count) food\(sourceEntries.count == 1 ? "" : "s") will be added to \(targetDateText).")
                    }
                    .listRowBackground(AppColors.appCard)

                    ForEach(mealGroups) { group in
                        Section {
                            Button {
                                copy(group.entries)
                            } label: {
                                Label("Copy \(group.meal.displayName)", systemImage: "plus.circle")
                                    .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            }
                            .tint(AppColors.calorie)

                            ForEach(group.entries) { entry in
                                Button {
                                    copy([entry])
                                } label: {
                                    FoodRow(entry: entry)
                                }
                                .buttonStyle(.plain)
                            }
                        } header: {
                            Label(group.meal.displayName, systemImage: group.meal.icon)
                        }
                        .listRowBackground(AppColors.appCard)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Copy from Day")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .alert("Food logging paused", isPresented: $showFoodLoggingBlocked) {
                Button("OK", role: .cancel) { }
            } message: {
                Text("End or cancel your active fast before logging food.")
            }
        }
    }

    private func copy(_ entries: [FoodEntry]) {
        guard !entries.isEmpty else { return }
        let copiedTimestamp = timestamp(on: targetDate, usingTimeFrom: .now)
        for entry in entries {
            let copiedEntry = entry.duplicatedForLogging(at: copiedTimestamp)
            if !foodStore.addEntry(copiedEntry) {
                showFoodLoggingBlocked = true
                return
            }
        }
        dismiss()
    }

    private func timestamp(on day: Date, usingTimeFrom timeSource: Date) -> Date {
        let calendar = Calendar.current
        let dayComponents = calendar.dateComponents([.year, .month, .day], from: day)
        let timeComponents = calendar.dateComponents([.hour, .minute, .second, .nanosecond], from: timeSource)
        var components = DateComponents()
        components.year = dayComponents.year
        components.month = dayComponents.month
        components.day = dayComponents.day
        components.hour = timeComponents.hour
        components.minute = timeComponents.minute
        components.second = timeComponents.second
        components.nanosecond = timeComponents.nanosecond
        return calendar.date(from: components) ?? day
    }
}
