# AI models — cross-platform contract

Plan: `/Users/macbook/.claude/plans/fluffy-popping-wreath.md`. Shared files live in `shared/ai/`;
the downloadable-model catalogue lives in `local-models/catalog.v2.json`. The executable reference
is `scripts/ai_reference.py`; `scripts/ai_contract_check.py` validates the vectors in
`shared/ai/test-vectors/` (`--write` regenerates every `expected` from the reference), and
`local-models/verify_catalog.py --online` is what pins a new artifact before anyone is offered it.
Where this prose and the reference disagree, the reference wins and the prose is fixed. Change the
shared files first, then both platforms in the same change.

This document owns **which model answers**: the provider registry, the saved model profiles, the
four roles, credential storage, Google Vertex AI, the on-device catalogue, and the per-conversation
override the Coach model picker writes. It does **not** restate what a request contains —
`docs/coach.md` owns the chat itself, `docs/health-data.md` §7 and `docs/health-records.md` §26–§30
own the tool contracts and their consent.

## 1. Rules that never bend

1. **A profile is never silently repointed or silently substituted.** Deleting a model or a profile
   rewrites every role that referenced it to a visible, explicit choice, and a profile that cannot
   answer produces a refusal naming it — never an answer from some other provider. An on-device
   request that quietly fell back to a cloud model would put health data on someone else's server
   without a word.
2. **Credentials are never copied between profiles.** A profile either owns a key or inherits the
   provider's. Deleting a profile deletes only its own key.
3. **A key is never deleted on the profile layer's authority.** `apikey_<provider>` has owners
   outside it — onboarding, the legacy per-provider screen, and speech-to-text, which falls back to
   the matching AI provider's key. Only "Delete all data" clears that namespace.
4. **An artifact that is partial, unverified, ineligible, gated without a token, or deleted is never
   selectable.** Blocked models are listed with their reason, not hidden and not silently started.
5. **Onboarding writes the legacy flat keys and nothing else.** The profile list is derived from
   them, never the reverse (§4).
6. **A chat's model choice belongs to that chat.** Picking a model in the Coach menu changes that
   conversation. Making it the app default is a separate, labelled action.
7. **Nothing here weakens a health guardrail.** A medical model answering does not make Coach a
   clinician: describe, never diagnose, never suggest starting, stopping or changing a dose, defer
   to the prescriber (`docs/medications.md`, `docs/health-records.md`).

## 2. The provider registry

`shared/ai/providers.json` is the registry both platforms are checked against. Its `id` is the
**provider token**: the iOS `rawValue` and the Android `@SerialName`, which are equal by contract
(`"Google Gemini"`). Android's enum *name* (`GEMINI`) is a platform detail that never appears in
shared data — it is what Android's own flat preference keys and `apikey_` accounts use, and the
mapping happens at the boundary.

Each row carries `api_format` (`on_device`, `litert_local`, `gemini`, `anthropic`,
`openai_compatible`), `base_url`, the four capability flags (`requires_key`,
`requires_custom_endpoint`, `supports_custom_model_name`, `configurable_timeout`),
`supports_vision`, and the model lineups. `platforms` is per row because Apple Intelligence is
iOS-only.

The top-level `token_limit` object owns the one rule that is easy to get subtly wrong: OpenAI always
takes `max_completion_tokens`; a custom OpenAI-compatible endpoint takes it when the model id's last
`/`-separated component starts with `gpt-5`, `o1`, `o3` or `o4`; everything else takes `max_tokens`.
`token_limit_key(provider, model)` is the only implementation of it.

Two rows are special:

- **`Gemma 4 E2B (On-Device)`** keeps that token because the token is the persistence key, but its
  display name is "On-device model" and its model list is no longer one hardcoded id — it is the set
  of **installed** catalogue models (§6), so the existing `selectedAIModel` string finally carries
  meaning and both dispatchers must pass it to the runtime instead of ignoring it.
- **`Google Vertex AI`** is added by §5 in the phase that ships it; `shared/ai/vertex.json` already
  holds its routing.

A known defect is recorded rather than quietly fixed: Ollama's text lineup is `literal + models`, so
`qwen3.8` and `gemma4` appear twice in its picker. The registry describes what the app does.

## 3. Model profiles

A profile is a configuration the user named:

```json
{"id": "aip_0001", "nickname": "Work OpenAI", "provider": "OpenAI", "model_id": "gpt-5.4-mini",
 "base_url": null, "vertex": null, "credential_ref": "provider:OpenAI",
 "created_ms": 0, "updated_ms": 0}
```

