# Coach — cross-platform contract

Plan: `/Users/macbook/.claude/plans/fluffy-popping-wreath.md`. Shared files live in `shared/coach/`;
each platform embeds a verbatim copy of the schema and the two catalogs, and a parity test compares
them with the shared file. The executable reference is `scripts/coach_reference.py`;
`scripts/coach_contract_check.py` validates the vectors in `shared/coach/test-vectors/`
(`--write` regenerates every `expected` from the reference). Where this prose and the reference
disagree, the reference wins and the prose is fixed. Change the shared files first, then both
platforms in the same change.

This document owns the **chat**: conversations, attachments, rendering, the data switches, the
gallery and the archive. It does **not** restate the tool contracts that already exist elsewhere —
`docs/health-data.md` §7 (health tools, consent, guardrails) and `docs/health-records.md` §26–§30
(records tools, consent, per-conversation record selection, the on-device packed block) stay
authoritative, and §3 below only says how Coach chooses between them. The medication tools are new
and live in `shared/medications/coach_tools.json`.

## 1. Rules that never bend

1. **A chart never invents a number.** Every value inside an `ayuvo-chart` block must come from a
   tool result, the user's own message or a file they attached. A spec that does not parse renders as
   a plain code block — never a guessed chart, never an empty axis. Days with no data are left out,
   never drawn as zero (the same rule the app's own charts follow, `docs/charts.md`).
2. **Tool payloads are never persisted.** A stored message holds the visible text, `record_refs` and
   attachment ids. No table in `shared/coach/schema.sql` holds a health value.
3. **A switch can only narrow.** The per-conversation data switch never grants consent; it only turns
   off something consent already permits (§8). Consent itself is only ever given by an affirmative
   act on its own screen.
4. **The user sees what the model sees.** The text extracted from an attachment is shown, after
   redaction, exactly as it will be sent (§6).
5. **Deleting means deleting.** Removing a conversation removes its messages and any attachment no
   other message references, file blobs included. A tombstone is local: it is never exported and it
   always beats an incoming archive row (§11).
6. Never tell the user to start, stop, change or skip a medicine, or suggest a dose
   (`shared/medications/coach_tools.json` → `prompt.guardrails`).

## 2. Storage locations, backup and deletion

| | Android | iOS |
|---|---|---|
| Database | `ayuvo_coach.db` (`coach/data/CoachDatabase.kt`) | `Application Support/Ayuvo/Coach/coach.sqlite` (`Coach/Data/CoachDatabase.swift`) |
| Attachments | `filesDir/ayuvo-coach/<attachment_id>/original.<ext>` + `thumb.jpg` | `Application Support/Ayuvo/Coach/files/<attachment_id>/…` |
| Repository | `coach/data/CoachRepository.kt` | `Coach/Data/CoachRepository.swift` (actor) |
| Observable state | `ui/coach/CoachViewModel.kt` | `Stores/CoachStore.swift` (`@Observable`) |
| OS backup | excluded in `backup_rules.xml` and in both `<cloud-backup>` and `<device-transfer>` of `data_extraction_rules.xml` | `isExcludedFromBackup = true` on the Coach directory, complete-until-first-authentication protection |
| Manual export | `coach_chats` section of the `ayuvo-all-data` zip (§11) | same |
| Cloud backup | only when `coachChatBackupEnabled` is on (off by default, `docs/cloud-backup.md`) | not applicable — iOS has no cloud backup |

Deleting a conversation, "Delete all chats" in Settings › Data & Privacy, and uninstalling all remove
the blobs as well as the rows.

## 3. Data sources and tools

Coach advertises tools from four sources. Everything about a source except *whether it is offered*
belongs to that source's own contract.

| Source | Tools | Contract | Consent flag |
|---|---|---|---|
| `food` | `get_data_summary`, `get_weight_history`, `get_body_fat_history`, `get_calorie_totals`, `get_food_entries`, `get_fasting_history`, and the five workout tools when the user has logged any | this document | none — the app's own diary |
| `health` | `get_health_data_types`, `get_health_summary`, `get_health_samples`, `get_sleep_history` | `docs/health-data.md` §7 | `coachHealthDataEnabled` |
| `medications` | `get_medications`, `get_dose_history`, `get_medication_adherence` | `shared/medications/coach_tools.json` | `coachMedicationsEnabled` |
| `records` | `records_search`, `records_get`, `records_observation_series` | `docs/health-records.md` §26–§30 | `healthRecordsCoachAccessEnabled` |

`coachMedicationsEnabled` (default **false**, plus `coachMedicationsConsentedAt`) is new. It is set
only by an affirmative act: the first time the user turns Medications on in the composer's data
switcher, a confirmation states that medicine names, strengths and dose times will be sent to their
AI provider. Turning it off removes the tools immediately. Both keys are cloud-backed
(`docs/cloud-backup.md`).

Implementations: iOS `Medications/Coach/{CoachMedicationsContext,MedicationsCoachToolExecutor}.swift`
and `Medications/Stores/MedicationStore+Coach.swift`; Android `medications/coach/CoachMedicationsContext.kt`
with the executor on `CoachTools.executeMedications`. Both build the context from the archive
snapshot, so a tool result and the app's own adherence screen read the same rows.

Whether the user *asked* about medicines — which decides if the "not available" line is worth the
tokens — is `prompt.mentions_words` in `shared/medications/coach_tools.json`, folded and matched
whole-word on both platforms, so the two can never drift.

Providers without tool calling (on-device Gemma, Apple Intelligence) get the existing text blocks
instead: the health digest (`docs/health-data.md` §7.3), the packed records block
(`docs/health-records.md` §29), and — new — a `## Medications` block of at most 12 lines, one per
active medicine: `- <name> <strength>: <schedule summary> (<adherence>% over <n> days)`.

## 4. Markdown blocks

Reference `parse_blocks(markdown)` (vectors `markdown_blocks.json`) → `{"blocks": [...]}`. It
replaces the two hand-rolled parsers that used to live in `ChatView.swift` and `CoachScreen.kt`.
Block level only: inline emphasis, code spans and links stay with the platform renderer
(`AttributedString(markdown:)` on iOS, `buildAnnotatedString` on Android), so this contract never has
to model them.

CRLF and CR become LF, and one trailing newline is the document terminator, not a blank line. Tabs
expand to `TAB_WIDTH` = 4 spaces; list depth is `min(3, indent / 2)`, so one tab nests two levels.
Precedence, highest first:

| Kind | Recognised by | Fields |
|---|---|---|
| `code` / `chart` | a line starting with ` ``` `; the info string `ayuvo-chart` makes it a chart (§5) | `lang` (lowercased, `null` when absent), `text`; an unterminated fence runs to the end |
| `rule` | `---`, `***` or `___`, three or more, alone on the line | — |
| `heading` | one to four `#` then a space | `level`, `text` |
| `table` | a line containing `|` whose **next** line is a delimiter row (`---`, `:--`, `--:`, `:-:`) with the same number of cells | `headers`, `aligns` (`left`/`center`/`right`), `rows`; a short row is padded, a long one is cut |
| `quote` | one to three `>` | `depth`, `text` |
| `task` | a bullet whose text starts `[ ] ` or `[x] ` | `depth`, `checked`, `text` |
| `bullet` | `- `, `* `, `+ ` | `depth`, `text` |
| `numbered` | digits then `.` or `)` then a space | `depth`, `marker`, `text` |
| `paragraph` | anything else | `text` |

Consecutive paragraph lines join with one space, as do consecutive quote lines of the same depth. A
blank line closes the open paragraph or quote. List items never merge.

Renderers: `Views/Coach/CoachMarkdownView.swift` (with `CoachTableView` and an `NSCache` of parsed
blocks) and `ui/coach/CoachMarkdown.kt`. Tables scroll sideways rather than squeezing columns on a
phone; links are tappable on both platforms. Only the inline pass differs, because each platform has
its own: `AttributedString(markdown:)` and `inlineMarkdown`.

## 5. Charts

Reference `parse_chart_spec(raw)` (vectors `chart_spec.json`) → `{"ok": true, "spec": …}` or
`{"ok": false, "reason": …}`. The grammar, the caps and the **exact** system-prompt section that
teaches the model to emit it are in `shared/coach/chart_spec.json`; both platforms embed it verbatim
and a parity test byte-compares.

Types: `bar`, `grouped_bar`, `stacked_bar`, `line`, `area`, `pie`, `scatter`, `range`, `progress`.
`pie` and `progress` take exactly one series; `range` requires `y2` on every point. Caps: 4 series,
60 points per series, 60 code points per label, 120 per title. Points may be `[label, value]`,
`[label, low, high]`, `{"x","y"[,"y2"]}` or a bare number (its label becomes its 1-based position); a
numeric label is printed without a trailing `.0`.

A normalised spec always has all nine keys — `type`, `title`, `unit`, `x_label`, `y_label`, `note`,
`max`, `series`, `dropped` — with `null` where absent (`dropped` is a count, so 0), so ports never
branch on a missing key.

**The body is repaired before it is read** (`repair_chart_json(raw)`, vectors `chart_repair.json`).
Strictness is about parity between the two phones, not about punishing a model for punctuation, so a
single left-to-right pass that never reaches inside a string drops `//` and `/* */` comments, drops a
comma that sits directly before `}` or `]`, drops a quote **glued to the end of a number** (`5.38"`,
a model writing the unit as a mark), and turns `NaN`, `Infinity`, `-Infinity`, `undefined` **and a
number token that is not a number** (`7.1.0`, `1.2.3`, `01`) into `null` — "no reading". The glued
quote matters more than it looks: left in, it opens a string that swallows the rest of the line, so
one stray character costs every reading after it. A quote after a number and a *space* is left alone
— only a glued one is a stray mark, and guessing otherwise could drop a key the model meant to write.
Reading `7.1.0` *nearly*, as 7.1, would be choosing a value for the user, so the point is left out
and counted instead. Then the root object is bounded: prose before the first `{` and after the root
value's last bracket is dropped, and a body that was **cut short** has the containers it left open
closed — structure only, never a value. A body cut off inside a string is left as it is, because
finishing a label would put words in the model's mouth. Nothing in the pass invents, rounds or moves
a number, and a genuinely ambiguous spec is still refused: single quotes and unquoted keys could
change a value if guessed at, so they stay `invalid_json`.

**What survives is then normalised.** The type is matched case-insensitively after spaces and dashes
become `_` and a trailing `_chart` is dropped, then through the alias table in
`shared/coach/chart_spec.json` (`column` → `bar`, `donut` → `pie`, `gauge` → `progress`, …); anything
else is `unknown_type`. The series, the points and the labels are read from the first key the object
*has*, from the lists in that same file — `series`/`datasets`/`data`, `points`/`data`/`values`/`y`,
`label`/`name`/`title`, `labels`/`categories`/`x_labels`, `x`/`label`/`name`/`t`, `y`/`value`/`v`,
`y2`/`high`/`y_high` — so the shapes chart libraries use are understood as they are written. A list
that carries no points at all is not a list of series: a list of point lists becomes one series each,
anything else becomes one series' points. A quoted number (`"6.2"`) is read back as the number, since
that is still the reading the model took; a string with a unit in it (`"6.2 h"`) is not a number and
is refused.

**A point with no reading is left out, never drawn as zero.** A `null`, absent, empty or unreadable
value drops that point and adds one to `spec.dropped`; a series whose readings are all missing is
dropped whole; a spec with nothing left to draw is `no_points`. This is the same rule the prompt
gives the model — omit the day, do not estimate it — applied on the reading side. Both renderers
print `dropped` under the plot ("3 readings could not be read and are not shown", a11y id
`coach.chart.dropped`), so a chart is never quietly shorter than the text beside it.

**The spec is never handed to a platform JSON parser.** They disagree about what they accept:
Apple's `JSONSerialization` allows trailing commas, Android's `org.json` additionally allows single
quotes, unquoted keys and comments, and Python's `json` allows `NaN` and `Infinity`. A chart that
renders on one phone and not another is a parity bug, so all three ports run the same scanner —
`strict_json(text)` / `CR.strictJSON` / `CoachReference.strictJson`, RFC 8259 with no extensions: no
trailing commas, no leading zeros, no comments, no single quotes, no unquoted keys, no `NaN` or
`Infinity`, no raw control characters inside strings, at most 20 000 code points and 32 levels of
nesting. A repeated key keeps the last value. Its behaviour is covered by the `chart_spec.json`
vectors, so a port that quietly falls back to the platform parser fails the suite.

It **fails closed**. `reason` is one of `invalid_json` (which covers `NaN` and `Infinity`),
`not_an_object`, `unknown_type`, `no_series`, `too_many_series`, `too_many_series_for_type`,
`no_points`, `too_many_points`, `bad_point`, `bad_number`, `label_too_long`, `missing_y2`, `bad_max`.
On any of them the renderer shows the raw text as a code block.

Every reason has a **wording group** (`chart_reason_group(reason)`), so the card can say what went
wrong rather than only that something did: `malformed` (`invalid_json`, `not_an_object`, `bad_point`),
`unsupported` (`unknown_type`), `too_big` (`too_many_series`, `too_many_series_for_type`,
`too_many_points`, `label_too_long`) and `no_readings` (`no_series`, `no_points`, `bad_number`,
`missing_y2`, `bad_max`). Each platform has one string per group —
`CoachMarkdownView.chartFailureText` / `coach_chart_failed_*` — and `coach_contract_check.py` fails if
a reason ever lacks a group.

Rendering follows `docs/charts.md`: bar width ratio 0.6, 4 pt top-only corners, hairline gridlines,
y labels aligned to the ticks, and the app's domain colours in the same order. iOS
(`Views/Coach/CoachChartView.swift`) uses Swift Charts with the constants from `ChartAxisStyle`;
Android (`ui/coach/CoachChartBlock.kt`) draws on Canvas with the constants from
`ui/charts/ChartAxis.kt`'s `ChartSpec`. `pie` and `progress` are laid out by hand on both, since
neither is a cartesian plot. `chart_accessibility_text(spec)` gives the one-sentence VoiceOver /
TalkBack label, and a long press offers "Copy data" — the numbers, not a picture of them.

The x axis shows at most six labels, evenly sampled with the first and last always kept, so a
60-point series does not become a smear.

**The prompt asks for the shape that is hardest to corrupt.** The worked example uses `labels` plus a
flat `values` list rather than seven `[label, value]` pairs: the same seven numbers, a third of the
brackets, and a model that drifts on one of them loses one reading instead of the structure. The
`points` forms stay accepted — see `point_forms` in `shared/coach/chart_spec.json` — because older
conversations and other models still write them.

The `## Charts` section and its guardrails are appended to the system prompt by
`ChatService.buildSystemPrompt` on both platforms, read from the bundled catalog
(`CoachCatalog.chartsPromptSection` / `CoachCatalogs.chartsPromptSection()`). Providers without tool
calling get it too: the format is plain text, so it works there as well.

## 6. Attachments

Reference `attachment_excerpt(pages, max_pages, max_chars)` (vectors `attachment_excerpt.json`) and
`turn_excerpts(attachments, max_turn_chars)`.

Kinds: `image`, `pdf`, `text`, `note`. Images keep the existing path (1600 px / q78 for the model,
700 px / q68 for the bubble) and are limited to 4 per turn; Apple Intelligence still rejects images.
Documents are turned into text **on device** — iOS `RecordTextExtractor` (PDF text layer, then Vision
OCR), Android `TextStage` + `MlKitOcrEngine` — and only the text is sent.

`attachment_excerpt` then: normalises CRLF/CR, drops every line the records redaction rule would drop
(`_pii_line`, `docs/health-records.md` §28 — identity/contact/ID labels and runs of ten or more
digits), skips empty pages, joins pages with a blank line and, when the attachment has more than one
page, introduces each with `--- page N ---`. Caps: `MAX_ATTACHMENT_PAGES` 20, `MAX_ATTACHMENT_CHARS`
20 000 per attachment, `MAX_TURN_CHARS` 40 000 per turn. It returns `text` (`null` when nothing
survives), `pages_total`, `pages_used`, `pages_skipped`, `chars`, `redacted_lines` and `truncated`.

The composer chip shows `<filename> · <pages> pages · <chars> characters` and opens a sheet with the
exact redacted text (rule 4). A record attached through the picker is **not** an attachment: it goes
through the existing selection of `docs/health-records.md` §27, unchanged.

The excerpts reach the model by being appended to the message text, after `turn_excerpts` has cut
them to the per-turn budget: `--- Attached file: <name> ---` then the excerpt, `(truncated)` when it
was cut, `(no readable text)` when the file yielded none. Implementations:
`Coach/Processing/CoachAttachmentComposer.swift` and `coach/processing/CoachAttachmentComposer.kt`.

Extraction: iOS `Coach/Processing/CoachAttachmentProcessor.swift` (PDFKit text layer, then Vision
OCR, via `RecordTextExtractor`); Android `coach/processing/CoachAttachmentProcessor.kt`
(`PdfRenderer.Page.getTextContents` where the S extension 13 provides it, ML Kit OCR on a 2x render
otherwise). Both refuse anything that is not a PDF or a text file, and anything over 64 MB.

## 7. Conversations

Reference `conversation_title(text)` (vectors `conversation_title.json`). From the first user
message: `[text](url)` becomes `text`, `*_`~` are removed, whitespace collapses, leading `#`/`>` are
stripped, the first sentence is taken when its terminator is at least `MIN_SENTENCE_LENGTH` (12) code
points in, and the result is cut to `MAX_TITLE_LENGTH` (48) on a word boundary with an ellipsis. An
empty result is `""`; the platform then shows its own localized "New chat".

A conversation carries its data switches, its selected record ids, its provider override and the §30
online decision, so switching away and back restores the state that used to be in memory. "New chat"
creates a row; it never clears one. "Start again from this chat" copies the title, switches and
record selection into a fresh empty conversation.

The list is ordered pinned first, then by `last_message_ms` (falling back to `updated_ms`), then
`created_ms`, all descending. A row shows the title (or the localized "New chat" when it is still
empty), the last message collapsed to 140 code points, the relative time and an attachment count.
Search runs over `messages_fts`: the query is folded, every term becomes a `term*` prefix match, and
the matching conversations come back in list order. Deleting tombstones the conversation and its
messages, removes their FTS rows, and deletes the blobs of every attachment no surviving message
references.

## 8. Data source switches

Reference `resolve_data_sources(available, consents, switches, workouts_available)` (vectors
`data_sources.json`) → `{"sources", "tools", "blocked"}`.

A source is effective only when it is **available** (the user has that data or has connected it),
**consented** (`food` needs none), and its switch is not `false`. An omitted switch is on. `blocked`
explains each ineffective source as `unavailable`, `not_consented` or `switched_off`, which is what
the switcher row shows ("Not connected" opens the relevant consent screen). Workout tools ride with
`food` and additionally need `workouts_available`.

`sources` decides the advertised tool list and which `## Data available` prompt lines are emitted.
Turning a source off mid-conversation takes effect on the next request.

The switches are applied **once, at the edge**: `ChatService.sendMessage` (iOS) and
`ChatService.send` (Android) null out the context of every switched-off source before the prompt or
the tools are built, so nothing downstream has to remember to check again. `CoachTools` then runs
`resolve_data_sources` to produce `availableToolNames`, which is what the provider schemas are built
from — a tool for a source the user has not connected is never disclosed, in any of the three
transport formats.

The UI lives in `Views/Coach/CoachComposerSheet.swift` and `ui/coach/CoachComposerSheet.kt`: an
**Attach** group (Camera · Photos · Files · Health record · Note) and a **What Coach can use** group
of four rows. A row shows a switch when the source is available and consented, a **Connect** button
when it is not (which opens that source's own consent, never the switch), and "No data" when there is
nothing to read. A summary line above the composer names the effective sources whenever one is off.

## 9. Prompt gallery

Reference `gallery_for(sources, catalog)` (vectors `prompt_gallery.json`) over
`shared/coach/prompt_gallery.json`. Entries are `{id, category, order, requires, icon, title_en,
prompt_en}`. `id` is the key each platform uses for its localized title and prompt (iOS
`*.xcstrings`, Android `strings.xml`); `title_en` / `prompt_en` are the English source those
resources are seeded from and what the parity test compares.

An entry is offered only when every source in `requires` is effective — hidden, never greyed out, so
the gallery never promises something the user has not connected. Order is `order` ascending then `id`
inside each category, categories in catalog order: nutrition, sleep, training, labs, medications,
planning.

Tapping a card **prefills and focuses** the composer so the user can edit before sending, matching
the §27 hand-off. The empty-state grid and the chip row above the composer draw from the same
catalog, so there is one source of truth — but they **send** on tap, as they always have.

The chips are `chips_for(goal, has_workouts, has_sleep, sources, catalog)` (vectors
`prompt_chips.json`): a sleep and a training prompt in front when there is something to read, then
the weight goal's own four, capped at `MAX_CHIPS` (6). Every id is a gallery entry, so a chip is
gated by exactly the rule above; with nothing connected there are no chips rather than chips that
cannot be answered. A chip shows the entry's short title and sends its full prompt.

| | iOS | Android |
|---|---|---|
| Port | `CR.galleryFor`, `CR.chipsFor` | `CoachReference.galleryFor`, `.chipsFor` |
| Text | `Views/Coach/PromptGalleryText.swift` | `ui/coach/PromptGalleryText.kt` + `values/strings.xml` |
| Screen | `Views/Coach/PromptGalleryView.swift` | `ui/coach/PromptGallerySheet.kt` |
| Entry points | `ChatView` toolbar `square.grid.2x2`, "Browse all prompts" under the empty grid | `CoachScreen` toolbar grid icon, "Browse all prompts" under the empty grid |
| Tests | `PromptGalleryTextTests.swift` | `PromptGalleryTextTest.kt` |

**Suggestions can be turned off.** Settings › AI & Speech › AI Providers › Coach › "Suggested
prompts" (iOS `@AppStorage("coachPromptSuggestions")`, Android
`PreferencesStore.coachPromptSuggestions`, both default **true**) hides the chip row above the
composer and the empty-state grid, including the records chips of §27. The toolbar gallery button and
"Browse all prompts" stay, so turning suggestions off removes the nagging, not the feature.

The gallery is presented **over** the Coach screen on both platforms rather than pushed as its own
route: it has to hand a prompt back to the composer, and sharing the screen's own state is the only
way to do that without a second Coach view model or a global channel.

Accessibility ids: `coach.prompts`, `coach.prompts.open`, `coach.prompts.browse`,
`coach.prompt.<id>`, `settings.coach.suggestions` (iOS) / `settings.row.coachSuggestions` (Android).

## 10. Message actions

Per assistant message: **Copy** (the raw markdown), **Regenerate**, **Share**. Regenerate re-sends
the preceding user turn with the same attachments, records and switches, and writes a new row with
the same `seq`, `variant_index + 1` and `regenerated_from` set to the first variant's id. Nothing is
deleted, so a `‹ 1/2 ›` stepper can walk the versions; the highest `variant_index` is shown by
default.

Per conversation: **Export as Markdown** (title, date, provider, `**You:**` / `**Coach:**` turns,
records used, attachments by name) and **Export as JSON** (one conversation in the §11 shape).

An export never inlines an attachment's text. The excerpt was a redacted copy of a file the user
still has; the transcript names the file and stops there.

Reference: `export_slug`, `conversation_markdown`, `conversation_json`, `regenerate_plan`,
`_latest_variants`, pinned by `shared/coach/test-vectors/export.json`.

| | iOS | Android |
|---|---|---|
| Port | `Coach/Logic/CoachReference.swift` (`CR.exportSlug` …) | `coach/logic/CoachReference.kt` |
| Rows for the reference | `Coach/Models/CoachModels.swift` (`referenceValue`) | `CoachRepository.referenceRow` |
| Action row | `Views/Coach/CoachMessageActions.swift` | `ui/coach/CoachMessageActions.kt` |
| Wiring | `Views/ChatView.swift` (`regenerate`, `exportConversation`) | `ui/coach/CoachViewModel.kt` (`regenerate`, `exportConversation`) |
| Export menu | `Views/Coach/ConversationListView.swift` | `ui/coach/ConversationListSheet.kt` |
| Share | `CoachShareSheet` (`UIActivityViewController`) | `CoachShare` (`ACTION_SEND`, FileProvider `coach_share`) |
| Tests | `CoachExportTests.swift` | `CoachExportTest.kt` |

Accessibility ids: `coach.message.copy`, `coach.message.regenerate`, `coach.message.share`,
`coach.message.variant` (Android also tags the two arrows `.previous` / `.next`),
`coach.conversation.export`.

## 11. Archive `ayuvo-coach-chats` v1

Reference `chat_archive(snapshot)` and `merge_chat_archive(snapshot, archive)` (vectors
`chat_archive.json`). Container, written in the order a single-pass reader needs:

1. `manifest.json` — `{format: "ayuvo-coach-chats", format_version: 1, platform, app_version,
   exported_at, zone_id, counts: {conversations, messages, attachments}}`
2. `conversations.ndjson` — columns `id, title, created_ms, updated_ms, last_message_ms, pinned,
   archived, data_sources, selected_record_ids, provider_override`
3. `messages.ndjson` — columns `id, conversation_id, seq, role, content, created_ms, updated_ms,
   regenerated_from, variant_index, record_refs, attachment_ids`
4. `attachments.ndjson` — columns `id, kind, filename, mime_type, bytes, sha256, page_count,
   char_count, excerpt, created_ms`
5. `attachments/<attachment_id>/<filename>` — the blobs
6. `checksums.json` — `{"<entry>": "<lowercase hex sha256>"}` for every entry except itself; a
   mismatch is a **warning** in the preview, not a rejection

Implementations: `coach/export/CoachChatArchive.kt` (`CoachChatArchiveWriter` / `CoachChatArchiveReader`,
with the zip half testable on its own) and `Coach/Export/CoachChatArchive.swift`. The rows come from
`CoachRepository.snapshotJson()` — **tombstones included**, because the merge has to know what the
user deleted — and go back through `applySnapshot`, which touches only the reference's own columns so
a local blob path and an FTS entry survive an import that changed nothing but text.

Where it is carried:

- **Export All Data** (both platforms): section `coach_chats`, at `coach-chats/ayuvo-coach-chats.zip`.
  Android registers it in `AllDataExportCoordinator` (step `COACH_CHATS`), `AllDataImportPlan` and
  `AllDataImportCoordinator`.
- **Google Drive backup** (Android only): entry `coach-chats/ayuvo-coach-chats.zip` inside
  `ayuvo-backup.zip`, and only while `coachChatBackupEnabled` is on — see §12 and
  `docs/cloud-backup.md`.

Export drops every tombstone and every attachment no exported message references. Order is
conversations by `(created_ms, id)`, messages by `(conversation_id, seq, id)`, attachments by
`(created_ms, id)`, so re-exporting a merged store is byte-identical.

Entries are compact JSON with **sorted keys** (iOS `RJ.jsonText`, Kotlin
`CoachChatArchiveFormat.compact`), so the same store exports to the same bytes on either platform.
`shared/coach/fixtures/ayuvo-coach-chats-fixture.zip` is a small archive written by
`scripts/coach_contract_check.py --write` that neither platform produced; `CoachChatArchiveTests`
and `CoachChatArchiveTest` both read it back and merge it, which is what proves a chat exported on
one phone opens on the other.

Import is **merge**, never delete: upsert by `id`, a newer `updated_ms` wins, a local tombstone always
wins, and a message whose conversation is neither in the archive nor live locally is skipped and
counted (`messages_skipped_orphan`). A missing blob marks the attachment `file_missing` rather than
failing the import. `format != "ayuvo-coach-chats"` → `bad_format`; `format_version > 1` →
`newer_version` ("This file was made by a newer version of Ayuvo"), failing closed exactly like
`DiaryImporter` and `CloudBackupArchive`.

## 12. Migration from `coachChatHistory`

Once, on the first launch after the update, guarded by the `coach_meta` row
`legacy_history_migrated` so it never runs twice: decode the legacy blob, create one conversation
titled by `conversation_title` of the first user message, insert the messages in order with `seq`
1..n, move each inline thumbnail into the attachment store as an `image` attachment, then clear the
preference key. A corrupt blob yields an empty store and is never a crash — the key is cleared either
way, so a bad blob cannot wedge every launch.

Implementations: `Coach/Data/CoachMigration.swift` reads `UserDefaults` `coachChatHistory` (a JSON
array with `attachmentImageData` as base64 `Data`); `coach/data/CoachMigration.kt` reads the DataStore
key of the same name (`attachmentImageBase64`). `PreferencesStore.chatHistory` / `setChatHistory` and
`data/ChatRepository.kt` are gone; only `legacyChatHistoryJson()` and `clearLegacyChatHistory()`
remain, for this migration alone. `coachChatHistory` **stays** in `CloudBackupPolicy.excludedKeys`
even though nothing writes it any more: an upgrading device still holds the old key until this
migration runs, and a restore from an older archive can put it back.

### The opt-in Drive toggle (Android)

`coachChatBackupEnabled` (default **false**) and `coachChatBackupConsentedAt`, set only by the consent
sheet under Settings → Data & Privacy → Backup & Export → Google Drive backup → "Include Coach chats"
(`DataPrivacyPages.kt`, row `cloudBackupChats`). When it is on, `CloudBackupCoordinator` hands
`CloudBackupArchive.pack` the `ayuvo-coach-chats` bytes and `contentHash` folds their digest in, so a
changed transcript still triggers the next auto-backup; when it is off, nothing about the archive
changes. A restore merges through `CoachChatArchiveReader`, never replaces. The toggle governs
Ayuvo's own Drive archive only: the database and the attachment files stay out of Android
auto-backup and device transfer either way. iOS has no cloud backup, so it has no toggle.

## 13. Vectors and the contract check

Envelope `{"format": "ayuvo-coach-vectors", "version": 1, "function": …, "cases": [{"name", "input",
"expected", "notes"?}]}`, 2-space indent, sorted keys, UTF-8 without escapes, trailing newline.
`input` is the source of truth; `--write` only regenerates `expected`.

| File | Function |
|---|---|
| `markdown_blocks.json` | `parse_blocks` |
| `chart_spec.json` | `parse_chart_spec` |
| `chart_repair.json` | `repair_chart_json` |
| `attachment_excerpt.json` | `attachment_excerpt` |
| `conversation_title.json` | `conversation_title` |
| `data_sources.json` | `resolve_data_sources` |
| `prompt_gallery.json` | `gallery_for` |
| `chat_archive.json` | `chat_archive` / `merge_chat_archive` |
| `prompt_chips.json` | `chips_for` |
| `export.json` | `export_slug` / `conversation_markdown` / `conversation_json` / `regenerate_plan` |

Comparison rules for ports: numbers by value, object key order irrelevant, array order significant,
text measured in **code points**. Each platform runs every file (`CoachVectorTests`) with a coverage
test that fails when a vector file has no runner. `scripts/coach_contract_check.py` additionally
verifies the two catalogs, `shared/medications/coach_tools.json`, the schema's table and index names,
the documented constants, regex portability, and the archive round trip.

`scripts/coach_contract_check.py` also rebuilds and validates
`shared/coach/fixtures/ayuvo-coach-chats-fixture.zip` (§11): entry order, entry bytes, the checksums,
that a tombstone and an unreferenced attachment never leave the device, and that merging it into an
empty store inserts 2 conversations, 3 messages and 1 attachment.
## 14. Accessibility identifiers

| Element | Identifier |
|---|---|
| Chat scroll view | `coach.messages` |
| One message | `coach.message.<id>` |
| Copy / regenerate / share | `coach.message.copy` · `coach.message.regenerate` · `coach.message.share` |
| Version stepper | `coach.message.variant` |
| Composer text field | `coach.input` |
| Composer `+` | `coach.attach` |
| Composer sheet rows | `coach.attach.camera` · `.photos` · `.files` · `.record` · `.note` |
| Data switch | `coach.source.<food\|health\|medications\|records>` |
| Attachment chip / excerpt sheet | `coach.attachment.<id>` · `coach.attachment.excerpt` |
| Conversation list | `coach.conversations` · `coach.conversation.<id>` · `coach.newChat` |
| Conversation actions | `coach.conversation.rename` · `.duplicate` · `.export` · `.delete` |
| Prompt gallery | `coach.gallery` · `coach.gallery.<id>` |
| Rendered chart | `coach.chart.<index>` |

## 15. Parity checklist

Same block model, same chart grammar and caps, same redaction, same titles, same switch semantics,
same gallery order, same archive bytes. Same tool names, descriptions and schemas. Same accessibility
identifiers. Same copy for every consent sheet, and no copy that claims data "never leaves the
device" — Coach exists to send it to the user's chosen provider.
