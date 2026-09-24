import SwiftUI

/// One gallery entry, already resolved into this device's language.
struct GalleryPrompt: Identifiable, Hashable {
    let id: String
    let title: String
    let prompt: String
}

/// The Coach prompt gallery (docs/coach.md §9): every predefined prompt, by category, for the
/// sources this device can actually answer from.
///
/// Tapping a card **prefills** the composer rather than sending it, so the user can edit it first —
/// the same hand-off the Records screens use (docs/health-records.md §27).
struct PromptGalleryView: View {
    /// Category label and its entries, in catalog order.
    let sections: [(String, [GalleryPrompt])]
    var onPick: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var shown: [(String, [GalleryPrompt])] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !needle.isEmpty else { return sections }
        return sections.compactMap { label, entries in
            let hits = entries.filter {
                $0.title.lowercased().contains(needle) || $0.prompt.lowercased().contains(needle)
            }
            return hits.isEmpty ? nil : (label, hits)
        }
    }

    var body: some View {
        NavigationStack {
            Group {
                if shown.isEmpty {
                    emptyState
                } else {
                    list
                }
            }
            .background(AppColors.appBackground)
            .navigationTitle("Prompt gallery")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, prompt: Text("Search prompts"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
        }
        .accessibilityIdentifier("coach.prompts")
    }

    private var list: some View {
        List {
            ForEach(shown, id: \.0) { label, entries in
                Section {
                    ForEach(entries) { entry in
                        Button {
                            onPick(entry.prompt)
                            dismiss()
                        } label: {
                            HStack(alignment: .top, spacing: 10) {
                                Image(systemName: "arrow.up.right")
                                    .font(.system(size: 11, weight: .bold))
                                    .foregroundStyle(AppColors.calorie)
                                    .padding(.top, 3)
                                    .accessibilityHidden(true)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(entry.title)
                                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                                        .foregroundStyle(.primary)
                                    Text(entry.prompt)
                                        .font(.system(.caption, design: .rounded))
                                        .foregroundStyle(.secondary)
                                        .lineLimit(2)
                                }
                                Spacer(minLength: 0)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(AppColors.appCard)
                        .accessibilityIdentifier("coach.prompt.\(entry.id)")
                    }
                } header: {
                    Text(label)
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: query.isEmpty ? "sparkles" : "magnifyingglass")
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(AppColors.calorie.opacity(0.7))
            Text(query.isEmpty ? "Nothing to ask about yet" : "No prompts match that")
                .font(.system(.headline, design: .rounded))
            Text(query.isEmpty
                 ? "Turn a data source on in the composer, or log something, and prompts will appear here."
                 : "Try a different word.")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
