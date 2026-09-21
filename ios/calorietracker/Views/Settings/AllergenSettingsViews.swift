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

struct AllergenSensitivitiesDetailView: View {
    let onSave: ([String]) -> Void

    @State private var allergens: [String]
    @State private var value = ""
    @State private var pendingDeletion: String?
    @State private var showImportSource = false
    @State private var showPhotoPicker = false
    @State private var showFileImporter = false
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var isImportingLabReport = false
    @State private var importErrorMessage: String?
    @State private var pendingImportCandidates: [String]?
    @State private var selectedImportNames: Set<String> = []

    init(current: [String], onSave: @escaping ([String]) -> Void) {
        self.onSave = onSave
        _allergens = State(initialValue: current)
    }

    var body: some View {
        List {
            Section {
                if allergens.isEmpty {
                    Text("No sensitivities added")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(allergens, id: \.self) { allergen in
                        HStack {
                            Text(allergen)
                            Spacer(minLength: 0)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button {
                                pendingDeletion = allergen
                            } label: {
                                Label("Delete", systemImage: "trash.fill")
                            }
                            .tint(.red)
                        }
                    }
                }
            } header: {
                Text("Sensitivities")
            } footer: {
                Text("Used for local label checks. Swipe left to delete. Changes save automatically. Results never indicate that a food is safe.")
            }
            .listRowBackground(AppColors.appCard)

            Section {
                Button {
                    showImportSource = true
                } label: {
                    Label("Import from lab report", systemImage: "doc.text.viewfinder")
                }
                .disabled(isImportingLabReport)
                .foregroundStyle(AppColors.calorie)
            } footer: {
                Text("Extracts possible sensitizations from an ISAC/ALEX-style allergy blood-test report for local checks only. Not a medical diagnosis — confirm every name before adding.")
            }
            .listRowBackground(AppColors.appCard)

            Section {
                TextField("Type an allergen", text: $value)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.done)
                    .onSubmit { addCurrentValue() }
            } footer: {
                Text("Press Return to add.")
            }
            .listRowBackground(AppColors.appCard)
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.appBackground)
        .navigationTitle("Allergen sensitivities")
        .navigationBarTitleDisplayMode(.inline)
        .overlay {
            if isImportingLabReport {
                ZStack {
                    Color.black.opacity(0.28).ignoresSafeArea()
                    ProgressView("Reading lab report…")
                        .padding(20)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
            }
        }
        .confirmationDialog("Import from lab report", isPresented: $showImportSource, titleVisibility: .visible) {
            Button("Choose Photo") { showPhotoPicker = true }
            Button("Choose File") { showFileImporter = true }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Pick a photo or PDF of your allergy blood-test report.")
        }
        .photosPicker(isPresented: $showPhotoPicker, selection: $selectedPhotoItem, matching: .images)
        .onChange(of: selectedPhotoItem) { _, item in
            guard let item else { return }
            selectedPhotoItem = nil
            Task { await importLabReport(from: item) }
        }
        .fileImporter(
            isPresented: $showFileImporter,
            allowedContentTypes: [.image, .pdf],
            allowsMultipleSelection: false,
            onCompletion: { result in
                Task { await importLabReport(from: result) }
            }
        )
        .sheet(isPresented: Binding(
            get: { pendingImportCandidates != nil },
            set: { if !$0 { clearPendingImport() } }
        )) {
            LabReportAllergenConfirmationSheet(
                candidates: pendingImportCandidates ?? [],
                selectedNames: $selectedImportNames,
                onCancel: { clearPendingImport() },
                onAdd: { selected in
                    mergeImportedAllergens(selected)
                    clearPendingImport()
                }
            )
        }
        .alert("Delete Allergen?", isPresented: Binding(
            get: { pendingDeletion != nil },
            set: { if !$0 { pendingDeletion = nil } }
        )) {
            Button("Cancel", role: .cancel) { pendingDeletion = nil }
            Button("Delete", role: .destructive) {
                if let pendingDeletion {
                    removeAllergen(pendingDeletion)
                }
                pendingDeletion = nil
            }
        } message: {
            Text("Remove \(pendingDeletion ?? "this allergen") from your sensitivities?")
        }
        .alert("Unable to Import", isPresented: Binding(
            get: { importErrorMessage != nil },
            set: { if !$0 { importErrorMessage = nil } }
        )) {
            Button("OK", role: .cancel) { importErrorMessage = nil }
        } message: {
            Text(importErrorMessage ?? "The lab report could not be read.")
        }
    }

    private func persist(_ next: [String]) {
        allergens = next
        onSave(next)
    }

    private func addCurrentValue() {
        let next = value.trimmingCharacters(in: .whitespacesAndNewlines)
        value = ""
        guard !next.isEmpty else { return }
        guard !allergens.contains(where: { $0.caseInsensitiveCompare(next) == .orderedSame }) else { return }
        persist(allergens + [next])
    }

    private func removeAllergen(_ allergen: String) {
        persist(allergens.filter { $0 != allergen })
    }

