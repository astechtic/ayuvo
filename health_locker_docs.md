You are a senior product architect, UX designer, privacy-focused mobile architect, and mobile engineer. Help me design and implement a new **Health Records** feature for my **Ayuvo health app**.

The goal is to build a fast, simple, private, local-first personal health-record system where users can collect, organize, understand, search, share, receive, and analyze their health documents.

The feature should feel like a **Personal Health Knowledge Base**, not just a document manager.

---

# 1. New Bottom Navigation Tab

Add a new bottom navigation tab:

**Health Records**

This should be a first-class Ayuvo feature.

The default Health Records screen should open in a **Timeline view**.

Users should also be able to switch views, for example:

* Timeline
* List
* Grid

The main page should provide:

* Search
* Filters
* Quick Add
* Recent records
* Important highlights
* Records needing review
* Categories
* Shared/Received records

Keep the primary interface simple and fast.

---

# 2. Local-First Architecture

Design the feature around a **local-first + privacy-first** architecture.

Core health-record functionality should work locally:

* Original files
* OCR output
* Extracted metadata
* AI-generated highlights
* Structured health data
* Search index
* User notes
* Tags
* Relationships
* Record links

Default architecture:

```text
UI
 ↓
ViewModel / State
 ↓
Local Database
 ↓
Local File Storage
 ↓
Processing / AI Layer
```

Cloud services must not be required for basic Health Records functionality.

Do **not** add a separate encryption architecture or encryption layer to this feature.

Keep the implementation lightweight and practical.

---

# 3. AI Choice During Onboarding

Do not force every user to use either local AI or cloud AI.

During Ayuvo onboarding/setup, allow the user to choose their preferred AI processing option.

Example:

**How would you like Ayuvo AI to process your health records?**

* Local AI
* Cloud AI
* Ask me when needed
* Skip AI for now

Remember the user's choice in settings.

The user should be able to change this preference later.

The Health Records system should adapt to that choice.

For example:

### Local AI

Use available on-device AI/OCR/processing.

### Cloud AI

Use the configured cloud AI service when the user has selected it.

### Ask Me

Ask before using AI for a particular record or operation.

### Skip AI

Store and organize the document without AI processing.

Do not create unnecessary repeated AI permission screens when the user has already configured their preference.

---

# 4. Health Record Data Model

Create a clean and extensible database schema.

Each record should support:

### Core

* `id`
* `title`
* `recordType`
* `category`
* `source`
* `createdAt`
* `documentDate`
* `updatedAt`
* `fileType`
* `fileSize`
* `mimeType`
* `filePath`
* `thumbnailPath`
* `checksum`
* `sourceApp`
* `importMethod`
* `processingStatus`
* `reviewStatus`
* `favorite`
* `archived`
* `tags`

### Extracted metadata

Attempt to identify:

* Doctor name
* Doctor specialty
* Hospital / clinic / lab
* Department
* Patient name
* Report name
* Report type
* Test names
* Test values
* Units
* Reference ranges
* Abnormal/critical markers
* Diagnosis
* Symptoms
* Medications
* Procedures
* Recommendations
* Follow-up date
* Visit date
* Sample/collection date
* Report date
* Prescription date
* Admission/discharge date
* Time
* Location

Do not let AI overwrite the original record.

For extracted fields, store useful provenance such as:

* source
* extraction method
* confidence
* user-confirmed state
* source page/location where applicable

Extraction methods can include:

* File metadata
* PDF text extraction
* OCR
* AI
* deterministic parser/rules
* User-entered

---

# 5. Original Record + Structured Data

Always preserve the original document.

Separate the system into three conceptual layers:

### Original Record

The exact imported:

* PDF
* image
* scan
* text document
* attachment

### Structured Understanding

Information extracted from the original:

* metadata
* highlights
* entities
* observations
* measurements
* diagnoses
* medications
* recommendations
* dates

### User Data

Information manually added or changed by the user:

* notes
* tags
* corrections
* links
* custom labels

