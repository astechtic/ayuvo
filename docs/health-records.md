# Health Records — cross-platform contract

Plan: `/Users/macbook/.claude/plans/improve-workouts-flow-in-typed-pinwheel.md`. Shared files live in `shared/records/`; each platform embeds a verbatim copy and a parity test compares it with the shared file. Change the shared file first, then both platforms in the same change.

## 1. Rules that never bend
1. The imported original file is the source of truth. It is never modified or re-encoded; it is copied byte-for-byte into the records files root.
2. Import succeeds as soon as the original is copied and the `records` row is inserted. Every later stage is best-effort and may fail without removing the record.
3. AI output is a suggestion (`state = suggested`) and never overwrites `confirmed`, `user` or `rejected` values.
4. Nothing is split, merged, replaced or deleted without an explicit user action.
5. Records are excluded from iCloud backup, Android Auto Backup and the regular Drive `ayuvo-backup.zip`. They leave the device only through: the user's chosen AI provider (per the AI mode), explicit sharing, a user-saved `ayuvo-records` archive, or the Android opt-in Drive records backup.
6. Copy rule: never say records "never leave your device".

## 2. Storage locations
| | Android | iOS |
|---|---|---|
| Database | `context.getDatabasePath("ayuvo_records.db")` | `Application Support/Ayuvo/Records/records.sqlite` |
| Files root | `filesDir/ayuvo-records/` | `Application Support/Ayuvo/Records/files/` |
| Original | `<id>/original.<ext>` (relative `file_path`) | same |
| Thumbnail | `<id>/thumb.jpg` (max 320 px, JPEG q76) | same |
| Render cache | `cacheDir/records-render/` | `Caches/Records/render/` |
| Backup exclusion | `backup_rules.xml` + `data_extraction_rules.xml` exclude `ayuvo_records.db*` and `ayuvo-records/` | `isExcludedFromBackup` on `Ayuvo/Records/`; no `healthRecords*` preference key is in cloud backup |

Delete All Data deletes the database (and its `-wal`/`-shm`/`-journal` siblings), the files root and the render cache.

## 3. Enumerations (stored as these exact lowercase strings)
- `record_type`: `lab_report`, `prescription`, `consultation_note`, `discharge_summary`, `imaging_report`, `diagnostic_report`, `medication_list`, `vaccination_record`, `bill`, `insurance`, `personal_note`, `other`.
- `category`: `lab_reports`, `prescriptions`, `doctor_visits`, `imaging`, `hospitalization`, `procedures`, `vaccination`, `medication`, `insurance_bills`, `personal_notes`, `other`.
  - Default category per type: lab_report/diagnostic_report → lab_reports; prescription → prescriptions; consultation_note → doctor_visits; discharge_summary → hospitalization; imaging_report → imaging; medication_list → medication; vaccination_record → vaccination; bill/insurance → insurance_bills; personal_note → personal_notes; other → other.
- `source`: `import` (file picker), `photos`, `camera`, `scan`, `paste`, `note`, `share_in` (Android share intent / iOS share extension), `open_in` (VIEW intent / Files "Open in").
- `import_method`: `file_picker`, `photo_picker`, `camera`, `document_scanner`, `paste_text`, `note_editor`, `share_sheet`, `open_in`, `archive_restore`.
- `file_type`: `pdf`, `image`, `text`, `other`. Decided by sniffing bytes (`%PDF-` → pdf; JPEG `FF D8 FF`, PNG `89 50 4E 47`, HEIC/HEIF `ftyp` brands `heic|heix|hevc|heim|heis|mif1|msf1`, WebP `RIFF....WEBP`, GIF `GIF8` → image; valid UTF-8 without NUL bytes → text; otherwise other). Sender MIME type and filename are hints only.
- `mime_type`: from the sniffed type (`application/pdf`, `image/jpeg`, `image/png`, `image/heic`, `image/webp`, `image/gif`, `text/plain`, `text/markdown` when the extension is `.md`, `application/octet-stream`).
- `processing_status`: `saved`, `queued`, `extracting_text`, `analyzing`, `ai_pending_consent`, `ready`, `failed_partial`. Phase 1 writes `saved` on import and `ready` after thumbnail/metadata succeed (`failed_partial` + `processing_error` if they fail).
- `review_status`: `none`, `needs_review`, `reviewed`.
- `document_date_precision`: `day`, `month`, `year`. `document_date_method`: `file_metadata`, `pdf_text`, `ocr`, `rules`, `ai_local`, `ai_cloud`, `user`, `import_time`.
- `record_pages.text_source`: `pdf_text`, `ocr`, `user`, `plain`.

## 4. Phase 1 import behaviour
1. Stream-copy the source to `<files root>/<id>/original.tmp` while computing SHA-256; sniff the first 32 bytes; rename to `original.<ext>` (ext from the sniffed type: pdf, jpg, png, heic, webp, gif, txt, md, bin). Maximum file size 512 MB (larger → import refused with a message; nothing is kept).
2. Pasted text and notes are saved as `original.txt` (UTF-8) and one `record_pages` row (`page_index 0`, `text_source plain`).
3. Plain-text and Markdown files also get one `record_pages` row with their text (capped at 2 MB of text).
4. Insert the `records` row (`processing_status = saved`). Title: user-entered, else the filename without extension (underscores/dashes → spaces, trimmed), else `"<Type> — <date>"`, else "Untitled record".
5. Exact duplicate check: another record with the same `checksum_sha256` → show "This looks like an existing record" with Keep both · Replace · Merge · Cancel (Phase 1 implements Keep both, Cancel and Open existing; Replace/Merge arrive with Phase 2 review).
6. Background: page count (PDF), thumbnail (PDF page 1 / image downsample; text → none, the UI draws a text tile), basic metadata date (PDF `/CreationDate`, EXIF `DateTimeOriginal`, filename `yyyy-MM-dd|yyyyMMdd|dd-MM-yyyy` patterns) → `document_date` with `document_date_method = file_metadata`, precision `day`; when none, `document_date = NULL` and the timeline uses `created_ms`.
7. Index the FTS row: `title`, `notes_tags` (notes + tag names), `body` (page text), others empty in Phase 1. Folding: lowercase + strip diacritics (NFD, remove combining marks) on both write and query; query terms become `term*` joined by spaces (implicit AND).

## 5. Timeline ordering
`records.sort_date` = `document_date` when known, else the **device-local** calendar day of `created_ms` at import time. The app writes it on insert and on every `document_date` change (clearing the document date resets it to the local import day). Order: `ORDER BY sort_date DESC, created_ms DESC, seq DESC` (served by `idx_records_timeline`), keyset cursor `(sort_date, created_ms, seq)`, grouped by the month of `sort_date`. Archived records are hidden unless the Archived filter is on.

## 7. Share inbox (iOS app group)
`<app group>/RecordsInbox/<uuid>/` contains `item.json` and at most one payload file named `payload.<ext>` (ext from the source filename, else from the UTI). The extension writes into `RecordsInbox/.tmp-<uuid>/` and renames the folder when complete; the app ignores `.tmp-*` folders and deletes an item folder only after its import finished (success or a permanent failure). `item.json`: `{"format":"ayuvo-records-inbox","version":1,"original_filename":…,"uti":…,"received_at_ms":…,"text":…}` — `text` is set only for text items without a payload.

## 6. Preference keys
- `healthRecordsViewMode`: `timeline` (default) | `list` | `grid`.
- `healthRecordsAiMode` (Phase 2): unset | `local` | `cloud` | `ask` | `off`.
All `healthRecords*` keys are excluded from cloud backup.
