import PhotosUI
import SwiftUI

/// One editable nutrient row of the medication form: the amount as typed, in the unit picked. It is converted
/// to the nutrient's canonical unit with `convert_amount` only when the form saves (docs/nutrients.md §8).
struct NutrientFormRow: Identifiable, Hashable {
    let id = UUID()
    var key: String
    var amountText: String
    /// "g" | "mg" | "mcg" | "iu".
    var unit: String
    /// IU form for vitamin A / E (`iu.forms` keys); nil otherwise.
    var form: String?

    init(key: String, amountText: String = "", unit: String? = nil, form: String? = nil) {
        self.key = key
        self.amountText = amountText
        self.unit = unit ?? NutrientCatalog.unit(key)
        self.form = form
    }

    /// A stored row, shown in its canonical unit.
    init(_ nutrient: DraftNutrient) {
        self.init(key: nutrient.key, amountText: NutrientFormRow.plain(nutrient.amountPerUnit))
    }

    static func plain(_ value: Double) -> String {
        value.formatted(.number.precision(.fractionLength(0...6)).grouping(.never).locale(Locale(identifier: "en_US_POSIX")))
    }

    var amount: Double? {
        let trimmed = amountText.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
        return Double(trimmed)
    }

    /// Units the picker offers: the mass units, plus IU where the reference allows it.
    static func units(for key: String) -> [String] {
        NutrientsReference.byKey[key]?.iu == nil ? ["g", "mg", "mcg"] : ["g", "mg", "mcg", "iu"]
    }

    static func unitTitle(_ unit: String) -> String { unit == "iu" ? "IU" : unit }

    /// Vitamin A / E need a form for IU (natural / synthetic, retinol / beta-carotene).
    var iuForms: [String] {
        guard unit == "iu" else { return [] }
        return (NutrientsReference.byKey[key]?.iu?.forms?.keys).map { Array($0).sorted() } ?? []
    }

    static func formTitle(_ form: String) -> String {
        switch form {
        case "natural": String(localized: "Natural (d-alpha)")
        case "synthetic": String(localized: "Synthetic (dl-alpha)")
        case "retinol": String(localized: "Retinol / retinyl")
        case "supplement_beta_carotene": String(localized: "Beta-carotene (supplement)")
        case "food_beta_carotene": String(localized: "Beta-carotene (food)")
        case "food_alpha_carotene_beta_cryptoxanthin": String(localized: "Alpha-carotene (food)")
        default: form
        }
    }

    func converted() -> NutrientsReference.Conversion {
        NutrientsReference.convertAmount(amount, unit: unit, key: key, form: form)
    }
}

extension NutrientsReference.Conversion {
    var message: String {
        switch error {
        case "invalid_amount": String(localized: "Enter an amount greater than 0.")
        case "unsupported_unit": String(localized: "Choose g, mg or mcg.")
        case "iu_not_supported": String(localized: "IU isn't used for this nutrient. Choose g, mg or mcg.")
        case "form_required", "unknown_form": String(localized: "Choose the form printed on the label.")
        case "unknown_nutrient": String(localized: "Choose a nutrient from the list.")
        default: String(localized: "Check this amount.")
        }
    }
}

/// State of "Get nutrients with AI". The form owns it and presents the photo picker and review sheet from its
/// root: a picker or sheet attached to a Form section is presented from a list row, which dismissed the whole
/// medication sheet when the row was recycled.
@MainActor
@Observable
final class SupplementNutrientReader {
    var photoItem: PhotosPickerItem?
    var showPhotoPicker = false
    var isReading = false
    var message: String?
    var review: ReviewPayload?

    struct ReviewPayload: Identifiable {
        let id = UUID()
        let result: NutrientsReference.LabelResult
    }

    func readPhoto(_ item: PhotosPickerItem, name: String, strength: String, doseUnit: DoseUnit) async {
        defer { photoItem = nil }
        guard let data = try? await item.loadTransferable(type: Data.self) else {
            message = String(localized: "The photo couldn't be opened.")
            return
        }
        await run(photo: data, name: name, strength: strength, doseUnit: doseUnit)
    }

    func run(photo: Data?, name: String, strength: String, doseUnit: DoseUnit) async {
        isReading = true
        message = nil
        defer { isReading = false }
        do {
            let result = try await SupplementLabelAI.read(photo: photo, name: name, strength: strength, doseUnit: doseUnit.rawValue)
            if !result.ok {
                message = String(localized: "The AI answer couldn't be read. Add the nutrients by hand.")
            } else if result.items.isEmpty && result.rejected.isEmpty {
                message = String(localized: "No amounts were found. Add the nutrients by hand.")
            } else {
                review = ReviewPayload(result: result)
            }
        } catch SupplementLabelAI.Failure.notConfigured {
            message = String(localized: "Set up AI in Settings to get nutrients from a label.")
        } catch is CancellationError {
            return
        } catch {
            message = String(localized: "The AI couldn't read this right now. Add the nutrients by hand.")
        }
    }