- `id` is `aip_` + four digits, allocated in creation order. **Not a UUID**: the vectors have to be
  byte-stable, and the id becomes part of a credential account name that both platforms must spell
  the same way.
- `nickname` is `"<provider> · <model>"` unless the user renames it, with `" (2)"` appended on a
  collision. Two profiles can only collide when provider and model match and the base URL differs.
- `credential_ref` is exactly one of `provider:<token>`, `profile:<id>` or `none` (§4).
- `vertex` carries `{project_id, location}` for Vertex profiles and is `null` everywhere else.

Four **roles** point at profiles: `image` (the primary, used for every request carrying an image),
`text` (used for text-only requests when enabled), `image_fallback` and `text_fallback`. Each role
is `{profile_id, enabled}`. This is the same four-way structure the app has today; what changes is
that a role names a profile instead of inlining a provider, a model and a base URL.

## 4. Where the old keys go

Profiles are the source of truth. The legacy flat keys — `selectedAIProvider`, `selectedAIModel`,
`separateTextProviderEnabled`, the two fallback pairs and their enable flags, `customBaseURL_<p>`
and `fallbackCustomBaseURL_<p>` — become a **write-through mirror**, re-projected in the same
transaction as every profile mutation. Every existing reader keeps working untouched:
`GeminiService.dispatch`, `RecordsAIExtractor`, `FoodAnalysisService`,
`setInitialSpeechProviderForAIProvider`. (No widget or app extension reads AI settings, so there is
no cross-process mirror to maintain.)

`project_legacy(profiles, roles)` is that projection. It is lossy on purpose: nickname, id,
`vertex{}`, `credential_ref` and any second profile on one provider have no flat home, and nothing
that reads the flat keys wants them.

**Onboarding is the one inbound channel.** It is unchanged: it still asks for exactly one model and
still writes only the flat keys. A new preference, `aiPrimaryProjectedFingerprint`, records the
`provider\0model\0base_url` triple the mirror last wrote. At launch:

| Observed flat primary | Action |
|---|---|
| equals the fingerprint | profiles win; re-project (a no-op) |
| differs | some external writer changed it — which can only be onboarding — so `adopt_legacy_primary` find-or-creates a profile for it and points the image role there |

Find-or-create uses the migration's dedupe key, so the user's onboarding choice is **adopted, never
ignored and never duplicated**. When onboarding is eventually allowed to change, delete this inbound
channel first, keep the outbound mirror for one release, then drop it.

### The one-shot migration

`migrate_profiles(payload)` converts the four legacy slots into profiles, guarded by
`aiProfilesMigrationVersion` (currently 1) and run **after** the existing
`migrateLegacyGeminiModelsIfNeeded` / `migrateFallbackBaseUrls`, so the model-registry upgrades land
in the profiles rather than after them.

- Slots are processed in the fixed order `image, text, image_fallback, text_fallback`; profile order
  is creation order. The output is a deterministic function of the input — no sort, no clock
  (`now_ms` is an input).
- The dedupe key is `(provider, trimmed model, trimmed base_url)`. Primary and fallback on one
  endpoint become **one** profile; Gemini Pro primary with Gemini Flash fallback stay two.
- `customBaseURL_<p>` and `fallbackCustomBaseURL_<p>` are both carried into their respective
  profiles. Dropping the second would silently point a self-hosted fallback at the primary's server.
- A disabled fallback is still materialised, with `enabled: false`. Skipping it would mean toggling
  it back on in Settings silently resolves somewhere else.
- A slot naming an unknown provider, or carrying no model, is dropped; if that leaves the image role
  empty, the Gemini default is synthesized, because resolution must never find no primary.
- Every migrated profile gets `credential_ref = "provider:<token>"`. That single line is the whole
  backward-compatibility story: nobody re-enters a key.
- `migrate(migrate(x)) == migrate(x)`.

### Credentials

| | iOS Keychain account | Android EncryptedSharedPreferences key |
|---|---|---|
| Legacy, per provider | `apikey_<rawValue>` | `apikey_<ENUM_NAME>` |
| New, per profile | `aiprofilekey_<profileId>` | `aiprofilekey_<profileId>` |

`resolve_credential(ref, requires_key, has_profile_key, has_provider_key)`:

- no key needed, or `none`, or empty → `none`
- `profile:<id>` → `profile` if that key exists, else **`provider`** if the provider key exists, else
  `missing`
- `provider:<t>` → `provider` if that key exists, else `missing`

