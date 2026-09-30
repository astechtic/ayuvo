import Foundation

/// Translated display text for the shared JSON contracts (docs/localization.md).
///
/// The contracts stay English. `scripts/l10n/l10n_contracts.py` gives every display
/// field a stable key such as `derived.metric.<id>.title` in `Contracts.xcstrings`;
/// a key without a translation falls back to the English from the contract.
nonisolated enum ContractText {
    static func text(_ key: String, _ fallback: String) -> String {
        Bundle.main.localizedString(forKey: key, value: fallback, table: "Contracts")
    }
}
