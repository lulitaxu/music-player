import SwiftUI

/// A restrained built-in player visualizer. It is intentionally visual-only
/// until the playback engine exposes audio level data.
public struct VisualizerView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var animate = false

    public init() {}

    public var body: some View {
        HStack(alignment: .center, spacing: 4) {
            ForEach(0..<18, id: \.self) { index in
                Capsule()
                    .fill(AppColors.accent.opacity(0.75))
                    .frame(width: 4, height: barHeight(index))
                    .animation(
                        reduceMotion ? nil : .easeInOut(duration: 0.45)
                            .repeatForever(autoreverses: true)
                            .delay(Double(index) * 0.025),
                        value: animate
                    )
            }
        }
        .frame(height: 52)
        .onAppear { animate = true }
    }

    private func barHeight(_ index: Int) -> CGFloat {
        let base = CGFloat((index * 7) % 25 + 10)
        return reduceMotion || !animate ? base : base + CGFloat((index % 4) * 8)
    }
}