The profile→provider fall-through is load-bearing: `KeyStore.openOrRecover` deliberately wipes every
key after an `AEADBadTagException`, and without it a profile whose own key vanished would be
permanently dead rather than merely back on the provider key.

Writing a key in the profile editor: a value **equal** to the provider's existing key keeps
`provider:` and writes nothing — re-pasting the same key must not fork a credential that a later
rotation would then miss. A different value writes `aiprofilekey_<id>` and never touches the
provider key. Clearing the field deletes the profile key and reverts the ref.

`keys_to_delete(profile, remaining)` always returns an empty `provider_keys`. See rule 3.

Vertex profiles store `{project_id, location}` in the profile and the service-account JSON under the
same `aiprofilekey_<id>`, so the lookup rule is unchanged. Their `credential_ref` is always
`profile:`, because two Vertex projects is the motivating case and there is no meaningful
provider-level Vertex key to inherit.

**Data reset** (`AIProviderSettings.deleteAllData()`, Android `PreferencesStore.clearAll()` +
`KeyStore.clearAll()`) must also clear the profile list, the role pointers, the fingerprint, every
`aiprofilekey_*` and `aiProfilesMigrationVersion` — and the two migration-version keys
`deleteAllData()` forgets today (`geminiModelMigrationVersion`, `aiModelRegistryMigrationVersion`).
With the version cleared, a reset followed by onboarding re-runs the migration and yields exactly
one profile.

## 5. Role resolution

`resolve_role(payload)` replaces `AIProviderSettings.currentConfig(requiresVision:)` and the inline
block in Android's `ChatService.send`. `payload.env` carries everything the pure function may not
look up itself — `platform`, `requires_vision`, `local_models`, `providers_with_keys`,
`profiles_with_keys`, `on_device_available`, `max_response_tokens`, `request_timeout_seconds`, and
optionally a `provider_defaults` map a port can pass from its own enum.

`platform` is not cosmetic. The registry is cross-platform and lists Apple Intelligence, which only
iOS has; without the filter an Android migration would happily build a profile for it and then be
unable to resolve a route to it ever again. Every migration and every resolution names its platform,
and a vector that omits one is deliberately platform-agnostic.

Which role: an image request always uses `image`; a text request uses `text` when it is enabled,
otherwise `image`. Unchanged.

Guards, in order. Each **blocks** the chosen profile and says why:

| Guard | `blocked` |
|---|---|
| vision required, provider cannot | `no_vision` |
| a `litert_local` profile whose model is not installed, or an `on_device` profile the platform says is unavailable | `model_not_installed` |
| vision required, the installed local model has no vision tower | `no_vision` |
| a key is required and neither store has one | `no_key` |
| the effective base URL is empty on a network provider | `no_base_url` |
| the role points at a profile that is not in the list | `no_profile` |

**Resolution never substitutes a different profile for the one the user chose.** A blocked route
still comes back — the caller needs something to name in the refusal — but it carries `blocked` and
the caller surfaces the error instead of sending the request. This is rule 1, and it matters most
for the case that looks most helpful: quietly answering an on-device request from a cloud profile
would put health data on someone else's server without a word.

The one route that answers without a chosen profile is the synthesized default on a device where
**no profiles exist at all and no role points anywhere** — what the app shows today before the first
key is entered. It may carry a `missing` credential, and the transport raises the same "no API key"
error it does today. Any other unblocked route must have a credential, and the contract check
asserts both halves.

`resolve_fallback` returns a route or nothing, never a fall-through. It returns nothing when the
role is disabled, its profile is gone, a guard fails, or the route matches the primary on the triple
`(provider, model, effective base_url)`. **Compare the endpoint, not the profile id**: two profiles
can describe one endpoint, and retrying the request that just failed is not a fallback. An image
request uses the image pair, anything else the text pair — unchanged.

`RecordsAiModeResolver` must call the resolver rather than re-deriving the selection as it does
today. If the two drift, the Records UI names a cloud provider that a real call would never use.

## 5b. Settings

Settings › AI & Speech › AI Providers opens with a **Models** group: every saved profile, its
provider and model, and which roles it serves. Tapping one edits it; "Add model" creates a
configuration that no role uses yet, ready for the chat picker. Deleting one takes its role pointers
with it and removes only its own key.

The four role groups below it are unchanged to look at, and they still edit the model that role
runs. What changed is where their writes land: **every setter on `AIProviderSettings` /
`PreferencesStore` now folds its write into the profile store** (`sync_role_from_legacy` →
`assign_role`). That is the seam that keeps the mirror honest — the old rows, onboarding and
anything else that still assigns `selectedAIProvider` all end up creating or editing the right
profile without knowing profiles exist.

