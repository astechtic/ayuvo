import Foundation

/// User-facing text for the Partner Health Sync status model (docs/partner-sync.md §14) and package / pairing
/// errors. Copy rule: "stored on this device", never "never leaves your device".
nonisolated enum PartnerStatusText {
    static func status(_ status: String) -> String {
        switch status {
        case "pairing_required": String(localized: "partner.status.pairing_required", defaultValue: "Pair again to sync", comment: "Partner sync status")
        case "waiting_for_network": String(localized: "partner.status.waiting_for_network", defaultValue: "Waiting for Wi-Fi", comment: "Partner sync status: no Wi-Fi or hotspot")
        case "connecting": String(localized: "partner.status.connecting", defaultValue: "Looking for partner…", comment: "Partner sync status")
        case "syncing": String(localized: "partner.status.syncing", defaultValue: "Syncing…", comment: "Partner sync status")
        case "up_to_date": String(localized: "partner.status.up_to_date", defaultValue: "Up to date", comment: "Partner sync status")
        case "partner_unavailable": String(localized: "partner.status.partner_unavailable", defaultValue: "Partner not nearby", comment: "Partner sync status: the other phone was not found on this network")
        case "local_network_denied": String(localized: "partner.status.local_network_denied", defaultValue: "Allow Local Network access in Settings to sync", comment: "Partner sync status: iOS Local Network permission is off")
        case "not_trusted": String(localized: "partner.status.not_trusted", defaultValue: "Partner no longer trusts this phone", comment: "Partner sync status: the partner unpaired or changed keys")
        default: String(localized: "partner.status.sync_failed", defaultValue: "Sync didn't finish", comment: "Partner sync status")
        }
    }

    /// Package import errors (`package_validate` codes and the importer's own).
    static func importError(_ code: String) -> String {
        switch code {
        case "unknown_sender": String(localized: "partner.import.unknown_sender", defaultValue: "This file is from someone you haven't paired with. Pair with them first in Settings › Partner Health.", comment: "Partner package import error")
        case "wrong_recipient": String(localized: "partner.import.wrong_recipient", defaultValue: "This file was made for a different phone.", comment: "Partner package import error")
        case "bad_signature", "hash_mismatch": String(localized: "partner.import.tampered", defaultValue: "This file was changed or damaged after it was created and can't be imported.", comment: "Partner package import error")
        case "unsupported_version": String(localized: "partner.import.unsupported_version", defaultValue: "This file needs a newer version of Ayuvo.", comment: "Partner package import error")
        case "cursor_gap": String(localized: "partner.import.cursor_gap", defaultValue: "Earlier changes are missing. Ask your partner to send everything.", comment: "Partner package import error")
        case "not_package": String(localized: "partner.import.not_package", defaultValue: "This isn't an Ayuvo partner health file.", comment: "Partner package import error")
        default: String(localized: "partner.import.unreadable", defaultValue: "This file can't be read as partner health data.", comment: "Partner package import error")
        }
    }

    /// Pairing failures (`PartnerPairingState.failed` codes and a declined pairing).
    static func pairingError(_ code: String) -> String {
        switch code {
        case "not_ayuvo": String(localized: "partner.pair.not_ayuvo", defaultValue: "This isn't an Ayuvo partner code. On your partner's phone, open Settings › Partner Health › Add partner › Show my code.", comment: "Partner pairing error: scanned some other QR code or barcode")
        case "unsupported_version": String(localized: "partner.pair.unsupported_version", defaultValue: "This code needs a newer version of Ayuvo. Update Ayuvo on both phones.", comment: "Partner pairing error")
        case "malformed", "bad_key": String(localized: "partner.pair.malformed", defaultValue: "This code couldn't be read. Ask your partner to show it again.", comment: "Partner pairing error")
        case "self": String(localized: "partner.pair.self", defaultValue: "That's this phone's own code. Scan the code shown on your partner's phone.", comment: "Partner pairing error")
        case "expired": String(localized: "partner.pair.expired", defaultValue: "This code has expired. Ask your partner to show a new one.", comment: "Partner pairing error")
        case "not_trusted": String(localized: "partner.pair.not_trusted", defaultValue: "The phones couldn't verify each other. Ask your partner to show a new code and scan again.", comment: "Partner pairing error: wrong or used code")
        case "partner_unavailable": String(localized: "partner.pair.partner_unavailable", defaultValue: "Couldn't reach your partner's phone. Keep both phones awake and on the same Wi-Fi or hotspot, then try again.", comment: "Partner pairing error")
        case "local_network_denied": String(localized: "partner.pair.local_network_denied", defaultValue: "Ayuvo needs Local Network access to pair. Turn it on in Settings › Privacy & Security › Local Network.", comment: "Partner pairing error")
        case "waiting_for_network": String(localized: "partner.pair.waiting_for_network", defaultValue: "Connect both phones to the same Wi-Fi, or to one phone's hotspot, to pair.", comment: "Partner pairing error")
        case "timeout": String(localized: "partner.pair.timeout", defaultValue: "Your partner didn't confirm in time. Start again on both phones.", comment: "Partner pairing error")
        case "declined": String(localized: "partner.pair.declined", defaultValue: "Pairing was cancelled. Nothing was shared.", comment: "Partner pairing result when either side tapped Doesn't match or Cancel")
        default: String(localized: "partner.pair.internal", defaultValue: "Something went wrong. Try again.", comment: "Partner pairing error")
        }
    }

    static func upToDate() -> String {
        String(localized: "partner.import.up_to_date", defaultValue: "Already up to date", comment: "Partner package import result when nothing was new")
    }
}