The original source should always remain accessible.

---

# 6. Supported Record Types

Support importing:

* PDF
* JPG
* PNG
* HEIC
* screenshots
* scanned documents
* text notes
* plain text
* TXT/Markdown where practical
* prescriptions
* lab reports
* discharge summaries
* consultation notes
* imaging reports
* diagnostic reports
* medication lists
* vaccination records
* bills/invoices where useful

Use a flexible record model so new document types can be added later.

---

# 7. Add Record Flow

Create a very fast **Add Record** experience.

Actions:

**Add Record**

* Scan document
* Take photo
* Choose photos
* Choose files
* Import PDF
* Paste text
* Create note
* Import from Share Sheet
* Receive shared record

### Step 1 — Save immediately

As soon as the user imports a file:

**Save the original locally first.**

Do not block saving on AI processing.

### Step 2 — Detect content

Determine:

* file type
* document type
* single vs multi-document
* likely medical category

### Step 3 — Process

Run the processing pipeline based on the user's AI preference:

* metadata extraction
* PDF text extraction
* OCR where required
* classification
* entity extraction
* health-value extraction
* date detection
* highlight generation
* duplicate detection
* relationship suggestions

### Step 4 — Review

Show:

**We found these details**

Example:

Doctor: Dr. Rahul Sharma
Hospital: XYZ Hospital
Report: Complete Blood Count
Date: 12 Sep 2026

Allow the user to:

* Confirm
* Edit
* Ignore
* Add information

Do not force users to confirm every field.

Only surface uncertain or important information for review.

---

# 8. AI Processing Pipeline

Create a safe and lightweight processing pipeline:

```text
Import
 ↓
File Detection
 ↓
Metadata Extraction
 ↓
PDF Text Extraction
 ↓
OCR if needed
 ↓
Document Classification
 ↓
Entity Extraction
 ↓
Structured Data Extraction
 ↓
Highlight Generation
 ↓
Confidence / Review
 ↓
Duplicate Detection
 ↓
Relationship Detection
 ↓
Search Indexing
```

Use deterministic extraction before AI wherever practical.

Avoid unnecessarily processing the entire document with an LLM.

The implementation should remain fast even for large PDFs.

---

# 9. AI Highlights

For every processed medical document, generate concise **Important Highlights**.

Example:

**Important**

* Hemoglobin: 7.6 g/dL — below the report reference range
* Vitamin B12: 180 pg/mL — below the report reference range

**Medications**

* Ferrous ascorbate
* Vitamin B12

**Recommendations**

* Repeat CBC after treatment

Label AI-generated information clearly.

For example:

**AI summary — verify against the original report**

Never replace the original medical information with AI-generated interpretation.

---

# 10. Structured Health Data Points

This is a major part of the feature.

Do not only store documents and summaries.

Extract reusable health data points that can later connect across multiple reports.

Example:

```text
Observation
- metric: Hemoglobin
- value: 7.6
- unit: g/dL
- referenceLow: 12
- referenceHigh: 16
- status: low
- sourceRecordId: ...
- observedAt: ...
- extractionMethod: OCR + AI
- confidence: 0.94
- userConfirmed: false
```

Users should also be able to manually:

* edit a value
* edit a unit
* edit a date
* correct a metric
* add missing data
* remove an incorrect extracted point

The source document remains unchanged.

---

# 11. Longitudinal Health Graphs

Use saved structured data points to create future longitudinal graphs.

For example, from multiple CBC reports:

```text
CBC - July
Hemoglobin: 7.2

CBC - August
Hemoglobin: 8.4

CBC - September
Hemoglobin: 9.7
```

Ayuvo should recognize that these are the same metric and allow the user to see:

**Hemoglobin Trend**

with a graph showing the change over time.

This should work across multiple reports.

The graph system should:

* connect equivalent metrics
* use actual report dates
* retain units
* retain reference ranges where available
* link every data point back to its source document
* allow users to correct or remove an incorrectly linked point