Two rules that seam has to respect:

- **A role's on/off flag is an input, never an output.** `separateTextProviderEnabled` and the two
  fallback flags are written by their own toggles; the projection must carry them, not overwrite
  them, or flipping a toggle and then changing a provider would silently turn it back off.
- **Only the role that changed is mirrored.** Rewriting all four slots from the store would undo a
  slot somebody set directly since the last sync.

`assign_role` is copy-on-write: editing the primary never changes a fallback that happens to share
its profile. The four separate flat slots used to guarantee that, and the profile layer keeps the
guarantee. Editing in place keeps the profile id so a conversation pinned to it follows the change;
changing the provider resets `credential_ref` and reports the profile key to delete, because a key
issued for one provider is meaningless at another.

## 6. Google Vertex AI

One provider, three transports. `shared/ai/vertex.json` holds the table and
`vertex_endpoint(project, location, model_id)` is the only implementation.

Auth uses no new dependency: the service-account JSON becomes an RS256 JWT (`iss` = `client_email`,
`scope` = `https://www.googleapis.com/auth/cloud-platform`, `aud` = the token endpoint, 1 h),
exchanged for an access token cached until 60 s before expiry. Signing is platform crypto —
`SecKeyCreateSignature` with `.rsaSignatureMessagePKCS1v15SHA256` on iOS, `SHA256withRSA` +
`PKCS8EncodedKeySpec` on Android. The JSON never leaves the keychain and is never logged.

Host by location: `global` → `https://aiplatform.googleapis.com`; `us` / `eu` →
`https://aiplatform.{loc}.rep.googleapis.com`; anything else →
`https://{loc}-aiplatform.googleapis.com`. Path prefix is always
`/v1/projects/{project}/locations/{location}`. Header is always `Authorization: Bearer <token>`.

| Model id | Path | Transport | Body |
|---|---|---|---|
| `gemini-…`, `google/…` | `/publishers/google/models/{model}:generateContent` | the Gemini client | model is in the path, not the body |
| `claude-…`, `anthropic/…` | `/publishers/anthropic/models/{model}:rawPredict` | the Anthropic client | `anthropic_version: "vertex-2023-10-16"`, **no** `model` field |
| anything else | `/endpoints/openapi/chat/completions` | the OpenAI-compatible client | model is in the body |

The third route is the least documented of the three. Confirm it with a live call before it is
offered in the preset menu; if it does not hold, drop the route rather than guess at it.

## 7. On-device models

`local-models/catalog.v2.json` and `local-models/README.md` own the artifacts, their checksums, the
derived RAM gate and the gated-artifact rules. Three consequences reach this contract:

1. **Several chat models can be installed at once**, but the runtime holds **one** engine, closing
   the previous one on switch. `LocalGemmaRuntime` becomes a model-keyed `LocalLlmRuntime`; iOS
   grows the `LocalModelDescriptor` / generic manager Android already has, with
   `Gemma4LocalModelManager` left as a thin shim **so that `Gemma4ModelSettingsView`, which
   onboarding embeds, does not change.** Android's `LocalModelRow` keeps its signature for the same
   reason. The multi-model Settings UI is built from new components.
2. **Context length is per model**, clamped from the catalogue rather than the engine's global 4096.
   MedGemma's build exports a 2048-entry KV cache.
3. **Vision is per model.** Qwen3 has no vision tower, so a Qwen3 profile serves the text role and
   never the image role — a distinction the provider-level `supports_vision` flag cannot make.

Both platforms now run the catalogue rather than one baked-in artifact:

| | iOS | Android |
|---|---|---|
| Descriptors | `AI/Logic/LocalModelCatalog.swift`, parsed from the bundled `models_catalog.json` | `services/ondevice/LocalModelCatalog.kt` |
| Manager | `Gemma4LocalModelManager` takes a descriptor; `manager(for:)` keeps one per model, and Gemma keeps its existing directory so an install already on the phone is found where it is | `LocalModelManager` was already keyed |
| Runtime | one engine at a time, per-model `maxNumTokens` | same; switching closes the previous engine first |
| Installed set | `AIProvider.gemma4Local.models` is `installedChatModelIDs` | `InstalledLocalModels.ids`, published by the app |
| Token | `Gemma4LocalModelManager.huggingFaceToken` (Keychain) | `KeyStore.huggingFaceToken()` |