    /// Confirmed items replace rows of the same nutrient; the form still needs Save.
    static func merge(_ items: [NutrientsReference.LabelItem], into rows: inout [NutrientFormRow]) {
        for item in items {
            let row = NutrientFormRow(key: item.key, amountText: NutrientFormRow.plain(item.amount), unit: item.unit)
            if let index = rows.firstIndex(where: { $0.key == item.key }) {
                rows[index] = row
            } else {
                rows.append(row)
            }
        }
    }
}

extension View {
    /// Photo picker + review sheet of "Get nutrients with AI"; apply to the form's root, never to a section.
    func supplementNutrientReader(_ reader: SupplementNutrientReader, rows: Binding<[NutrientFormRow]>, doseUnit: DoseUnit,
                                  name: String, strength: String) -> some View {
        @Bindable var reader = reader
        return self
            .photosPicker(isPresented: $reader.showPhotoPicker, selection: $reader.photoItem, matching: .images)
            .onChange(of: reader.photoItem) { _, item in
                guard let item else { return }
                Task { await reader.readPhoto(item, name: name, strength: strength, doseUnit: doseUnit) }
            }
            .sheet(item: $reader.review) { payload in
                SupplementNutrientReviewSheet(result: payload.result, perUnit: MedicationFormatting.doseText(quantity: 1, unit: doseUnit)) { accepted in
                    SupplementNutrientReader.merge(accepted, into: &rows.wrappedValue)
                    reader.review = nil
                } onCancel: {
                    reader.review = nil
                }
            }
    }
}

/// "Nutrients (for supplements)" of the Add / Edit / Review form (docs/medications.md §21). Rows are amounts per
/// ONE dose unit; "Get nutrients with AI" fills a review sheet and never saves on its own.
struct MedicationNutrientsSection: View {
    @Binding var rows: [NutrientFormRow]
    let doseUnit: DoseUnit
    let name: String
    let strength: String
    var errors: [String: String] = [:]
    let reader: SupplementNutrientReader

    private var perUnit: String { MedicationFormatting.doseText(quantity: 1, unit: doseUnit) }

    var body: some View {
        Section {
            ForEach($rows) { $row in
                rowEditor($row)
            }
            .onDelete { rows.remove(atOffsets: $0) }
            Menu {
                // Every reference nutrient (app_tracked or not) plus the sports supplements, grouped.
                ForEach(NutrientCatalog.supplementSections, id: \.title) { section in
                    let keys = section.keys.filter { key in !rows.contains { $0.key == key } }
                    if !keys.isEmpty {
                        Section(section.title) {
                            ForEach(keys, id: \.self) { key in
                                Button(NutrientCatalog.labelTitle(key)) { rows.append(NutrientFormRow(key: key)) }
                                    .accessibilityIdentifier("medications.form.addNutrient.\(key)")
                            }
                        }
                    }
                }
            } label: {
                Label("Add nutrient", systemImage: "plus.circle")
            }
            .accessibilityIdentifier("medications.form.addNutrient")
            aiControls
        } header: {
            Text("Nutrients (for supplements)")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text("Amounts per \(perUnit). Doses you mark as taken add these to your nutrition, never to calories. Changing them also changes past doses.")
                if let message = reader.message {
                    Text(message).foregroundStyle(.orange)
                }
            }
        }
        .listRowBackground(AppColors.appCard)
    }

    @ViewBuilder
    private func rowEditor(_ row: Binding<NutrientFormRow>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: NutrientCatalog.iconName(row.wrappedValue.key))
                    .foregroundStyle(AyuvoPalette.nutrition)
                    .frame(width: 20)
                Text(NutrientCatalog.labelTitle(row.wrappedValue.key))
                    .font(.system(.body, design: .rounded))
                    .lineLimit(2)
                    .minimumScaleFactor(0.85)
                Spacer(minLength: 4)
                TextField("0", text: row.amountText)
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.trailing)
                    .frame(maxWidth: 90)
                    .accessibilityLabel(Text("\(NutrientCatalog.title(row.wrappedValue.key)) amount"))
                    .accessibilityIdentifier("medications.form.nutrient.\(row.wrappedValue.key).amount")
                Picker("", selection: row.unit) {
                    ForEach(NutrientFormRow.units(for: row.wrappedValue.key), id: \.self) { unit in
                        Text(NutrientFormRow.unitTitle(unit)).tag(unit)
                    }
                }
                .labelsHidden()
                .fixedSize()
                .accessibilityLabel(Text("Unit"))
                .accessibilityIdentifier("medications.form.nutrient.\(row.wrappedValue.key).unit")
            }
            if !row.wrappedValue.iuForms.isEmpty {
                Picker(selection: row.form) {
                    Text("Choose form").tag(String?.none)
                    ForEach(row.wrappedValue.iuForms, id: \.self) { form in
                        Text(NutrientFormRow.formTitle(form)).tag(String?.some(form))
                    }
                } label: {
                    Text("Form").font(.system(.subheadline, design: .rounded))
                }
            }
            Text(caption(row.wrappedValue))
                .font(.system(.caption, design: .rounded))
                .foregroundStyle(errors[row.wrappedValue.key] == nil ? Color.secondary : Color.orange)
        }
        .padding(.vertical, 2)
    }

    private func caption(_ row: NutrientFormRow) -> String {
        if let error = errors[row.key] { return error }
        let converted = row.converted()
        guard converted.ok, row.unit != NutrientCatalog.unit(row.key) else { return String(localized: "per \(perUnit)") }
        return String(localized: "per \(perUnit) · stored as \(NutrientCatalog.text(converted.amount, key: row.key))")
    }

    // MARK: AI

    @ViewBuilder
    private var aiControls: some View {
        let photoRoute = SupplementAIRoute.current(photo: true)
        let textRoute = SupplementAIRoute.current(photo: false)
        if photoRoute == nil && textRoute == nil {
            Label("Set up AI in Settings to get nutrients from a label.", systemImage: "sparkles")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("medications.form.aiSetup")
        } else {
            Menu {
                Button {
                    reader.showPhotoPicker = true
                } label: {
                    Label(photoRoute == nil ? String(localized: "From a label photo (set up an image model in Settings)") : String(localized: "From a label photo"),
                          systemImage: "camera.viewfinder")
                }
                .disabled(photoRoute == nil)
                Button {
                    Task { await reader.run(photo: nil, name: name, strength: strength, doseUnit: doseUnit) }
                } label: {
                    Label(textRoute == nil ? String(localized: "From name and strength (set up a text model in Settings)") : String(localized: "From name and strength"),
                          systemImage: "text.magnifyingglass")
                }
                .disabled(textRoute == nil || (name.trimmingCharacters(in: .whitespaces).isEmpty && strength.trimmingCharacters(in: .whitespaces).isEmpty))
            } label: {
                HStack {
                    Label("Get nutrients with AI", systemImage: "sparkles")
                    if reader.isReading {
                        Spacer()
                        ProgressView()
                    }
                }
            }
            .disabled(reader.isReading)
            .accessibilityIdentifier("medications.form.aiNutrients")
        }
    }
}