On a report detail page, show relevant trends when enough historical data exists.

Example:

**Hemoglobin**

`7.2 → 8.4 → 9.7`

with:

**View full trend**

---

# 12. Default Timeline View

The Health Records page should default to **Timeline**.

Example:

**September 2026**

September 12
CBC Report
XYZ Hospital
Dr. Rahul Sharma

September 10
Prescription
Dr. ABC

September 02
Vitamin Test

Each timeline item should be easy to open.

Provide view switching:

**Timeline | List | Grid**

Remember the user's preferred view.

Timeline should remain the default for first-time users.

---

# 13. Universal Health Search

Search must be a **Universal Health Search**, not just document filename search.

One search box should search across:

* documents
* OCR text
* titles
* doctors
* hospitals
* report names
* tests
* health values
* medications
* diagnoses
* notes
* tags
* AI highlights
* structured observations

Support queries such as:

> CBC

> Dr. Sharma

> Hemoglobin

> reports from September 2026

> abnormal blood reports

> diabetes reports

> prescriptions from last year

> reports where hemoglobin was low

Where practical, support natural-language/semantic search according to the user's selected AI mode.

Search should feel immediate and local.

---

# 14. Filters

Provide fast filter controls:

* All
* Reports
* Prescriptions
* Lab
* Imaging
* Doctor Notes
* Discharge
* Bills
* Images
* PDFs
* Notes
* Shared
* Received
* Favorites
* Needs Review

Advanced filters:

* Date
* Doctor
* Hospital
* Category
* Report type
* Test
* Abnormal findings
* Tags
* AI processed
* User confirmed

Allow multiple filters together.

---

# 15. Duplicate Detection

Detect likely duplicate imports using:

* file checksum
* file metadata
* image perceptual similarity
* content similarity
* extracted metadata

Show:

**This looks like an existing record**

Options:

* Keep both
* Replace
* Merge
* Cancel

Never silently remove an existing record.

---

# 16. Multi-Record PDF Detection

A single PDF can contain several separate health records.

Example:

A 20-page PDF may contain:

* CBC
* LFT
* Prescription
* Consultation note
* Invoice

Ayuvo should detect likely document boundaries.

Show:

**This PDF may contain 4 separate records.**

Example:

1. CBC Report — pages 1–4
2. LFT Report — pages 5–7
3. Prescription — pages 8–9
4. Consultation Note — pages 10–20

Allow:

**Save as separate records**

or:

**Keep as one document**

or:

**Review boundaries**

Never split silently.

Keep the original combined PDF.

Child records should reference:

* parent record
* page range
* original source document

---

# 17. Sharing Flow

Support:

**Share Record**

* Original PDF
* Image
* Selected pages
* Structured summary
* Selected information
* Multiple records

Before sharing, show:

**What will be shared**

For example:

* Original document
* Doctor
* Hospital
* Patient name
* Test results
* AI highlights
* Notes

Allow the user to deselect information.

Support simple redaction where useful:

* Name
* Address
* Phone
* Patient ID
* Insurance ID
* Other personal identifiers

Show a clear final confirmation before sending.

Keep this flow lightweight.

---

# 18. Receiving Records

Support receiving records through:

* Share Sheet
* Files
* AirDrop
* messaging apps
* email attachments
* browser downloads

On receive:

**Save to Health Records?**

Then:

* save locally
* identify file/document type
* extract metadata
* process AI according to the user's selected AI preference
* detect duplicates
* detect multi-record PDFs

Do not automatically trust sender-provided metadata.

---

# 19. Backup

Provide a simple backup and restore system without making the architecture unnecessarily heavy.

Support practical options such as:

* local backup/export
* optional cloud backup
* restore
* backup status
* backup size
* last backup time

Cloud backup should be an explicit user choice.

Keep backup UX simple.

Do not turn backup into a large security-management workflow.

---

# 20. AI Coach Integration

The existing Ayuvo **AI Coach** should be able to use Health Records.