`local_catalog_state(payload)` is what Settings renders: each model resolves to `installed`,
`available`, `needs_token` or `ineligible` (`low_ram_device`, `unsupported_abi`,
`insufficient_memory`, `insufficient_space`), and rows are ordered installed → available →
needs_token → ineligible, keeping catalogue order inside each group.

Gated artifacts send `Authorization: Bearer <Hugging Face token>` from a new secure value stored
beside the AI keys. Without a token the row is blocked with its reason — never hidden, and never
started and failed a third of the way through a 3 GB download.

## 8. The per-conversation override

The Coach top bar gains an overflow menu whose **Model…** entry lists the saved profiles and the
installed local models. Picking one writes that conversation's `provider_override`; a separate
"Use for new chats too" row updates the `image` role.

There is **no schema change**. `conversations.provider_override` is already TEXT and
`shared/coach/schema.sql` is byte-compared by a parity test on both platforms.

| Form | Written by |
|---|---|
| `Gemma 4 E2B (On-Device)` — a bare provider token | the Health-Records "Use on-device Coach" flow, unchanged |
| `profile:aip_0004\|OpenAI` | the new model picker |

The provider is carried **after** the id on purpose: this column travels in the chat archive
(`CONVERSATION_COLUMNS` in `scripts/coach_reference.py`), so a conversation imported onto another
device names a profile that does not exist there and must degrade to the provider rather than
quietly answer on the wrong model. It also lets the Coach header name the provider before the
profile store has loaded. No provider token may contain `|`; the contract check asserts it.

Resolution precedence at send time: profile id (still subject to the §5 guards, so a stale local
override on a device where that model was deleted degrades instead of throwing) → provider token →
an ephemeral route from provider defaults → normal role resolution.

**A pinned conversation never falls back and is never substituted.** It resolves through the same
guards as a role (§5), so a model that cannot answer produces a refusal that names it — "… has no
API key", "… is not installed on this device", "… cannot read images" — each ending with "pick
another model for this chat". The fallback roles are skipped entirely: the user named the model this
chat must use.

Android carries a real bug here that this work fixes: `CoachViewModel.providerOverride` is an
in-memory field that is never persisted and never rehydrated, so an on-device Coach conversation
silently reverts to the cloud provider after process death or a conversation switch. The repository
and the model already carry the column; the ViewModel must persist through
`CoachRepository.updateConversation`, rehydrate on open and on switch, and stop throwing the model
away in `ChatService.send`.

## 9. Vectors

`shared/ai/test-vectors/`, envelope `{"format": "ayuvo-ai-vectors", "version": 1, "function",
"cases"}`, canonical formatting (sorted keys, two-space indent, no escapes, trailing newline). Both
platforms run every file and a coverage test fails when a file has no runner.

| File | Function |
|---|---|
| `profile_migration.json` | `migrate_profiles` |
| `adopt_legacy_primary.json` | `adopt_legacy_primary` |
| `legacy_projection.json` | `project_legacy` |
| `role_assignment.json` | `assign_role` |
| `profile_add.json` | `add_profile` |
| `role_resolution.json` | `resolve_role` |
| `fallback_resolution.json` | `resolve_fallback` |
| `credential_lookup.json` | `resolve_credential` |
| `profile_delete.json` | `keys_to_delete` |
| `vertex_endpoint.json` | `vertex_endpoint` |
| `conversation_override.json` | `parse_override` / `encode_override` |
| `token_limit.json` | `token_limit_key` |
| `local_catalog.json` | `local_catalog_state` |

## 10. Accessibility ids

`settings.models`, `settings.model.<profileId>`, `settings.model.add`,
`settings.role.<primary|text|imageFallback|textFallback>`, `settings.localModel.<id>`,
`settings.hfToken`, `coach.menu`, `coach.model.picker`, `coach.model.<ref>`, `coach.model.default`.

## 11. Parity checklist

- [ ] `shared/ai/providers.json` matches both enums field by field (models, lineups, flags, base URLs).
- [ ] `local-models/catalog.v2.json` matches both platforms' descriptors byte for byte.
- [ ] Every vector file has a runner on both platforms, and the coverage test proves it.
- [ ] Profile ids, `aiprofilekey_` account names and the override encoding are spelled identically.
- [ ] The flat-key mirror is re-projected on every profile mutation, asserted by a test on each platform.
- [ ] Onboarding is untouched: `OnboardingView.swift`, `ui/onboarding/*`, `Gemma4ModelSettingsView`
      and `LocalModelRow`'s signature are unchanged, and a fresh install still offers one model.
