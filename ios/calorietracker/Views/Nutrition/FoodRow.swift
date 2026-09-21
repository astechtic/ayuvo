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

// MARK: - Food Row
struct FoodRow: View {
    let entry: FoodEntry
    @Environment(FoodStore.self) private var foodStore
    @State private var imagePreview: FullScreenImagePreview?

    private var servingText: String? {
        guard let grams = entry.servingSizeGrams else {
            let quantity = entry.reviewSelectedServingQuantity ?? 1
            let quantityText = ServingUnitEditor.formatQuantity(quantity)
            return "\(quantityText) \(String(localized: "Serving"))"
        }
        let formatted = grams == grams.rounded() ? "\(Int(grams))" : String(format: "%.1f", grams)
        if let selectedUnit = entry.selectedServingUnit,
           let quantity = entry.selectedServingQuantity,
           quantity > 0 {
            let option = ServingUnitOption.option(matching: selectedUnit, in: entry.servingUnitOptions)
            if !option.isGramUnit {
                let quantityText = ServingUnitEditor.formatQuantity(quantity)
                return "\(quantityText) \(option.displayUnit(for: quantity)) (~\(formatted)g)"
            }
        }
        return "\(formatted)g"
    }

    var body: some View {
        HStack(spacing: 12) {
            // Thumbnail — tap opens full-screen viewer without opening edit.
            if entry.imageFilename != nil || entry.imageData != nil {
                Button {
                    Task {
                        let images = await FoodEntryPhotoLoader.viewerImages(for: entry)
                        guard !images.isEmpty else { return }
                        await MainActor.run {
                            imagePreview = FullScreenImagePreview(images: images)
                        }
                    }
                } label: {
                    FoodEntryThumbnailView(
                        filename: entry.imageFilename,
                        legacyData: entry.imageData,
                        additionalCount: entry.listThumbnailAdditionalPhotoCount
                    )
                }
                .buttonStyle(.plain)
                .accessibilityLabel("View full photo")
            } else if let emoji = entry.emoji {
                Text(emoji)
                    .font(.system(size: 28))
                    .frame(width: 56, height: 56)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            } else {
                Image(systemName: "fork.knife")
                    .font(.title3)
                    .foregroundStyle(AppColors.calorie)
                    .frame(width: 56, height: 56)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            }

            // Info
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    HStack(spacing: 4) {
                        Text(entry.name)
                            .font(.system(.body, design: .rounded, weight: .medium))
                            .fixedSize(horizontal: false, vertical: true)
                        if foodStore.isFavorite(entry) {
                            Image(systemName: "heart.fill")
                                .font(.caption2)
                                .foregroundStyle(AppColors.calorie)
                        }
                    }
                    Spacer()
                    Text(entry.timeString)
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.tertiary)
                }

                HStack(spacing: 6) {
                    Text("\(entry.calories.formatted()) kcal")
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        .foregroundStyle(AppColors.calorie)

                    if let serving = servingText {
                        Text("·")
                            .foregroundStyle(.tertiary)
                        Text(serving)
                            .font(.system(.caption, design: .rounded))
                            .foregroundStyle(.secondary)
                    }
                }

                HStack(spacing: 8) {
                    MacroPill(label: "P", value: entry.protein)
                    MacroPill(label: "C", value: entry.carbs)
                    MacroPill(label: "F", value: entry.fat)
                }
            }
        }
        .padding(.vertical, 4)
        .fullScreenImagePreview($imagePreview)
    }
}

struct MacroPill: View {
    let label: String
    let value: Double

    var body: some View {
        Text("\(label) \(MacroValueFormatter.withUnit(value))")
            .font(.system(.caption2, design: .rounded, weight: .medium))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(AppColors.calorie.opacity(0.08), in: Capsule())
    }
}