The user can ask about:

* one report
* multiple reports
* selected records
* specific tests
* date ranges
* categories

Examples:

**Ask about this report**

**Compare with previous report**

**Analyze my latest blood reports**

**Explain changes over time**

**Show my Hemoglobin trend**

**Find abnormal values**

When multiple records are relevant, let the user select which records the AI Coach can use.

Example:

```text
Analyzing:
✓ CBC — Sep 12
✓ CBC — Aug 10
✓ CBC — Jul 18
```

Provide:

**Change records**

The user should always know which health records are being used as AI context.

AI Coach must use the user's configured AI preference unless the user explicitly chooses another option for that interaction.

---

# 21. AI Safety and Source Traceability

AI must never:

* fabricate values
* invent diagnoses
* silently modify structured data
* convert guesses into confirmed facts
* hide contradictions
* replace the original document

Every important extracted value or AI highlight should be traceable to the original record.

For example:

**Hemoglobin: 7.6 g/dL**

Tap → open the original report at the relevant page or source location.

Store:

* source record
* page/location
* confidence
* extraction method

This should also apply to graph data points.

---

# 22. Human Editing and Manual Control

Users must be able to edit almost everything that Ayuvo extracts or organizes.

Allow editing of:

* title
* date
* doctor
* hospital
* report type
* category
* tags
* notes
* extracted values
* units
* linked records
* relationships
* highlights where appropriate

AI should suggest; the user remains in control.

Never modify the original document itself.

---

# 23. Document Detail Screen

Create a clean and fast document detail page.

### Header

* Report title
* Date
* Doctor
* Hospital

### Original Document

PDF/image viewer

### AI Highlights

Important points

### Extracted Information

* Doctor
* Hospital
* Report
* Dates
* Tests
* Medications
* Diagnosis
* Recommendations

### Health Data Points

Show structured values.

Where historical data exists, show:

**Trend / Graph**

Example:

**Hemoglobin**

July 7.2
August 8.4
September 9.7

### User Notes

Personal notes

### Related Documents

At the **bottom of the report detail page**, show related documents.

Examples:

* Previous CBC
* Related prescription
* Doctor consultation
* Follow-up report
* Same hospital
* Same doctor
* Same health episode

Allow the user to manually link additional records.

### AI Coach

**Ask about this report**

### Actions

* Share
* Export
* Edit
* Move
* Tag
* Favorite
* Archive

Keep the page uncluttered and fast.

---

# 24. Related Records

Implement an intelligent but editable relationship system.

Automatically suggest related records using:

* doctor
* hospital
* dates
* report type
* test names
* extracted entities
* similarity
* health episode context

Also allow:

**Link Record**

and:

**Unlink Record**

The user must be able to manually create relationships the AI missed.

Related documents should be visible from the document detail page.

---

# 25. Categories & Organization

Support categories such as:

* Lab Reports
* Prescriptions
* Doctor Visits
* Imaging
* Hospitalization
* Procedures
* Vaccination
* Medication
* Insurance/Bills
* Personal Notes
* Other

Allow custom tags.

AI can suggest categories, but users can always change them.

---

# 26. Health Timeline + Relationships

The timeline should eventually become the central view of the user's health history.

Connect:

```text
Doctor Visit
   ↓
Lab Report
   ↓
Prescription
   ↓
Follow-up Report
   ↓
Updated Health Values
```

Users should be able to navigate naturally between related records.

Keep automatically-created relationships conservative and editable.

---

# 27. Fast and Smooth UX

This is a major requirement.

The entire Health Records feature should feel:

* fast
* responsive
* smooth
* lightweight
* simple
* predictable

Optimize particularly for:

* opening Health Records
* timeline scrolling
* search
* filtering
* opening PDFs/images
* adding a record
* switching views
* editing metadata
* viewing health graphs
* linking records
* sharing
* AI processing status

Do not block the UI while expensive processing runs.

Use background processing wherever appropriate.

