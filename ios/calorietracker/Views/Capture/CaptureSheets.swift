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

// MARK: - Multi-photo Capture Review
struct MultiPhotoCaptureSheet: View {
    @Binding var images: [UIImage]
    let isImportingPhotos: Bool
    @Binding var selectedPhotoItems: [PhotosPickerItem]
    @Binding var description: String
    let onAddPhoto: () -> Void
    let onRemove: (Int) -> Void
    let onAnalyze: (Bool) -> Void
    let onCancel: () -> Void
    @State private var showAdditionalPhotoPicker = false
    @State private var progressiveMeal = false
    @State private var showProgressiveInfo = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text("\(images.count) of 10 photos")
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        .foregroundStyle(.secondary)

                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 12) {
                            ForEach(Array(images.enumerated()), id: \.offset) { index, image in
                                Image(uiImage: image)
                                    .resizable()
                                    .scaledToFill()
                                    .frame(width: 240, height: 260)
                                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                                    .overlay(alignment: .topTrailing) {
                                        Button {
                                            onRemove(index)
                                        } label: {
                                            Image(systemName: "xmark")
                                                .font(.caption.weight(.bold))
                                                .foregroundStyle(.white)
                                                .frame(width: 30, height: 30)
                                                .background(.black.opacity(0.6), in: Circle())
                                        }
                                        .padding(10)
                                    }
                                    .overlay(alignment: .bottomLeading) {
                                        Text("Photo \(index + 1)")
                                            .font(.caption.weight(.semibold))
                                            .foregroundStyle(.white)
                                            .padding(.horizontal, 10)
                                            .padding(.vertical, 6)
                                            .background(.black.opacity(0.55), in: Capsule())
                                            .padding(10)
                                    }
                            }
                        }
                        .scrollTargetLayout()
                    }
                    .scrollTargetBehavior(.viewAligned)

                    if images.count < 10 {
                        HStack {
                            Spacer()
                            Button {
                                if isImportingPhotos {
                                    showAdditionalPhotoPicker = true
                                } else {
                                    onAddPhoto()
                                }
                            } label: {
                                Label(
                                    isImportingPhotos ? "Add Photos" : "Add Photo",
                                    systemImage: isImportingPhotos ? "photo.on.rectangle" : "camera.fill"
                                )
                            }
                            .buttonStyle(.bordered)
                            .tint(AppColors.calorie)
                        }
                    }

                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 5) {
                            HStack(spacing: 6) {
                                Text("Progressive Meal")
                                    .font(.system(.body, design: .rounded, weight: .semibold))
                                Button {
                                    showProgressiveInfo = true
                                } label: {
                                    Image(systemName: "info.circle.fill")
                                        .foregroundStyle(AppColors.calorie)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("How Progressive Meal works")
                            }
                            Text("Photo 1 → 2 → 3 follows each ingredient added to the same plate. Visible scale differences become ingredient weights.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 8)
                        Toggle("", isOn: $progressiveMeal)
                            .labelsHidden()
                            .tint(AppColors.calorie)
                            .disabled(images.count < 2)
                    }
                    .padding(14)
                    .background(
                        Color(.secondarySystemGroupedBackground),
                        in: RoundedRectangle(cornerRadius: 16, style: .continuous)
                    )

                    VStack(alignment: .leading, spacing: 8) {
                        Text("Note for food analysis (optional)")
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            .foregroundStyle(.secondary)
                        TextField(
                            "e.g. chicken is 180g, rice is 220g, use half the sauce",
                            text: $description,
                            axis: .vertical
                        )
                        .lineLimit(3...6)
                        .padding(14)
                        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 14))
                    }
                }
                .padding()
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Meal Photos")
            .navigationBarTitleDisplayMode(.inline)
            .photosPicker(
                isPresented: $showAdditionalPhotoPicker,
                selection: $selectedPhotoItems,
                maxSelectionCount: max(1, 10 - images.count),
                selectionBehavior: .ordered,
                matching: .images
            )
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    NativeSheetToolbarButton(title: "Cancel", action: onCancel)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    NativeSheetToolbarButton(
                        title: "Analyze",
                        isEmphasized: true,
                        isDisabled: images.isEmpty,
                        action: { onAnalyze(progressiveMeal && images.count > 1) }
                    )
                }
            }
            .onChange(of: images.count) { _, count in
                if count < 2 { progressiveMeal = false }
            }
            .alert("How Progressive Meal works", isPresented: $showProgressiveInfo) {
                Button("Done", role: .cancel) {}
            } message: {
                Text("Use this when every photo shows the same plate after another ingredient is added. Keep the photos in order and make the scale display visible. Ayuvo uses the difference between consecutive scale totals to estimate each new ingredient. Leave this off when the photos are only different angles of the same meal.")
            }
        }
    }
}

// MARK: - Context Description Sheet
struct ContextDescriptionSheet: View {
    let image: UIImage?
    @Binding var description: String
    let onAnalyze: () -> Void
    let onCancel: () -> Void

    @FocusState private var isFocused: Bool
    @State private var showError = false
    @State private var errorMessage = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    if let image {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxHeight: 240)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .overlay(
                                RoundedRectangle(cornerRadius: 16, style: .continuous)
                                    .strokeBorder(AppColors.calorie.opacity(0.15), lineWidth: 1)
                            )
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("Note for food analysis (optional)")
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                            .foregroundStyle(.secondary)

                        ZStack(alignment: .topLeading) {
                            if description.isEmpty {
                                Text("e.g. \"This is a half portion\" or \"Cooked in olive oil\"")
                                    .foregroundStyle(.tertiary)
                                    .font(.body)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 10)
                                    .allowsHitTesting(false)
                            }
                            TextField("", text: $description, axis: .vertical)
                                .font(.body)
                                .lineLimit(3...6)
                                .textFieldStyle(.plain)
                                .focused($isFocused)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 10)
                        }
                        .padding(12)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(Color(.secondarySystemGroupedBackground))
                        )
                    }

                }
                .padding()
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Add Description")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    NativeSheetToolbarButton(title: "Cancel", action: onCancel)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    NativeSheetToolbarButton(
                        title: "Analyze",
                        isEmphasized: true,
                        action: onAnalyze
                    )
                }
            }
            .onAppear { isFocused = true }
            .alert("Error", isPresented: $showError) {
                Button("OK") { }
            } message: {
                Text(errorMessage)
            }
        }
    }
}