    private func mergeImportedAllergens(_ names: [String]) {
        var next = allergens
        var existing = Set(next.map { $0.lowercased() })
        for name in names {
            let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty, existing.insert(trimmed.lowercased()).inserted else { continue }
            next.append(trimmed)
        }
        persist(next)
    }

    @MainActor
    private func beginImport() {
        isImportingLabReport = true
        importErrorMessage = nil
    }

    @MainActor
    private func presentImportResults(_ names: [String]) {
        isImportingLabReport = false
        if names.isEmpty {
            importErrorMessage = "No clearly positive or elevated sensitizations were found in this report."
            return
        }
        selectedImportNames = Set(names)
        pendingImportCandidates = names
    }

    @MainActor
    private func failImport(_ error: Error) {
        isImportingLabReport = false
        importErrorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
    }

    private func clearPendingImport() {
        pendingImportCandidates = nil
        selectedImportNames = []
    }

    private func runLabReportImport(_ loadImages: () async throws -> [UIImage]) async {
        await MainActor.run { beginImport() }
        do {
            let images = try await loadImages()
            guard !images.isEmpty else { throw GeminiService.AnalysisError.imageConversionFailed }
            let names = try await GeminiService.extractAllergensFromLabReport(images: images)
            await MainActor.run { presentImportResults(names) }
        } catch {
            await MainActor.run { failImport(error) }
        }
    }

    private func importLabReport(from item: PhotosPickerItem) async {
        await runLabReportImport {
            guard let data = try await item.loadTransferable(type: Data.self),
                  let image = UIImage(data: data) else {
                throw GeminiService.AnalysisError.imageConversionFailed
            }
            return [image]
        }
    }

    private func importLabReport(from result: Result<[URL], Error>) async {
        let url: URL
        switch result {
        case .success(let urls):
            guard let first = urls.first else { return }
            url = first
        case .failure(let error):
            let nsError = error as NSError
            if nsError.domain == NSCocoaErrorDomain && nsError.code == NSUserCancelledError {
                return
            }
            await MainActor.run { failImport(error) }
            return
        }

        await runLabReportImport {
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
            let data = try Data(contentsOf: url, options: [.mappedIfSafe])
            return try labReportImages(from: data, url: url)
        }
    }

    private func labReportImages(from data: Data, url: URL) throws -> [UIImage] {
        let isPDF = url.pathExtension.lowercased() == "pdf"
            || (try? url.resourceValues(forKeys: [.contentTypeKey]).contentType?.conforms(to: .pdf)) == true
        if isPDF {
            return rasterizePDFPages(data: data, maxPages: 3)
        }
        guard let image = UIImage(data: data) else {
            throw GeminiService.AnalysisError.imageConversionFailed
        }
        return [image]
    }

    private func rasterizePDFPages(data: Data, maxPages: Int) -> [UIImage] {
        guard let document = PDFDocument(data: data), document.pageCount > 0 else { return [] }
        let scale: CGFloat = 2
        return (0..<min(document.pageCount, maxPages)).compactMap { index in
            guard let page = document.page(at: index) else { return nil }
            let bounds = page.bounds(for: .mediaBox)
            let size = CGSize(width: bounds.width * scale, height: bounds.height * scale)
            return UIGraphicsImageRenderer(size: size).image { context in
                UIColor.white.setFill()
                context.fill(CGRect(origin: .zero, size: size))
                context.cgContext.saveGState()
                context.cgContext.translateBy(x: 0, y: size.height)
                context.cgContext.scaleBy(x: scale, y: -scale)
                page.draw(with: .mediaBox, to: context.cgContext)
                context.cgContext.restoreGState()
            }
        }
    }
}

private struct LabReportAllergenConfirmationSheet: View {
    let candidates: [String]
    @Binding var selectedNames: Set<String>
    let onCancel: () -> Void
    let onAdd: ([String]) -> Void

    private var allSelected: Bool {
        !candidates.isEmpty && candidates.allSatisfy(selectedNames.contains)
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button(allSelected ? "Deselect All" : "Select All") {
                        selectedNames = allSelected ? [] : Set(candidates)
                    }
                    .foregroundStyle(AppColors.calorie)
                }
                .listRowBackground(AppColors.appCard)

                Section {
                    ForEach(candidates, id: \.self) { name in
                        Toggle(isOn: selectionBinding(for: name)) {
                            Text(name)
                        }
                        .tint(AppColors.calorie)
                    }
                } header: {
                    Text("Possible sensitizations")
                } footer: {
                    Text("Confirm which names to add. This is not a medical diagnosis and never means a food is safe.")
                }
                .listRowBackground(AppColors.appCard)
            }
            .scrollContentBackground(.hidden)
            .background(AppColors.appBackground)
            .navigationTitle("Confirm allergens")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add Selected") {
                        onAdd(candidates.filter(selectedNames.contains))
                    }
                    .disabled(selectedNames.isEmpty)
                    .fontWeight(.semibold)
                }
            }
        }
    }

    private func selectionBinding(for name: String) -> Binding<Bool> {
        Binding(
            get: { selectedNames.contains(name) },
            set: { isOn in
                if isOn {
                    selectedNames.insert(name)
                } else {
                    selectedNames.remove(name)
                }
            }
        )
    }
}