/// Review of what the model read: every item can be edited or removed; nothing is kept until "Use these".
struct SupplementNutrientReviewSheet: View {
    let perUnit: String
    let rejected: [NutrientsReference.LabelRejection]
    let onConfirm: ([NutrientsReference.LabelItem]) -> Void
    let onCancel: () -> Void
    @State private var items: [NutrientsReference.LabelItem]
    @State private var texts: [String: String]

    init(result: NutrientsReference.LabelResult, perUnit: String, onConfirm: @escaping ([NutrientsReference.LabelItem]) -> Void,
         onCancel: @escaping () -> Void) {
        self.perUnit = perUnit
        self.rejected = result.rejected
        self.onConfirm = onConfirm
        self.onCancel = onCancel
        _items = State(initialValue: result.items)
        _texts = State(initialValue: Dictionary(uniqueKeysWithValues: result.items.map { ($0.key, NutrientFormRow.plain($0.amount)) }))
    }

    private var confirmed: [NutrientsReference.LabelItem] {
        items.compactMap { item in
            guard let text = texts[item.key], let value = Double(text.replacingOccurrences(of: ",", with: ".")), value > 0 else { return nil }
            var copy = item
            copy.amount = value
            return copy
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(items, id: \.key) { item in
                        HStack {
                            Text(NutrientCatalog.labelTitle(item.key))
                            Spacer()
                            TextField("0", text: Binding(get: { texts[item.key] ?? "" }, set: { texts[item.key] = $0 }))
                                .keyboardType(.decimalPad)
                                .multilineTextAlignment(.trailing)
                                .frame(maxWidth: 100)
                            Text(item.unit).foregroundStyle(.secondary)
                        }
                    }
                    .onDelete { offsets in items.remove(atOffsets: offsets) }
                } header: {
                    Text("Per \(perUnit)")
                } footer: {
                    Text("Check every amount against the label. Swipe to remove one. Nothing is saved until you save the medication.")
                }
                if !rejected.isEmpty {
                    Section {
                        Text(rejected.count == 1
                             ? String(localized: "1 item wasn't recognised. Add it by hand if it's on the label.")
                             : String(localized: "\(rejected.count) items weren't recognised. Add them by hand if they're on the label."))
                            .font(.system(.subheadline, design: .rounded))
                            .foregroundStyle(.secondary)
                    } header: {
                        Text("Not recognised")
                    }
                }
            }
            .navigationTitle("Review nutrients")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Use these") { onConfirm(confirmed) }
                        .disabled(confirmed.isEmpty)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
