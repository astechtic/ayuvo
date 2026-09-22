import Testing
import UIKit
@testable import calorietracker

/// Every SF Symbol the Medications UI maps from its enums must exist on the running OS; a missing
/// name renders as an empty icon (the Form menu's "Cream" row showed none with "tube.fill").
struct MedicationSymbolTests {
    @Test(arguments: MedicationForm.allCases)
    func formSymbolExists(_ form: MedicationForm) {
        #expect(UIImage(systemName: form.systemImage) != nil, "\(form.rawValue) uses missing symbol \(form.systemImage)")
    }

    @Test func formSymbolsAreDistinct() {
        let symbols = MedicationForm.allCases.map(\.systemImage)
        #expect(Set(symbols).count == symbols.count)
    }

    @Test(arguments: MedicationStatus.allCases)
    func statusSymbolExists(_ status: MedicationStatus) {
        #expect(UIImage(systemName: status.systemImage) != nil, "\(status.rawValue) uses missing symbol \(status.systemImage)")
    }

    @Test(arguments: DoseStatus.allCases)
    func doseStatusSymbolExists(_ status: DoseStatus) {
        #expect(UIImage(systemName: status.systemImage) != nil, "\(status.rawValue) uses missing symbol \(status.systemImage)")
    }
}
