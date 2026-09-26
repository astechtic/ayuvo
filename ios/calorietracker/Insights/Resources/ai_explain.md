# Ayuvo Insights — AI explanation prompt (v1)

Contract: `docs/insights.md` (AI explanation). Payload: `scripts/insights_reference.py` `ai_payload` + `canonical_json`. Prompt assembly: `build_prompt`. Validation: `validate_ai_output`. Vectors: `test-vectors/ai_summary.json` (exact payload, exact prompt text and validator results).

"Explain with AI" runs only when the user taps it. The numbers on screen always come from the deterministic engines; the model only puts them into words. When the answer fails validation, the app shows the deterministic text instead and says so.

Both platforms embed the text between the ```` ``` ```` fences below verbatim (no trailing newline inside a block) and fill the user template with plain string replacement (no templating engine): `{task}` first, then `{payload}`, each exactly once, so text inside the payload is never re-substituted.

## Placeholders
- `{task}`: `ai.tasks[kind]` from `insights_config.json`, where kind is `recovery`, `health_age` or `daily_review`.
- `{payload}`: `canonical_json({kind: payload[kind]})`, plus `"patterns": payload.patterns` for `daily_review`. Canonical JSON: keys sorted, no whitespace, integral numbers as integers, other numbers with at most 2 decimals and no trailing zeros, strings escaped as `\"` `\\` `\n` `\r` `\t` and `\u00XX` for other control characters, non-ASCII written as is.
- The payload holds only derived values (scores, labels, contributor texts, deviations, review items, surfaced patterns). It never holds raw samples, timestamps, dates or names.

## Which variant
| Route | System prompt | Max output tokens |
|---|---|---|
| `cloud` (BYOK text provider) | cloud | `ai.max_output_tokens.cloud` (600) |
| `local` Gemma / Apple Intelligence | compact | `ai.max_output_tokens.local` (300), never more than the context allows |

The text role is resolved the same way as the other AI features (iOS `AIRoleResolver`; Android `AIRoleResolver`). There is no silent fallback, in particular never from on-device to cloud. Status line after success: `ai.status_labels.local` or `ai.status_labels.cloud` with `{provider}` replaced by the provider's display name.

## System prompt (cloud)
```
You explain wellness numbers from a person's own health app in plain, friendly English.
Return ONLY one JSON object, no markdown, no prose, exactly this shape:
{"headline":"","bullets":[]}

Rules:
1. Explain only what the data in the message says. Do not add new facts, causes or numbers.
2. Use only numbers that appear in the data. Do not calculate, convert or round new numbers.
3. Never diagnose, name a medical condition or suggest one might be present.
4. Never give medication advice or mention doses.
5. Describe links between habits and results as associations ("has been linked in your data with", "on days when"), never as causes.
6. Suggestions are gentle everyday habits (sleep, movement, food, water, rest) and only when the data supports them.
7. headline: one sentence, at most 100 characters. bullets: 1 to 5 short sentences, each at most 180 characters.
8. If a section has a status other than "ok", say briefly that more data is needed.
```

## User template
```
Task: {task}

Data:
{payload}

Return the JSON object.
```

## System prompt (compact, on-device)
```
Explain these wellness numbers in plain English. Output only JSON: {"headline":"","bullets":[]}
headline: 1 sentence, max 100 characters. bullets: 1-5 short sentences, max 180 characters each.
Use only numbers from the data. No diagnosis, no medical conditions, no medication or dose advice. Say "linked with" or "on days when", never "caused". If status is not "ok", say more data is needed.
```

## What the validator does with the answer
- Lenient parse: a ```` ``` ```` fence is stripped when present, then the first balanced `{...}` (string and escape aware) is parsed; anything else → `parse_error`.
- `headline` must be a string and `bullets` a list of strings, else `bad_shape`. Both are trimmed.
- Lengths are counted in UTF-16 code units: headline 1–100 (`headline_length`), 1–5 bullets (`bullet_count`), each bullet 1–180 (`bullet_length`).
- Every number in the text (`[0-9]+(,[0-9]{3})*(\.[0-9]+)?`, grouping commas removed, rounded to 2 decimals) must be a number of the payload (as is, or rounded to 1 or 0 decimals, sign ignored), a number printed inside a payload string, or one of `ai.allowed_numbers` (100); otherwise `unknown_number`.
- Any of `ai.blocked_terms` (case-insensitive) → `blocked_term`.
- Result `{ok, errors, output}`; the explanation is shown only when `ok`.