Show immediate progress and allow users to continue using the app.

The original document should become available immediately after import.

AI processing can continue in the background.

Design for thousands of records and large PDFs without making normal navigation slow.

---

# 28. Offline-First Behavior

Core functionality should continue to work without internet:

* import
* scan
* view
* search
* timeline
* list
* grid
* edit
* tag
* link records
* structured data viewing
* graphs from existing data
* local AI where selected
* export
* local backup

Clearly communicate whenever a feature requires network access.

---

# 29. Storage Management

Provide a simple:

**Settings → Health Records → Storage**

Show:

* Documents
* Images
* PDFs
* OCR data
* Thumbnails
* AI data
* Backups
* Total size

Support practical cleanup actions such as:

* remove generated cache
* rebuild indexes
* duplicate cleanup
* compact thumbnails where appropriate

Never remove original documents automatically.

---

# 30. Import From Share Sheet

This should be a primary workflow.

Example:

User receives:

`blood_report.pdf`

Then:

**Share → Ayuvo → Save to Health Records**

Immediately show:

**Saved to Health Records**

Then process in the background.

The document should remain usable even if AI processing fails.

---

# 31. Failure Handling

Support imperfect inputs:

* blurry image
* handwritten prescription
* password-protected PDF
* corrupted PDF
* unsupported file
* no text layer
* OCR failure
* incorrect classification
* duplicate
* multi-record document

Never fail the import simply because processing failed.

Example:

**Original saved successfully**

**Some details could not be extracted. You can edit them manually.**

---

# 32. Privacy Transparency

Add a simple section:

**How Ayuvo Handles Your Health Records**

Clearly explain:

* records are stored locally
* what AI mode the user selected
* when online AI is used
* what happens during sharing
* what is included in backups

Keep the explanation user-friendly and avoid excessive security terminology.

Show processing status such as:

**Processed on this device**

or

**Processed using online AI**

depending on the user's selected AI mode.

---

# 33. Recommended Additional Features

Only add the following additional capabilities because they directly improve the Health Records experience:

### A. Related Documents

When viewing a report detail page, show relevant related records at the bottom.

Examples:

* previous reports
* follow-up reports
* prescriptions
* doctor visits
* same test type
* same doctor/hospital

Allow the user to manually link and unlink records.

### B. Universal Health Search

The primary search experience should search across the entire Health Records knowledge base:

* documents
* OCR content
* structured values
* notes
* doctors
* hospitals
* tests
* medications
* AI highlights
* tags

### C. Persistent Health Data Points

Save reusable health observations from reports so Ayuvo can connect the same metric across time.

Example:

CBC → Hemoglobin
CBC → Hemoglobin
CBC → Hemoglobin

Then generate trend graphs on relevant report/detail pages.

### D. User Editing and Manual Linking

The user can manually:

* edit any extracted field
* correct any health value
* change dates
* change doctor/hospital
* change categories
* add notes
* create links between records
* remove incorrect relationships

### E. Performance

Every interaction should be optimized for speed and smoothness.

Do not allow AI processing, OCR, indexing, or graph generation to freeze or slow down the main UI.

---

# 34. Implementation Expectations

Before changing code:

1. Inspect the existing Ayuvo architecture.
2. Identify the existing navigation system.
3. Identify current database/storage infrastructure.
4. Identify existing AI services and onboarding configuration.
5. Identify current file/document handling.
6. Identify Share Sheet/import support.
7. Identify existing AI Coach architecture.
8. Reuse existing infrastructure wherever possible.
9. Avoid introducing unnecessary frameworks or services.
10. Propose schema changes and migrations.
11. Define interfaces between records, structured health data, AI processing, search, graphs, and AI Coach.
12. Define an implementation sequence.
13. Then implement incrementally.

Do not redesign unrelated parts of Ayuvo.

---

# 35. Testing Requirements

Test:

