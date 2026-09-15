import Charts
import SwiftUI
import UIKit

/// Horizontal scrub gesture for a Swift Chart: finds the plotted point nearest the finger,
/// publishes it through `selected`, hapticly ticks on change, and floats `label` above the
/// plot clamped inside the plot frame. Extracted from the Weight / Body Fat sections so
/// the Health detail chart shares one implementation.
struct ChartScrubOverlay<Point: Equatable, Label: View>: View {
    let proxy: ChartProxy
    let points: [Point]
    let date: (Point) -> Date
    @Binding var selected: Point?
    private let label: (Point) -> Label

    init(
        proxy: ChartProxy,
        points: [Point],
        date: @escaping (Point) -> Date,
        selected: Binding<Point?>,
        @ViewBuilder label: @escaping (Point) -> Label
    ) {
        self.proxy = proxy
        self.points = points
        self.date = date
        self._selected = selected
        self.label = label
    }

    var body: some View {
        GeometryReader { geometry in
            let plotFrame = proxy.plotFrame.map { geometry[$0] }

            ZStack(alignment: .topLeading) {
                Color.clear
                    .contentShape(Rectangle())
                    .simultaneousGesture(
                        DragGesture(minimumDistance: 6)
                            .onChanged { value in
                                guard abs(value.translation.width) > abs(value.translation.height),
                                      let plotFrame else { return }
                                inspect(at: value.location.x - plotFrame.minX)
                            }
                            .onEnded { _ in
                                withAnimation(.easeOut(duration: 0.16)) {
                                    selected = nil
                                }
                            }
                    )

                if let selected,
                   let plotFrame,
                   let pointX = proxy.position(forX: date(selected)) {
                    label(selected)
                        .fixedSize()
                        .position(
                            x: min(max(plotFrame.minX + pointX, plotFrame.minX + 56), plotFrame.maxX - 56),
                            y: plotFrame.minY + 26
                        )
                        .transition(.opacity.combined(with: .scale(scale: 0.94)))
                }
            }
        }
    }

    private func inspect(at plotX: CGFloat) {
        guard let target: Date = proxy.value(atX: plotX), !points.isEmpty else { return }
        let nearest = points.min {
            abs(date($0).timeIntervalSince(target)) < abs(date($1).timeIntervalSince(target))
        }
        guard let nearest, nearest != selected else { return }
        selected = nearest
        UISelectionFeedbackGenerator().selectionChanged()
    }
}
