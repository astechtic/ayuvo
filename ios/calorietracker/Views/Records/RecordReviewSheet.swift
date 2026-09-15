import SwiftUI

/// "We found these details" (plan §2): uncertain, key or conflicting fields with Confirm all,
/// per-field Confirm / Edit / Ignore, Add information and Review later.
struct RecordReviewSheet: View {
    let recordID: String
    var onOpenSource: ((RecordField) -> Void)?
    @Environment(RecordsStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var detail: RecordDetail?
    @State private var editing: RecordField?
    @State private var editText = ""
    @State private var showAdd = false
    @State private var addKey: RecordFieldKey = .doctorName
    @State private var addValue = ""
    @State private var showSplit = false
    @State private var duplicatePrompt: RecordDuplicatePrompt?

    var body: some View {
        NavigationStack {
            Group {
                if let detail {
                    content(detail)
                } else {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .background(AppColors.appBackground)
            .navigationTitle("We found these details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Review later") { dismiss() }
                        .accessibilityIdentifier("records.review.later")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Confirm all") {
                        Task {
                            await store.confirmAll(recordID: recordID)
                            dismiss()
                        }
                    }
                    .fontWeight(.semibold)
                    .accessibilityIdentifier("records.review.confirmAll")
                }
            }
        }
        .task(id: store.revision) { detail = await store.detail(id: recordID) }
        .alert("Edit \(editing?.key.title ?? "")", isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })) {
            TextField("Value", text: $editText)
            Button("Save") {
                guard let field = editing else { return }
                let value = editText
                Task { await store.setFieldState(field, state: .user, editedValue: value) }
                editing = nil
            }
            Button("Cancel", role: .cancel) { editing = nil }
        } message: {
            if let field = editing, field.key.isDate {
                Text("Use the format yyyy-MM-dd, for example 2026-09-12.")
            }
        }
        .sheet(isPresented: $showAdd) { addSheet }
        .fullScreenCover(isPresented: $showSplit) {
            RecordSplitReviewView(recordID: recordID)
        }
        .sheet(item: $duplicatePrompt) { prompt in
            RecordDuplicateSheet(prompt: prompt)
        }
        .presentationDetents([.large])
    }

    @ViewBuilder
    private func content(_ detail: RecordDetail) -> some View {
        let reviewFields = RecordReviewEvaluator.reviewFields(detail.fields)
        let conflicts = RecordReviewEvaluator.conflicts(detail.fields)
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    RecordThumbnailView(record: detail.record)
                        .frame(width: 52, height: 52)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(detail.record.title)
                            .font(.system(.headline, design: .rounded))
                            .lineLimit(2)
                        HStack(spacing: 6) {
                            RecordTypePill(type: detail.record.recordType)
                            Text(RecordFormatting.dateText(detail.record))
                                .font(.system(.caption, design: .rounded))
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                if let failure = RecordStatusText.failure(detail.record.processingError), detail.record.processingStatus == .failedPartial {
                    Label(failure, systemImage: "info.circle")
                        .font(.system(.footnote, design: .rounded))
                        .foregroundStyle(.secondary)
                }
                if reviewFields.isEmpty && detail.fields.isEmpty {
                    Text("Some details couldn't be extracted. You can add them manually.")
                        .font(.system(.subheadline, design: .rounded))
                        .foregroundStyle(.secondary)
                }

                if detail.splitProposal?.status == .pending, let proposal = detail.splitProposal {
                    actionCard(
                        title: String(localized: "This PDF may contain \(proposal.segments.count) records"),
                        systemImage: "rectangle.split.3x1",
                        button: String(localized: "Review boundaries")
                    ) { showSplit = true }
                }
                if let candidate = detail.duplicates.first {
                    actionCard(title: String(localized: "This looks like an existing record"), systemImage: "doc.on.doc", button: String(localized: "Compare")) {
                        Task { duplicatePrompt = await store.duplicatePrompt(for: candidate) }
                    }
                }
                if detail.record.recordType == .other || (detail.record.typeConfidence ?? 1) < 0.7 && detail.record.typeMethod == .rules {
                    typeCard(detail.record)
                }
                if detail.record.documentDate == nil {
                    RecordsCard {
                        Label("No date found", systemImage: "calendar.badge.exclamationmark")
                            .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        Button("Add the report date") {
                            addKey = .reportDate
                            addValue = RecordDates.dayString(from: Date())
                            showAdd = true
                        }
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                    }
                }

                if !conflicts.isEmpty {
                    Text("Different values found")
                        .font(.system(.caption, design: .rounded, weight: .bold))
                        .foregroundStyle(.secondary)
                        .textCase(.uppercase)
                }
                ForEach(conflicts.keys.sorted(), id: \.self) { key in
                    RecordsCard {
                        Text(RecordFieldKey(rawValue: String(key.split(separator: ":")[0]))?.title ?? key).font(.system(.subheadline, design: .rounded, weight: .semibold))
                        HStack(alignment: .top, spacing: 10) {
                            ForEach(conflicts[key] ?? []) { field in
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(field.displayValue)
                                        .font(.system(.body, design: .rounded))
                                    RecordMethodBadge(method: field.method)
                                    Button(field.state == .suggested ? "Use this" : "Chosen") {
                                        Task {
                                            await store.setFieldState(field, state: .confirmed)
                                            for other in (conflicts[key] ?? []) where other.id != field.id && other.state == .suggested {
                                                await store.setFieldState(other, state: .rejected)
                                            }
                                        }
                                    }
                                    .buttonStyle(.bordered)
                                    .disabled(field.state != .suggested)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(10)
                                .background(AppColors.appBackground, in: RoundedRectangle(cornerRadius: 12))
                            }
                        }
                    }
                }

                if !reviewFields.isEmpty {
                    Text("Please check")
                        .font(.system(.caption, design: .rounded, weight: .bold))
                        .foregroundStyle(.secondary)
                        .textCase(.uppercase)
                }
                ForEach(reviewFields.filter { field in !conflicts.values.contains { $0.contains { $0.id == field.id } } }) { field in
                    fieldCard(field)
                }

                Button {
                    addKey = .doctorName
                    addValue = ""
                    showAdd = true
                } label: {
                    Label("Add information", systemImage: "plus.circle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .tint(AppColors.calorie)
                .accessibilityIdentifier("records.review.add")
            }
            .padding()
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("records.reviewSheet")
    }

    private func actionCard(title: String, systemImage: String, button: String, action: @escaping () -> Void) -> some View {
        RecordsCard {
            Label(title, systemImage: systemImage)
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
            Button(button, action: action)
                .buttonStyle(.borderedProminent)
                .tint(AppColors.calorie)
        }
    }

    private func typeCard(_ record: HealthRecord) -> some View {
        RecordsCard {
            Label("What kind of record is this?", systemImage: "questionmark.folder")
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
            Menu {
                ForEach(RecordType.allCases) { type in
                    Button(type.title) {
                        Task {
                            await store.update(id: record.id, patch: RecordPatch(recordType: type, category: type.defaultCategory))
                            await store.reevaluateReview(recordID: record.id)
                        }
                    }
                }
            } label: {
                HStack {
                    Text(record.recordType.title)
                    Image(systemName: "chevron.up.chevron.down")
                }
                .font(.system(.subheadline, design: .rounded, weight: .semibold))
            }
        }
    }

    private func fieldCard(_ field: RecordField) -> some View {
        RecordsCard {
            HStack(alignment: .firstTextBaseline) {
                Text(field.key.title)
                    .font(.system(.caption, design: .rounded, weight: .semibold))
                    .foregroundStyle(.secondary)
                Spacer()
                RecordMethodBadge(method: field.method)
            }
            Text(field.displayValue)
                .font(.system(.body, design: .rounded, weight: .medium))
                .accessibilityIdentifier("records.review.value.\(field.key.rawValue)")
            if let page = field.sourcePage {
                Button {
                    onOpenSource?(field)
                } label: {
                    Label("Page \(page + 1)", systemImage: "doc.text.magnifyingglass")
                        .font(.system(.caption, design: .rounded, weight: .semibold))
                }
                .disabled(onOpenSource == nil)
            }
            HStack(spacing: 8) {
                Button("Confirm") { Task { await store.setFieldState(field, state: .confirmed) } }
                    .buttonStyle(.borderedProminent)
                    .tint(AppColors.calorie)
                    .accessibilityIdentifier("records.review.confirm.\(field.key.rawValue)")
                Button("Edit") {
                    editText = field.testResult?.value ?? field.valueText
                    editing = field
                }
                .buttonStyle(.bordered)
                Button("Ignore", role: .destructive) { Task { await store.setFieldState(field, state: .rejected) } }
                    .buttonStyle(.bordered)
            }
            .controlSize(.small)
        }
    }

    private var addSheet: some View {
        NavigationStack {
            Form {
                Picker("Detail", selection: $addKey) {
                    ForEach(RecordFieldKey.allCases.filter { $0 != .testResult && $0 != .location }) { key in
                        Text(key.title).tag(key)
                    }
                }
                if addKey.isDate {
                    DatePicker("Date", selection: Binding(
                        get: { RecordDates.date(fromDay: addValue) ?? Date() },
                        set: { addValue = RecordDates.dayString(from: $0) }
                    ), displayedComponents: .date)
                } else {
                    TextField("Value", text: $addValue)
                }
            }
            .navigationTitle("Add information")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { showAdd = false } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        let key = addKey
                        let value = addKey.isDate && addValue.isEmpty ? RecordDates.dayString(from: Date()) : addValue
                        Task { await store.addField(recordID: recordID, key: key, value: value) }
                        showAdd = false
                    }
                    .disabled(!addKey.isDate && addValue.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
    }
}