* PDF import
* image import
* text notes
* Share Sheet import
* OCR
* metadata extraction
* AI extraction
* user editing
* low-confidence extraction
* duplicate detection
* multi-record PDF detection
* user-confirmed splitting
* timeline
* list view
* grid view
* view switching
* Universal Health Search
* filters
* health data points
* graph generation
* source traceability
* related records
* manual record linking
* AI Coach context selection
* local AI mode
* cloud AI mode
* ask-before-AI mode
* no-AI mode
* sharing
* receiving
* backup
* restore
* offline operation
* large PDFs
* thousands of records
* failed OCR
* failed AI processing

Pay particular attention to:

**The app must remain fast and responsive while processing documents in the background.**

---

# 36. Deliverables

Produce a complete product and engineering plan containing:

### Product

* feature definition
* user journeys
* navigation
* screen map
* user flows
* edge cases

### UX

For each screen:

* purpose
* layout
* components
* actions
* empty state
* loading state
* error state
* processing state

### Architecture

* system architecture
* database schema
* relationships
* migrations
* file structure
* processing pipeline
* search architecture
* health-data architecture
* graph architecture
* AI Coach integration
* backup architecture

### AI

* OCR strategy
* extraction strategy
* AI highlights
* confidence
* source traceability
* local/cloud/ask/no-AI modes
* semantic search where appropriate

### Implementation

Provide:

* recommended project structure
* modules
* models
* repositories
* services
* protocols/interfaces
* database migrations
* processing jobs
* background processing strategy
* UI state management

### Performance

Define how the feature should remain responsive with:

* thousands of records
* large PDFs
* OCR
* AI processing
* full-text search
* graph generation
* thumbnail generation

### Phased Rollout

Prioritize:

**Phase 1 — Foundation**

Health Records tab + timeline + list/grid + local storage + import + viewer + basic metadata.

**Phase 2 — Intelligence**

OCR + AI extraction + highlights + review + Universal Health Search + filters.

**Phase 3 — Health Knowledge Base**

Structured health data points + graphs + related records + manual linking/editing.

**Phase 4 — AI Coach**

Single/multi-record analysis + health-history context + comparisons + trend understanding.

**Phase 5 — Sharing & Backup**

Sharing + receiving + simple backup/restore flows.

Do not over-engineer Phase 1.

---

# 37. Critical Product Rules

These are non-negotiable:

1. **Original documents remain the source of truth.**
2. **AI-generated information is never automatically treated as authoritative.**
3. **Users can edit extracted information whenever they want.**
4. **Users can manually link and unlink records.**
5. **Health data points must remain connected to their source records.**
6. **Universal Health Search is the primary search experience.**
7. **Timeline is the default Health Records view.**
8. **Users can switch between Timeline, List, and Grid.**
9. **Multi-record PDFs must never be silently split.**
10. **Import must succeed even if OCR or AI processing fails.**
11. **AI processing must follow the user's selected onboarding preference: Local AI, Cloud AI, Ask Me, or No AI.**
12. **Do not force users through repeated AI-choice flows when their preference is already configured.**
13. **Core record management must work offline.**
14. **Related documents must be shown at the bottom of the report detail page.**
15. **Health observations should be reusable across reports for longitudinal graphs.**
16. **The UI must remain fast and smooth while processing occurs in the background.**
17. **Do not add unnecessary encryption/security workflows to this feature.**
18. **Do not add a Delete & Recovery system to this feature.**

---

## Final Goal

Build Ayuvo Health Records as a:

**Fast + Local-First Health Record Vault + Universal Health Search + Structured Health Knowledge Base + AI-Assisted Health History**

The ideal experience should feel like:

> “All my health records are organized in one place, I can quickly find anything, see my health history as a timeline, connect reports and health values over time, view trends, edit anything that is wrong, share what I choose, and ask Ayuvo AI to understand one or many records.”

Start by inspecting the existing Ayuvo codebase and architecture. Then propose the architecture, UX, database model, AI processing design, search strategy, health-data graph model, and phased implementation plan before making changes.
