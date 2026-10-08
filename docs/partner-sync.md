# Partner Health Sync — cross-platform contract

Plan: `/Users/macbook/.claude/plans/pasted-content-id-e508-ayuvo-sleepy-comet.md`. Shared files live in `shared/partner/` (`schema.sql`, `record_types.json`, `protocol.json`, `test-vectors/`, `fixtures/`). The executable reference is `scripts/partner_reference.py`; `scripts/partner_contract_check.py` validates the vectors (`--write` regenerates every `expected`). Where this prose and the reference disagree, the reference wins and the prose is fixed. Change the shared files first, then both platforms in the same change.

## 1. Product rules that never bend
1. **Local only.** There is no Ayuvo server, cloud database, account or internet fallback. Data moves only:
   - directly between two paired phones on the same local network (same Wi-Fi router or one phone's hotspot), or
   - as a `.ayuvo.zip` file the user sends through the OS share sheet.
2. **Partner data is separate and read-only.** Received data lives in its own database (§6), keyed by the partner's `owner_id`. It is never merged into the user's own health, food, medication or records stores. It is never written to HealthKit / Health Connect, never exported in Export All Data, and never backed up.
3. **No documents.** Report files, page images, OCR text, bounding boxes, evidence snippets, notes, medication photos and GPS tracks are never sent. Only structured overviews (§7.6) are sent.
   - Every mapper reads named columns only.
   - Validators reject any record containing a `forbidden_keys` field (`record_types.json`).
   - Package readers reject any entry outside the allow-list (§13).
4. **Consent per partner and category.** Nothing is shared until the user turns a category on for that partner. The default is off.
5. **Owner decisions (2026-10-07):**
   - Packages are **signed, not encrypted**.
   - Vitals are shared as **daily rollups for all history plus raw samples for the last 7 days**.
   - On revoke or unpair, the **receiver keeps** what it already has, labelled "No longer shared".
   - **Multiple partners** are supported.
6. Copy rule: "stored on this device", never "never leaves your device".

## 2. Storage, backup and deletion
| | Android | iOS |
|---|---|---|
| Database | `ayuvo_partner.db` (`partner/data/PartnerDatabase.kt`) | `Application Support/Ayuvo/Partner/partner.sqlite` (`Partner/Data/PartnerDatabase.swift`) |
| Identity keys | `data/KeyStore.kt` (EncryptedSharedPreferences, Android Keystore master key) | Keychain, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` |
| Backup | excluded in `backup_rules.xml` + `data_extraction_rules.xml` | directory `isExcludedFromBackup`; never in iCloud |

The device keys are never exported, so a restored or new phone is a new partner device and must pair again.

User actions:
- **Unpair**: trust is removed and the data is kept.
- **Delete partner data**: the partner's rows are removed and that partner's cursor is reset to 0, so a later sync re-sends what is still shared.
- **Remove partner**: both of the above.

## 3. Identity and keys
- Each installation lazily creates, on first use of Partner:
  - `device_id`: a lowercase UUID
  - an **X25519** static key pair (Noise)
  - an **Ed25519** signing key pair (packages)
- Public keys travel as base64url without padding (`b64url_encode`).
- **Fingerprint:** hex of the first 16 bytes of SHA-256(x25519_pub ‖ ed25519_pub). It is shown as 8 groups of 4 upper-case hex digits (`fingerprint`, `format_fingerprint`; vectors in `kdf`).
- **Primitives:**
  - iOS: CryptoKit `Curve25519.KeyAgreement`, `Curve25519.Signing`, `ChaChaPoly`, `SHA256`, `HKDF`.
  - Android: Tink `X25519`, `Ed25519Sign`/`Ed25519Verify`, `ChaCha20Poly1305` (JCA lacks them below API 33).
- No custom cryptography: sessions use the published Noise Protocol Framework (§5). Both Noise ports must replay `test-vectors/noise_ikpsk2.json` and `noise_kk.json` (cacophony) byte for byte.

## 4. QR pairing (`qr`)
**Text:** `ayuvo-partner:1:` + base64url(compact JSON, sorted keys).

| Field | Content |
|---|---|
| `protocol` | `"ayuvo-partner-sync"` |
| `v` | 1 |
| `device_id` | |
| `name` | trimmed, whitespace collapsed, ≤ 40 code points, empty becomes "Ayuvo" |
| `x25519`, `ed25519` | public keys, 32 bytes each |
| `token` | 32 random bytes |
| `exp_ms` | now + 10 min |
| `hosts` | `["ip:port", …]`, ≤ 8, the showing phone's current addresses |

- The QR carries **no health data**.
- **`qr_parse` error codes:**
  - `not_ayuvo`: any other QR or barcode
  - `unsupported_version`
  - `malformed`
  - `bad_key`: a key or the token is not 32 bytes
  - `self`: my own QR
  - `expired`: past `exp_ms`, or `exp_ms` more than 10 min ahead
- On success the payload gains `fingerprint`.

**Flow:**
1. **A** (Partner Health › Add partner › Show code) opens a pairing listener (§5) on an ephemeral TCP port, advertises it (§11) and shows the QR.
2. **B** (Add partner › Scan code) parses it. B connects to each `hosts` entry in order, then to Bonjour/NSD results, and runs **Noise_IKpsk2** as initiator. The responder static key is the QR `x25519`; the psk comes from `pairing_psk(token)`.
3. Both sides compute `sas_code(handshake_hash)` and show the same **6 digits**. Each user taps Confirm only if the codes match.
4. After the user confirms the code and picks what to share, each side sends `PAIR_CONFIRM {accepted, name, grants, device_id, ed25519, platform}` inside the encrypted channel. The responder learns the initiator's X25519 key from the IK handshake and its Ed25519 key here. A decline sends `accepted: false` and closes.
5. With both accepted, each side stores the other in `partners` with its keys and grants, then the normal session (§12, starting with HELLO) runs on the same connection as the initial sync.

**Rules:**
- The token is single-use and dies with the listener (10 min, or after one pairing).
- A wrong psk fails the handshake (`not_trusted`).
- Scanning an already-paired device re-pairs it: keys are replaced, and data and cursor are kept only if the keys are unchanged.

**Derivations** (vectors in `kdf`):
- `pairing_psk` = HKDF-SHA256(ikm = token, salt = "ayuvo-partner-sync-v1", info = "pairing-psk", L = 32).
- `sas_code` = SHA-256("ayuvo-partner-sas" ‖ handshake_hash), first 4 bytes big-endian mod 10^6, zero-padded to 6 digits.

## 5. Transport security
- **Pairing:** `Noise_IKpsk2_25519_ChaChaPoly_SHA256`. **Trusted sessions:** `Noise_KK_25519_ChaChaPoly_SHA256`. Prologue `"ayuvo-partner-sync/1"` (UTF-8).
- In KK the responder looks up the initiator's static key in `partners` (unpaired rows excluded) and aborts with `not_trusted` before decrypting anything else. A random Ayuvo, or any other device on the LAN, gets nothing.
- **Framing:** 2-byte big-endian length + Noise message (≤ 65535 bytes). After the handshake every frame is one transport message carrying one UTF-8 JSON protocol message (§12). A JSON message larger than 65535 − 16 bytes is split by the sender across `CHANGES` pages, never across frames.
- **Timeouts:** 20 s idle, 5 min per session. Sockets close after `DONE` or `ERROR`. No connection is kept open to detect presence.

## 6. Partner database (`shared/partner/schema.sql`, v1)
| Table | Purpose |
|---|---|
| `partner_meta` | `schema_version`, `rev` (my outbound counter), `device_id` |
| `partners` | trust: name, fingerprint, keys, platform, `paired_ms`, `unpaired_ms`, last host/port |
| `partner_grants_out` | what I share with each partner |
| `partner_grants_received` | what each partner shares with me, `revoked_ms` |
| `partner_records` | received data: `(owner_id, type, record_id)` → `category, rev, day, ts_ms, updated_ms, data_json` |
| `partner_sync_state` | `last_rev` (my cursor into their ledger), `acked_rev` (their cursor into mine), last sync/attempt, transport, `status`, `last_error`, `last_export_id` |
| `outbound_ledger` | my shareable records: `(type, record_id)` → `category, day, content_hash, rev, deleted` |

- **Migrations:** `migrations/` is empty. Because this is a new database, existing installs gain an empty file and their other databases are untouched.
- **SQLite 3.18 rule** (Android minSdk 26): upserts are UPDATE-then-INSERT.

## 7. Records on the wire
**Envelope:** `{type, id, category, rev, deleted, updated_ms, day?, data?}`.
- `day` is `yyyy-MM-dd` in the sender's local time, required exactly for types with `day: true`.
- A tombstone has `deleted: true` and no data.
- Per-type fields are listed in `record_types.json`.

**`envelope_validate` codes:** `malformed`, `unknown_type`, `not_shareable` (health type outside the allow-list), `category_mismatch`, `missing_field`, `bad_day`, `forbidden_field`, `too_large` (string > 2000, array > 500).

### 7.1 Health-derived types (category from the `health_types` allow-list)
| Type | Source | id | Notes |
|---|---|---|---|
| `metric_day` | `health_daily_rollups` | `<type_id>:<day>` | all history |
| `metric_hour` | `health_hourly_rollups` (`hourly_types`) | `<type_id>:<day>:<hour>` | last 7 days |
| `sample` | `health_samples` (`intraday_types`, `deleted = 0`) | sample id | last 7 days; sleep stage rows included for the hypnogram |

Cycle-tracking, symptom, mental-wellbeing, hearing, mobility, event and ECG types are **never** shared (contract check enforces).

### 7.2 Vitals extras
- `derived_day`: from `derived_daily_values`, rows with a value only, excluding derived metrics in the mobility and hearing categories.
- `analytics_day`: single-day `analytics_results` for `recovery_indicator`, `hrv_status`, `sleep_status`, `load`.
- `weight`: the app's own weight entries, used when Health is not connected.

### 7.3 Sleep
`sleep_night`: one row per wake day, built by the platform's sleep analysis (`HealthSleepAnalysis`). It holds asleep, in-bed, deep, REM, light and awake minutes, plus sleep HR, HRV, respiratory rate and efficiency when present.

### 7.4 Nutrition and workouts
- `food_entry`: the app's diary entry. No photo and no image path.
- `water_day`: daily total in ml.
- `workout`: the app's workout diary and Health workouts. Activity, times, duration, kcal, distance, pace, HR, load and set/volume summary. **No GPS track.**

### 7.5 Medicines
`medication`, `medication_schedule`, `dose_log` come from the medications DB.
- `photo_path` and `related_record_id` are dropped.
- `times_json` / `days_json` become arrays.
- `is_prn` becomes a bool.
- `dose_log.note` (the user's own short dose note, ≤ 200 chars) **is** shared, because the product brief asks for medicine notes "where appropriate". The forbidden key is `notes`, which covers record notes and is never sent.

### 7.6 Report overviews (`report_overview`)
- **Source:** a `records` row plus its `record_fields`, `record_highlights` and `observations`. Archived records are not shared.
- **Keys:**
  - `title`, `record_type`, `category`
  - `report_date` (`document_date`, else a valid `report_date` field)
  - `doctor`, `doctor_specialty`, `facility`: the best non-rejected field, in order user > confirmed > suggested, then highest confidence
  - `summary`: the first live `summary` highlight
  - `highlights` (`important`) and `recommendations`
  - `results`: every non-rejected observation, sorted by raw name, with name, analyte_id, value, value_num, unit, ref_low/high/text, flag and observed_date
  - `abnormal`: results flagged low, high, critical or abnormal
  - `diagnoses`, `medications`: from `medication` field JSON
- **Never sent:** files, pages, OCR text, `evidence`, `source_bbox`, `source_page`, patient-name fields or `notes`.
- **Frame fit:** an envelope must fit one Noise frame on the network (≤ 65535 − 16 bytes of JSON). An oversized `report_overview` drops `results` entries from the end, never `abnormal`, `summary` or `highlights`, until it fits. Any other record that still does not fit is skipped on the network only (its rev is covered, it is counted as `oversize_skipped`) and travels in packages, which have no frame limit.

### 7.7 Source mappings (`map`)
`map_rollup`, `map_hourly`, `map_sample`, `map_analytics`, `map_medication`, `map_schedule`, `map_dose_log`, `map_report_overview` are pinned by vectors. The blob-backed types (`food_entry`, `water_day`, `weight`, `workout`) and `sleep_night` follow the `record_types.json` field lists from each platform's own models.

## 8. Merge engine — receiver (`merge_apply`)
Network sync and package import both call the same function with one `CHANGES` batch from one owner.

1. If `from_rev > cursor`, the batch is refused whole (`cursor_gap`). A malformed batch is `malformed`.
2. Each record is checked in order. The checks are `envelope_validate`, then that its category is currently granted by that owner (`not_granted`), then that `rev ≤ to_rev`. A rejected record is reported and skipped; the rest of the batch still applies.
3. The highest rev wins. An equal rev is a `duplicate`, a lower one is `stale`.
   - A record with no stored row and `rev ≤ cursor` is `stale`, because it was committed before and has since been deleted, pruned or purged. Replaying an old package therefore never resurrects anything.
   - Inside one batch, a later tombstone cancels an earlier insert.
4. **Output:**
   - upserts and deletes, sorted by `(type, record_id)` comparing Unicode scalars (UTF-8 byte order; Kotlin and Swift ports must not use locale or UTF-16 ordering)
   - counts: inserted, updated, deleted, duplicate, stale, rejected
   - the new cursor, `max(cursor, to_rev)`
   - `updated_ms` is clamped to `now + 10 min`.
5. Platforms write the upserts, the deletes and `partner_sync_state.last_rev = cursor` in **one transaction**. A killed sync resumes from the last committed batch.

Idempotency is checked by the contract: re-applying any accepted batch on its own result changes nothing.

**Retention (`retention_prune`):** after every sync, delete received `sample` and `metric_hour` rows with `ts_ms` older than 7 days.

## 9. Grants and revocation (`grants_received`)
- **Received grants:** every HELLO and every package manifest carries the categories the owner currently shares with me. `grants_received_update` sets `granted`. A category that was granted and is no longer gets `revoked_ms = now` and its data is **kept**; the UI labels it "No longer shared". Re-granting clears `revoked_ms`.
- **Outgoing grants:** `partner_grants_out` filters every delta query (§10), so a revoked category's new or changed records are never emitted. Turning a category back on for anyone runs `ledger_regrant`, which gives every row of that category a fresh rev. Any partner whose cursor moved past those rows while the category was off therefore receives them again; duplicates are absorbed by §8.

## 10. Outbound ledger — sender (`ledger`)
- **Why it exists:** the sender keeps a ledger because food, weights and workouts live in JSON blobs without update timestamps, and the user's stores must not change.
- **`ledger_refresh`** runs before every sync or export, in batches off the main thread:
  - Each source renders its records and hashes the canonical JSON. The hash is device-local and never compared across devices.
  - **Scopes:**
    - `food_entry`, `weight`, `workout`, `medication*`, `dose_log` and `report_overview` use full scope.
    - `metric_day`, `derived_day`, `analytics_day`, `sleep_night` and `water_day` use `day_from = today − 30` after the first full run.
    - `sample` and `metric_hour` use `day_from = today − 7`.
  - A new or changed hash, category or day gets the next rev. A ledger row in scope without a current record becomes a tombstone with the next rev. Revs are assigned in `(type, record_id)` order: changes first, then tombstones.
- **`ledger_prune`:** drops intraday ledger rows older than the window locally, without a rev. Receivers prune the same rows themselves.
- **`ledger_delta`:**
  - Selects `rev > cursor AND category IN grants` in ascending rev order, `LIMIT 500`.
  - `to_rev` is the last row's rev while `has_more`, otherwise the device counter, so revs of ungranted categories are skipped.
  - A cursor ahead of the counter (the receiver remembers more than this install issued) restarts from 0.
  - Payloads are rendered from the source when sent; a row whose source vanished is sent as a tombstone.
  - Data is never loaded all at once: ledger pages and source reads are bounded by the page.

## 11. Discovery and automatic sync
- **Service:** `_ayuvo-partner._tcp`, TXT `v=1`, random instance name. No names or ids are advertised.
  - iOS: `NWListener` + `NWBrowser`. Android: `NsdManager` (`registerServiceInfoCallback` on API 34+) with plain sockets.
- **Fallback:** the last address that completed a session (`partners.last_host/last_port`) is dialled directly when discovery finds nothing. Some hotspots and routers block mDNS.
- **Sync window** (≤ 60 s, `discovery_window_ms`): advertise + listen + browse, connect to every trusted partner found, sync, tear everything down. Windows open:
  - **Android:**
    - app foreground (`MainActivity.onStart`)
    - `PartnerSyncWorker`: periodic 1 h, `NetworkType.UNMETERED`
    - a one-shot worker on network-available
    - Sync Now
  - **iOS:**
    - `scenePhase == .active`
    - `BGAppRefreshTask` `com.ayuvo.health.partner.sync`, in whatever time the OS grants
    - Sync Now
- **Guarantees:** no foreground service, no polling loop, no socket held open.
- **Honest limit:** two phones sync only while both are awake at the same time. Foreground on either side works. Background ↔ background is opportunistic. The UI never promises more.
- **Both sides connect:** when both browse, the device with the lexicographically smaller `device_id` connects at once. The larger one dials only if no session from that partner arrived within 15 s, which covers a partner whose browser is blocked. A second simultaneous session is refused with `busy`.

## 12. Protocol v1 (`message_validate`, `session`)
Every message is a JSON object with `t`. Field lists live in `protocol.json`.

```
HELLO        {protocol, v, device_id, platform, app_version, grants, capabilities?, name?}
PAIR_CONFIRM {accepted, name, grants, device_id, ed25519, platform}   (pairing only; identity fields required when accepted)
SYNC_REQ     {cursor}                               my last committed rev of your ledger
CHANGES      {from_rev, to_rev, has_more, records}  ≤ 500 records and ≤ 512 KB per page
ACK          {committed_rev}                        after the page is committed (§8 step 5)
DONE         {}
ERROR        {code, message?}
```
Error codes: `unsupported_version`, `not_trusted`, `malformed`, `cursor_gap`, `busy`, `internal`.

**Session flow** (`session_script` checks the inbound order):
1. HELLO both ways. A `v` mismatch gets `unsupported_version` and an unknown key gets `not_trusted`; neither side sends data.
2. Each side sends one SYNC_REQ and serves the other's SYNC_REQ with CHANGES pages from `ledger_delta`, waiting for an ACK after each page. It sets `acked_rev` from the ACK.
3. After its own pull ends (a page with `has_more = false`), a side sends DONE once its pages are acknowledged.
4. An interrupted session is `incomplete`: committed pages stay, and the next session resumes from the cursor. A `cursor_gap` (a lost commit) makes the receiver send `ERROR cursor_gap` and close (only one SYNC_REQ is allowed per session). The dialling side then immediately opens one fresh session, which requests from the committed cursor.

## 13. `.ayuvo.zip` package (`package`)
**Filename** (`package_filename`): `<Name>_Health_YYYY-MM-DD.ayuvo.zip`. The name keeps ASCII letters and digits joined by `_`, max 32 characters, falling back to "Partner".

**Entries** (`package_entry_names`), JSON or NDJSON only:
- `manifest.json`, written first
- `signature.json`
- `profile/partner.json`
- `sync/revision.json`
- `health/<category>.ndjson` for each granted category, one envelope per line, in rev order

**manifest.json:**
- `format: "ayuvo-partner-sync"`, `version: 1`
- `export_id`, `device_id`, `recipient_device_id`, `created_ms`
- `from_rev`, `to_rev`, `categories` (the grants)
- `files: [{name, sha256, bytes, count}]`
- The display name is only in `profile/partner.json`.

**signature.json:** `{alg: "Ed25519", key: <ed25519 pub>, sig: base64url(Ed25519(manifest.json bytes as stored))}`. The bytes are verified as stored, with no re-serialization.

**Export:**
- One recipient per package.
- Default content is everything shared (`from_rev = 0`). "Changes since last sync" starts at `acked_rev`.
- Envelopes are streamed into the zip in 500-row pages.

**`package_validate`** runs before any record is read. Codes in check order:

| Code | Meaning |
|---|---|
| `not_package` | not an Ayuvo partner package |
| `unsupported_version` | |
| `malformed` | |
| `unsafe_entry` | absolute paths, `..`, backslashes, NUL |
| `unknown_sender` | sender not a trusted partner |
| `wrong_recipient` | package made for someone else |
| `bad_signature` | |
| `missing_entry` | |
| `unexpected_entry` | anything outside the allow-list, e.g. a PDF or image, or a file for an ungranted category |
| `hash_mismatch` | |

A category file listed nowhere is simply absent.

**Import:**
1. The confirmation sheet shows "Health data from <name> · N records · per-category counts" (`package_summary`).
2. `package_import` feeds the NDJSON files (category order of §1, file order inside) in 500-envelope batches into the same `merge_apply`. Category files interleave revs, so every batch uses the manifest's `from_rev`/`to_rev` and the **pre-import cursor**; each batch's rows commit in their own transaction and the cursor (`max(cursor, to_rev)`) commits only with the last batch. An interrupted import re-runs as duplicates.
3. A package older than the cursor merges as duplicates or stale and reports "Already up to date".

**Receiving from the OS:**
- Android: `MainActivity` VIEW/SEND intent filters (`application/zip`, `application/octet-stream`, `*.ayuvo.zip`). It sniffs `manifest.json` before the Records handler.
- iOS: `CFBundleDocumentTypes` for `public.zip-archive` (Alternate). It sniffs in `onOpenURL` before Records, and the share extension accepts zips into the app-group inbox.
- Zips that are not partner packages fall through to the existing handlers.

## 14. Status model
`partner_sync_state.status`:

| Status | Meaning |
|---|---|
| `pairing_required` | |
| `waiting_for_network` | no Wi-Fi/LAN |
| `connecting` | |
| `syncing` | |
| `up_to_date` | |
| `partner_unavailable` | window ended without finding them |
| `local_network_denied` | iOS Local Network permission off, or the NSD error on Android |
| `not_trusted` | the partner unpaired me or changed keys |
| `sync_failed` | `last_error` holds the code |

The UI always shows stored data with "Updated <relative time>" and never hides a partner because they are offline.

## 15. Summary card (`summary_metrics`)
- One compact card in Summary › Today. It lists up to 3 partners, sorted by most recent sync.
- Each row shows the name, freshness and up to 3 metrics in priority order:
  1. `recovery`: `analytics_day` recovery_indicator, today or yesterday
  2. `sleep`: `sleep_night`, today or yesterday
  3. `resting_hr`: `metric_day` avg, today or yesterday
  4. `activity` (exercise minutes today, if > 0), else `steps` today
  5. `medicines`: today's dose logs, taken of total
- Values use `round_half_up`. A metric appears only when a row exists, so nothing is fabricated. A metric from a revoked category is flagged `shared: false`.
- The card hides itself when there are no partners. Identifier: `summary.card.partner`.

## 16. Testing
- **Automated:**
  - `python3 scripts/partner_contract_check.py`
  - Android `PartnerVectorTests` and iOS `PartnerVectorTests`, which run every vector file and fail if a file has no runner
  - Noise cacophony replays
  - schema parity tests
  - merge/ledger DB integration tests: transaction rollback, 100k-row paging
  - package round-trip, tamper, corrupt, unknown-sender and version tests
  - the cross-platform package fixture `shared/partner/fixtures/partner-sample.ayuvo.zip` + `partner-sample.json`, regenerated byte-identically by `python3 scripts/partner_fixture.py` (dev tool, needs `cryptography`). Both apps verify its Ed25519 signature, import it to the recorded counts, re-import it as all duplicates, and reject a one-byte tamper (`hash_mismatch`) and a swapped key (`bad_signature`)
- **Manual matrix (physical devices):**
  - Pairs: iOS→iOS, Android→Android, iOS→Android, Android→iOS
  - Networks: same Wi-Fi, phone hotspot (each side as host)
  - Interruptions: Wi-Fi drop mid-sync (resume, no duplicates), partner offline (stale data shown)
  - Permissions: local-network denied
  - App states: foreground / background / suspended / locked
  - Grants: revoke then re-grant
  - Package via AirDrop, WhatsApp, Quick Share and Files
  - Results are recorded below.

| Date | Scenario | Result |
|---|---|---|
| 2026-10-08 | iOS simulator shows code, Android emulator scans (direct dial 10.0.2.2, debug hooks below) | Pass: same SAS on both, fingerprints cross-match, all 6 categories granted both ways, initial sync on the pairing connection (iOS received 7407, Android 34; cursors = sender revs) |
| 2026-10-08 | Trusted resync, Android dials iOS and iOS dials Android (adb forward) | Pass: only the delta moved (8 records, then 1 + 5 derived), a repeat of unchanged state moved 0 |
| 2026-10-08 | Unknown X25519 identity runs KK against each side | Pass: both responders close without a reply, initiator gets `not_trusted`, no rows or state change |
| 2026-10-08 | Packages Android → iOS and iOS → Android (VIEW intent) | Pass after the iOS inflate fix: 7410 / 42 inserted, re-import "Already up to date", content tamper `hash_mismatch`, manifest tamper `bad_signature` |

Emulator ↔ simulator test hooks (debug builds only): Android `DebugPartnerSeedActivity` extras `show_code`, `scan` + `scan_host`, `auto_confirm`, `listen_port`, `dial`, `sync_now`, `kk_probe`, `export_for`, `delete_data`, `confirm_import`, `dump`; iOS launch arguments `-ayuvoPartnerResetIdentity`, `-ayuvoPartnerShowCode`, `-ayuvoPartnerScan` + `-ayuvoPartnerScanHost`, `-ayuvoPartnerAutoConfirm`, `-ayuvoPartnerListenPort`, `-ayuvoPartnerDial`, `-ayuvoPartnerNoAdvertise`, `-ayuvoPartnerSyncNow`, `-ayuvoPartnerKKProbe`, `-ayuvoPartnerExportFor`, `-ayuvoPartnerDeleteData`, `-ayuvoPartnerDump`, `-ayuvoActionsAutoConfirm`. The emulator reaches a Mac listener at `10.0.2.2:P`; the simulator reaches an emulator listener through `adb forward tcp:Q tcp:P` at `127.0.0.1:Q`.

## 17. Known platform limits
- iOS cannot keep a listener alive in the background, and `BGAppRefreshTask` runs when the OS chooses.
- Android WorkManager windows are deferred by Doze and App Standby.
- Background ↔ background sync is opportunistic.
- Client isolation on some routers, guest networks and hotspots blocks peer connections entirely; the `.ayuvo.zip` path covers that.
- Packages are signed, not encrypted: anyone holding the file can read it. Import requires a paired sender.
- A restored or new phone has new keys and must pair again.
