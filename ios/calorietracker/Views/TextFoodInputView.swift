import SwiftUI

struct TextFoodInputView: View {
    @State private var foodDescription = ""
    @State private var placeholderIndex = 0
    @FocusState private var isFocused: Bool

    var onCancel: () -> Void
    var onSubmit: (String) -> Void

    var placeholders = [
        "2 eggs, toast with butter and a coffee",
        "Chipotle burrito bowl with chicken and rice",
        "Domino's pepperoni pizza, 2 slices",
        "Greek yogurt with granola and blueberries",
    ]

    /// Lines the field always occupies. A fixed height means neither the rotating placeholder
    /// nor typing can resize the popover, which UIKit answers by re-positioning it (the flicker).
    private static let fieldLines = 3

    var body: some View {
        form
            .padding(20)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .onAppear { isFocused = true }
        // A task-owned loop: unlike a `Timer.publish` stored on the struct, it is not recreated
        // (and restarted) every time the parent diary re-renders.
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2.5))
                guard !Task.isCancelled, foodDescription.isEmpty else { continue }
                placeholderIndex = (placeholderIndex + 1) % max(placeholders.count, 1)
            }
        }
    }

    private var form: some View {
        VStack(spacing: 20) {
            ZStack(alignment: .topLeading) {
                // One Text whose content cross-fades in place: no insertion/removal transitions
                // and no layout change, only opacity.
                Text(placeholders.isEmpty ? "" : placeholders[placeholderIndex % placeholders.count])
                    .foregroundStyle(.tertiary)
                    .font(.body)
                    .lineLimit(Self.fieldLines, reservesSpace: true)
                    .frame(maxWidth: .infinity, alignment: .topLeading)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 10)
                    .contentTransition(.opacity)
                    .animation(.easeInOut(duration: 0.3), value: placeholderIndex)
                    .opacity(foodDescription.isEmpty ? 1 : 0)
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)

                TextField("", text: $foodDescription, axis: .vertical)
                    .font(.body)
                    .lineLimit(Self.fieldLines, reservesSpace: true)
                    .textFieldStyle(.plain)
                    .autocorrectionDisabled()
                    .focused($isFocused)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 10)
                    .accessibilityLabel(Text("Food description"))
            }
            .padding(12)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color(.quaternarySystemFill))
            )

            Button {
                onSubmit(foodDescription)
            } label: {
                Text("Analyze")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(AppColors.calorie)
            .controlSize(.large)
            .disabled(foodDescription.trimmingCharacters(in: .whitespaces).isEmpty)

            Button("Cancel") {
                onCancel()
            }
            .foregroundStyle(.secondary)
        }
        .transaction { $0.animation = nil }
    }
}
