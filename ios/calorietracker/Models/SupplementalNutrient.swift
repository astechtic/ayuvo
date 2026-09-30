import Foundation

enum SupplementalNutrient: String, CaseIterable, Identifiable, Codable {
    case creatine
    case betaAlanine
    case lCitrulline
    case lCarnitine
    case lArginine
    case taurine
    case betaine
    case hmb

    var id: String { rawValue }

    var jsonKey: String {
        switch self {
        case .creatine: "creatine"
        case .betaAlanine: "beta_alanine"
        case .lCitrulline: "l_citrulline"
        case .lCarnitine: "l_carnitine"
        case .lArginine: "l_arginine"
        case .taurine: "taurine"
        case .betaine: "betaine"
        case .hmb: "hmb"
        }
    }

    var displayName: String {
        switch self {
        case .creatine: String(localized: "Creatine", comment: "Sports supplement nutrient name")
        case .betaAlanine: String(localized: "Beta-Alanine", comment: "Sports supplement nutrient name")
        case .lCitrulline: String(localized: "L-Citrulline", comment: "Sports supplement nutrient name")
        case .lCarnitine: String(localized: "L-Carnitine", comment: "Sports supplement nutrient name")
        case .lArginine: String(localized: "L-Arginine", comment: "Sports supplement nutrient name")
        case .taurine: String(localized: "Taurine", comment: "Sports supplement nutrient name")
        case .betaine: String(localized: "Betaine", comment: "Sports supplement nutrient name")
        case .hmb: String(localized: "HMB", comment: "Sports supplement nutrient name")
        }
    }

    var optionalNutrient: OptionalNutrient {
        OptionalNutrient(rawValue: rawValue)!
    }
}
