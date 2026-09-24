import SwiftUI

/// The Coach conversation list (docs/coach.md §7): search, open, rename, duplicate and delete.
///
/// "New chat" adds a conversation rather than clearing one — the old Reset button destroyed the only
/// history there was, which is what this whole screen exists to fix.
struct ConversationListView: View {
    @Environment(CoachStore.self) private var chatStore
    @Environment(\.dismiss) private var dismiss

    @State private var query = ""
    @State private var searchResults: [ConversationSummary]?
    @State private var renaming: ConversationSummary?
    @State private var renameText = ""
    @State private var pendingDelete: ConversationSummary?
    @State private var shareURL: URL?
    @State private var exportError: String?

    private var rows: [ConversationSummary] { searchResults ?? chatStore.conversations }

    var body: some View {
        NavigationStack {
            Group {
                if rows.isEmpty {
                    emptyState
                } else {
                    list
                }
            }
            .background(AppColors.appBackground)
            .navigationTitle("Chats")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, prompt: Text("Search chats"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        Task {
                            await chatStore.startNewConversation()
                            dismiss()
                        }
                    } label: {
                        Label("New chat", systemImage: "square.and.pencil")
                    }
                    .accessibilityIdentifier("coach.newChat")
                }
            }
            .task(id: query) { await runSearch() }
            .alert("Rename chat", isPresented: Binding(
                get: { renaming != nil },
                set: { if !$0 { renaming = nil } }
            )) {
                TextField(String(localized: "Name"), text: $renameText)
                Button("Cancel", role: .cancel) { renaming = nil }
                Button("Save") {
                    if let target = renaming {
                        Task { await chatStore.rename(conversationID: target.id, to: renameText) }
                    }
                    renaming = nil
                }
            }
            .confirmationDialog(
                Text("Delete this chat?"),
                isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
                titleVisibility: .visible
            ) {
                Button("Delete", role: .destructive) {
                    if let target = pendingDelete {
                        Task {
                            await chatStore.delete(conversationID: target.id)
                            await runSearch()
                        }
                    }
                    pendingDelete = nil
                }
                Button("Cancel", role: .cancel) { pendingDelete = nil }
            } message: {
                Text("The messages and any files you attached are deleted from this device.")
            }
        }
        .sheet(isPresented: Binding(get: { shareURL != nil }, set: { if !$0 { shareURL = nil } })) {
            if let shareURL {
                CoachShareSheet(items: [shareURL])
            }
        }
        .alert("Export failed", isPresented: Binding(
            get: { exportError != nil },
            set: { if !$0 { exportError = nil } }
        )) {
            Button("OK", role: .cancel) { exportError = nil }
        } message: {
            Text(exportError ?? "")
        }
        .accessibilityIdentifier("coach.conversationList")
    }

    /// §10: a readable transcript, or the archive shape so it imports like a backup.
    private func export(_ id: String, _ format: CoachExportFormat) async {
        guard let file = await chatStore.export(conversationID: id, format: format),
              let url = file.writeToTemporaryFile()
        else {
            exportError = String(localized: "That chat could not be exported.")
            return
        }
        shareURL = url
    }

    private var list: some View {
        List {
            ForEach(rows) { row in
                Button {
                    Task {
                        await chatStore.select(conversationID: row.id)
                        dismiss()
                    }
                } label: {
                    ConversationRow(summary: row, isCurrent: row.id == chatStore.current?.id)
                }
                .buttonStyle(.plain)
                .listRowBackground(AppColors.appCard)
                .accessibilityIdentifier("coach.conversation.\(row.id)")
                .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                    Button(role: .destructive) {
                        pendingDelete = row
                    } label: {
                        Label("Delete", systemImage: "trash")
                    }
                    .accessibilityIdentifier("coach.conversation.delete")

                    Button {
                        renameText = row.conversation.title
                        renaming = row
                    } label: {
                        Label("Rename", systemImage: "pencil")
                    }
                    .tint(AppColors.calorie)
                    .accessibilityIdentifier("coach.conversation.rename")
                }
                .contextMenu {
                    Button {
                        renameText = row.conversation.title
                        renaming = row
                    } label: {
                        Label("Rename", systemImage: "pencil")
                    }
                    Button {
                        Task {
                            await chatStore.duplicate(conversationID: row.id)
                            dismiss()
                        }
                    } label: {
                        Label("Start again from this chat", systemImage: "arrow.triangle.branch")
                    }
                    .accessibilityIdentifier("coach.conversation.duplicate")
                    Menu {
                        ForEach(CoachExportFormat.allCases, id: \.self) { format in
                            Button {
                                Task { await export(row.id, format) }
                            } label: {
                                Label(format.title, systemImage: format.systemImage)
                            }
                        }
                    } label: {
                        Label("Export", systemImage: "square.and.arrow.up")
                    }
                    .accessibilityIdentifier("coach.conversation.export")
                    Button {
                        Task { await chatStore.setPinned(conversationID: row.id, pinned: !row.conversation.pinned) }
                    } label: {
                        Label(row.conversation.pinned ? "Unpin" : "Pin",
                              systemImage: row.conversation.pinned ? "pin.slash" : "pin")
                    }
                    Divider()
                    Button(role: .destructive) { pendingDelete = row } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: query.isEmpty ? "bubble.left.and.bubble.right" : "magnifyingglass")
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(AppColors.calorie.opacity(0.7))
            Text(query.isEmpty ? "No chats yet" : "No chats match that")
                .font(.system(.headline, design: .rounded))
            Text(query.isEmpty
                 ? "Ask Coach something and it will show up here."
                 : "Try a different word.")
                .font(.system(.subheadline, design: .rounded))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, 40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func runSearch() async {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            searchResults = nil
            await chatStore.reloadConversations()
            return
        }
        searchResults = await chatStore.search(trimmed)
    }
}

private struct ConversationRow: View {
    let summary: ConversationSummary
    var isCurrent: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    if summary.conversation.pinned {
                        Image(systemName: "pin.fill")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(AppColors.calorie)
                            .accessibilityHidden(true)
                    }
                    Text(summary.conversation.displayTitle)
                        .font(.system(.subheadline, design: .rounded, weight: .semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                }
                if !summary.snippet.isEmpty {
                    Text(summary.snippet)
                        .font(.system(.caption, design: .rounded))
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                HStack(spacing: 8) {
                    Text(relativeTime)
                    if summary.attachmentCount > 0 {
                        Label("\(summary.attachmentCount)", systemImage: "paperclip")
                    }
                }
                .font(.system(.caption2, design: .rounded))
                .foregroundStyle(.tertiary)
            }
            Spacer(minLength: 0)
            if isCurrent {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(AppColors.calorie)
                    .accessibilityLabel(Text("Open"))
            }
        }
        .padding(.vertical, 5)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private var relativeTime: String {
        let ms = summary.conversation.lastMessageMs ?? summary.conversation.updatedMs
        let date = Date(timeIntervalSince1970: TimeInterval(ms) / 1000)
        return date.formatted(.relative(presentation: .named))
    }
}
