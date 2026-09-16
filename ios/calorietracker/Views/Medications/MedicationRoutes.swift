import SwiftUI

extension View {
    /// Attaches the Medications destinations to a `NavigationStack` (the Health tab's stack and,
    /// for "Add to Medications" from a record, the Records stack).
    func medicationRouteDestinations() -> some View {
        navigationDestination(for: MedicationRoute.self) { route in
            switch route {
            case .detail(let id):
                MedicationDetailView(medicationID: id)
            case .history(let medicationID):
                MedicationHistoryView(medicationID: medicationID)
            case .importFromRecord(let recordID):
                MedicationImportFromRecordView(recordID: recordID)
            }
        }
    }
}
