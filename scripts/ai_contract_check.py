#!/usr/bin/env python3
"""Ayuvo AI contract check (Python 3 stdlib only).

    python3 scripts/ai_contract_check.py           # verify; exit 1 on any problem
    python3 scripts/ai_contract_check.py --write   # rewrite every vector's "expected" from the reference

Checks:
  1. shared/ai/test-vectors/*.json: envelope shape, unique case names, stable formatting (sorted keys,
     2-space indent, UTF-8 without escapes, trailing newline), and every case's "expected" equals
     scripts/ai_reference.py run on its "input" (inputs are the source of truth; --write only replaces
     "expected"). Every file in EXPECTED_FILES must exist and no unknown file may be present.
  2. Output shapes per function.
  3. shared/ai/providers.json: canonical format, unique tokens, no token containing the override
     separator, coherent flags, and an api_format from the documented set.
  4. shared/ai/vertex.json: a host rule for every location form, a catch-all route, placeholders intact.
  5. local-models/catalog.v2.json: schema 2, unique ids, hex sha256, url built from repository +
     revision + filename, the documented memory-gate rule, and a licence URL on every gated artifact.
  6. Portability lint: the reference owns no regexes (ports must not need one either).
  7. A unittest suite with hand-computed expectations for the rules that fail silently.
"""

import glob
import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHARED = os.path.join(ROOT, "shared", "ai")
VECTORS = os.path.join(SHARED, "test-vectors")
PROVIDERS = os.path.join(SHARED, "providers.json")
VERTEX = os.path.join(SHARED, "vertex.json")
CATALOG = os.path.join(ROOT, "local-models", "catalog.v2.json")

sys.path.insert(0, HERE)
import ai_reference as R  # noqa: E402

FORMAT = "ayuvo-ai-vectors"
EXPECTED_FILES = {
    "profile_migration.json": "migrate_profiles",
    "adopt_legacy_primary.json": "adopt_legacy_primary",
    "legacy_projection.json": "project_legacy",
    "role_assignment.json": "assign_role",
    "profile_add.json": "add_profile",
    "role_resolution.json": "resolve_role",
    "fallback_resolution.json": "resolve_fallback",
    "credential_lookup.json": "resolve_credential",
    "profile_delete.json": "keys_to_delete",
    "vertex_endpoint.json": "vertex_endpoint",
    "conversation_override.json": "override",
    "token_limit.json": "token_limit_key",
    "local_catalog.json": "local_catalog_state",
}
API_FORMATS = ("on_device", "litert_local", "gemini", "anthropic", "openai_compatible")
PROFILE_FIELDS = {"id", "nickname", "provider", "model_id", "base_url", "vertex", "credential_ref",
                  "created_ms", "updated_ms"}
DOCUMENTED = {
    "ROLES": ("image", "text", "image_fallback", "text_fallback"),
    "MIGRATION_VERSION": 1,
    "PROFILE_ID_PREFIX": "aip_",
    "PROFILE_ID_DIGITS": 4,
    "OVERRIDE_PREFIX": "profile:",
    "OVERRIDE_SEPARATOR": "|",
    "DEFAULT_PROVIDER": "Google Gemini",
    "MAX_PROFILE_ID_LENGTH": 64,
}
HEX = "0123456789abcdef"


def dumps(obj):
    return json.dumps(obj, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def _first_diff(a, b, path="$"):
    if type(a) != type(b) and not (isinstance(a, (int, float)) and isinstance(b, (int, float))):
        return "%s: %r != %r" % (path, a, b)
    if isinstance(a, dict):
        for k in sorted(set(a) | set(b)):
            if k not in a or k not in b:
                return "%s.%s: missing on one side" % (path, k)
            d = _first_diff(a[k], b[k], "%s.%s" % (path, k))
            if d:
                return d
        return None
    if isinstance(a, list):
        if len(a) != len(b):
            return "%s: length %d != %d" % (path, len(a), len(b))
        for i, (x, y) in enumerate(zip(a, b)):
            d = _first_diff(x, y, "%s[%d]" % (path, i))
            if d:
                return d
        return None
    return None if a == b else "%s: %r != %r" % (path, a, b)


# ---------------------------------------------------------------------------------------------
# Shared catalogs
# ---------------------------------------------------------------------------------------------

def check_providers(problems):
    raw = open(PROVIDERS, encoding="utf-8").read()
    doc = json.loads(raw)
    if raw != dumps(doc):
        problems.append("providers.json: not in canonical format")
    if doc.get("format") != "ayuvo-ai-providers" or doc.get("version") != 1:
        problems.append("providers.json: bad envelope")
    seen = set()
    for row in doc["providers"]:
        pid = row.get("id")
        where = "providers.json/%s" % pid
        if pid in seen:
            problems.append("%s: duplicate token" % where)
        seen.add(pid)
        if R.OVERRIDE_SEPARATOR in (pid or ""):
            # The override column packs the token after a '|', so a token containing one would make
            # an imported conversation unparseable on the device that reads it.
            problems.append("%s: token contains %r" % (where, R.OVERRIDE_SEPARATOR))
        if row.get("api_format") not in API_FORMATS:
            problems.append("%s: unknown api_format %r" % (where, row.get("api_format")))
        if not row.get("platforms"):
            problems.append("%s: no platforms" % where)
        local = row["api_format"] in R.LOCAL_FORMATS
        if local and (row["requires_key"] or row["base_url"]):
            problems.append("%s: an on-device provider needs neither key nor base URL" % where)
        if row["requires_custom_endpoint"] and row["base_url"]:
            problems.append("%s: a user-supplied endpoint must not ship a default" % where)
        if row["requires_custom_model_name"] and not row["supports_custom_model_name"]:
            problems.append("%s: requires a custom model name but does not support one" % where)
        if row["models"] and row["models"][0] != row["text_models"][0] and row["text_models"]:
            pass  # a provider may lead its text lineup with a different model; not an error
        if (not local and not row["base_url"] and not row["requires_custom_endpoint"]
                and not row.get("computed_base_url")):
            # Vertex builds its URL from the profile's project and location, so an empty default is
            # correct there and only there.
            problems.append("%s: no base URL and the user is never asked for one" % where)
    for token in TOKEN_FIELDS(doc):
        if token not in seen:
            problems.append("providers.json: token_limit names unknown provider %r" % token)
    return len(doc["providers"])


def TOKEN_FIELDS(doc):
    return list(doc["token_limit"]["always_completion"]) + list(doc["token_limit"]["conditional"])


def check_vertex(problems):
    raw = open(VERTEX, encoding="utf-8").read()
    doc = json.loads(raw)
    if raw != dumps(doc):
        problems.append("vertex.json: not in canonical format")
    matches = [h["match"] for h in doc["hosts"]]
    for needed in ("global", "multi_region", "region"):
        if needed not in matches:
            problems.append("vertex.json: no host rule for %s" % needed)
    if "{project}" not in doc["path_prefix"] or "{location}" not in doc["path_prefix"]:
        problems.append("vertex.json: path_prefix lost a placeholder")
    if not [r for r in doc["routes"] if not r["prefixes"]]:
        problems.append("vertex.json: no catch-all route")
    for route in doc["routes"]:
        if route["transport"] not in ("gemini", "anthropic", "openai_compatible"):
            problems.append("vertex.json/%s: unknown transport" % route["publisher"])
        if route["drops_model_from_body"] and "{model}" not in route["path"]:
            problems.append("vertex.json/%s: drops the model from the body but not into the path"
                            % route["publisher"])
        if not route["drops_model_from_body"] and "{model}" in route["path"]:
            problems.append("vertex.json/%s: the model is in both the path and the body"
                            % route["publisher"])
    return len(doc["routes"])


def check_catalog(problems):
    raw = open(CATALOG, encoding="utf-8").read()
    doc = json.loads(raw)
    if raw != dumps(doc):
        problems.append("catalog.v2.json: not in canonical format")
    if doc.get("schemaVersion") != 2:
        problems.append("catalog.v2.json: schemaVersion must be 2")
    seen = set()
    for model in doc["models"]:
        where = "catalog.v2.json/%s" % model.get("id")
        if model["id"] in seen:
            problems.append("%s: duplicate id" % where)
        seen.add(model["id"])
        if model["format"] != "litertlm":
            problems.append("%s: only LiteRT-LM packages are runnable" % where)
        if not model["capabilities"] or model["capabilities"][0] != "text":
            problems.append("%s: every model must at least do text" % where)
        if model["contextTokens"] <= 0:
            problems.append("%s: contextTokens must be positive" % where)
        art = model["artifact"]
        sha = art["sha256"]
        if len(sha) != 64 or [c for c in sha if c not in HEX]:
            problems.append("%s: sha256 is not 64 hex characters" % where)
        expected_url = "https://huggingface.co/%s/resolve/%s/%s" % (
            art["repository"], art["revision"], art["filename"])
        if art["url"] != expected_url:
            problems.append("%s: url does not match repository + revision + filename" % where)
        if art["sizeBytes"] <= 0:
            problems.append("%s: sizeBytes must be positive" % where)
        if art["access"]["gated"] == art["access"]["anonymous"]:
            problems.append("%s: a gated artifact cannot also be anonymous" % where)
        gate = model["memoryPolicy"]["minimumPhysicalMemoryBytes"]
        if gate != R.memory_gate(art["sizeBytes"]):
            problems.append("%s: memory gate %d does not follow the documented rule (%d)"
                            % (where, gate, R.memory_gate(art["sizeBytes"])))
        if not model["license"].get("textURL"):
            problems.append("%s: no licence text URL" % where)
    return len(doc["models"])


# ---------------------------------------------------------------------------------------------
# Output shapes
# ---------------------------------------------------------------------------------------------

def _check_profiles(profiles, where, problems):
    ids = set()
    for p in profiles:
        if set(p) != PROFILE_FIELDS:
            problems.append("%s: profile keys %s" % (where, sorted(p)))
            continue
        if p["id"] in ids:
            problems.append("%s: duplicate profile id %s" % (where, p["id"]))
        ids.add(p["id"])
        if not p["id"].startswith(R.PROFILE_ID_PREFIX):
            problems.append("%s: profile id %r is not an aip_ id" % (where, p["id"]))
        ref = p["credential_ref"]
        if not (ref == "none" or ref.startswith("provider:") or ref.startswith("profile:")):
            problems.append("%s: credential_ref %r is not tagged" % (where, ref))
        if ref.startswith("profile:") and ref != "profile:" + p["id"]:
            # A profile-scoped key belongs to exactly one profile; anything else would orphan it.
            problems.append("%s: %s points at another profile's key" % (where, p["id"]))
        if p["provider"] not in R.PROVIDERS:
            problems.append("%s: unknown provider token %r" % (where, p["provider"]))


def _check_roles(roles, profiles, where, problems):
    ids = {p["id"] for p in profiles}
    if set(roles) != set(R.ROLES):
        problems.append("%s: roles %s" % (where, sorted(roles)))
        return
    for role, row in roles.items():
        if set(row) != {"profile_id", "enabled"}:
            problems.append("%s/%s: role keys %s" % (where, role, sorted(row)))
        if row.get("profile_id") is not None and row["profile_id"] not in ids:
            problems.append("%s/%s: points at a profile that is not in the list" % (where, role))
    if roles["image"]["profile_id"] is None:
        problems.append("%s: the primary role must always resolve" % where)


def _check_route(route, where, problems, blocked=None):
    required = {"role", "profile_id", "provider", "model", "base_url", "api_format", "credential",
                "vertex", "request_timeout_seconds", "max_response_tokens", "token_limit_key",
                "context_tokens"}
    missing = required - set(route)
    if missing:
        problems.append("%s: route missing %s" % (where, sorted(missing)))
        return
    if route["credential"]["source"] not in R.CREDENTIAL_SOURCES:
        problems.append("%s: credential source %r" % (where, route["credential"]["source"]))
    if route["credential"]["source"] == "missing" and not blocked and route["profile_id"]:
        # A profile that is allowed to answer must have a key. The unblocked synthesized default may
        # not: with nothing configured the app still builds the request and surfaces the provider's
        # own "no API key" error, exactly as it does today.
        problems.append("%s: an unblocked profile may never answer without a credential" % where)
    if route["api_format"] not in API_FORMATS:
        problems.append("%s: api_format %r" % (where, route["api_format"]))
    if route["api_format"] in R.LOCAL_FORMATS and route["base_url"]:
        problems.append("%s: an on-device route must not carry a base URL" % where)
    if route["api_format"] not in R.LOCAL_FORMATS and not route["base_url"] and not blocked:
        problems.append("%s: a network route must carry a base URL" % where)


def check_shape(function, got, where, problems, inp):
    if function == "add_profile":
        _check_profiles(got["profiles"], where, problems)
        if got["action"] not in ("created", "reused", "ignored"):
            problems.append("%s: action %r" % (where, got["action"]))
        ids = [p["id"] for p in got["profiles"]]
        if len(ids) != len(set(ids)):
            problems.append("%s: duplicate profile ids" % where)
        names = [" ".join(p["nickname"].split()).lower() for p in got["profiles"]]
        if len(names) != len(set(names)):
            problems.append("%s: two profiles share a name" % where)
    elif function == "assign_role":
        _check_profiles(got["profiles"], where, problems)
        if got["action"] not in ("created", "edited", "reused", "ignored"):
            problems.append("%s: action %r" % (where, got["action"]))
        # Copy-on-write: a profile another role also points at is never edited in place.
        if got["action"] == "edited":
            others = [r for r in R.ROLES if r != inp["role"]
                      and ((inp.get("roles") or {}).get(r) or {}).get("profile_id") == got["profile_id"]]
            if others:
                problems.append("%s: edited a profile %s also point at" % (where, others))
        ids = [p["id"] for p in got["profiles"]]
        if len(ids) != len(set(ids)):
            problems.append("%s: duplicate profile ids" % where)
    elif function in ("migrate_profiles", "adopt_legacy_primary"):
        _check_profiles(got["profiles"], where, problems)
        _check_roles(got["roles"], got["profiles"], where, problems)
        if function == "migrate_profiles" and got["migration_version"] < 1:
            problems.append("%s: migration_version never moves backwards" % where)
        if function == "adopt_legacy_primary" and got["reason"] not in (
                "match", "adopted", "created", "ignored"):
            problems.append("%s: reason %r" % (where, got["reason"]))
    elif function == "project_legacy":
        if set(got["slots"]) != set(R.ROLES):
            problems.append("%s: projected slots %s" % (where, sorted(got["slots"])))
        if not isinstance(got["fingerprint"], str):
            problems.append("%s: no fingerprint" % where)
    elif function == "resolve_role":
        _check_route(got, where, problems, got.get("blocked"))
        if got["role"] not in R.ROLES:
            problems.append("%s: role %r" % (where, got["role"]))
        if "blocked" not in got or "fell_back" not in got:
            problems.append("%s: a resolved role must say whether it is blocked" % where)
        pointer = ((inp.get("roles") or {}).get(got["role"]) or {}).get("profile_id")
        if got["profile_id"] is not None and got["profile_id"] != pointer:
            # The whole point of the refusal design: the resolver never answers from a profile the
            # user did not point this role at.
            problems.append("%s: resolution substituted %r for the chosen %r"
                            % (where, got["profile_id"], pointer))
        if got["blocked"] is None and got["fell_back"] and (inp.get("profiles") or []):
            problems.append("%s: the synthesized default answered although profiles exist" % where)
    elif function == "resolve_fallback":
        if got["route"] is not None:
            _check_route(got["route"], where, problems)
            if got["reason"] is not None:
                problems.append("%s: a route and a refusal reason at once" % where)
        elif not got["reason"]:
            problems.append("%s: no route and no reason" % where)
    elif function == "resolve_credential":
        if got["source"] not in R.CREDENTIAL_SOURCES:
            problems.append("%s: source %r" % (where, got["source"]))
    elif function == "keys_to_delete":
        if got["provider_keys"]:
            problems.append("%s: deleting a profile may never delete a provider key" % where)
        for key in got["profile_keys"]:
            if not key.startswith("aiprofilekey_"):
                problems.append("%s: %r is not a profile key" % (where, key))
    elif function == "vertex_endpoint":
        if got["ok"]:
            if "{" in got["url"] or "}" in got["url"]:
                problems.append("%s: an unfilled placeholder survived into the URL" % where)
            if not got["url"].startswith("https://"):
                problems.append("%s: Vertex is HTTPS only" % where)
            if got["transport"] == "anthropic" and "anthropic_version" not in got["body_extras"]:
                problems.append("%s: Claude on Vertex needs anthropic_version in the body" % where)
        elif not got.get("reason"):
            problems.append("%s: refused without a reason" % where)
    elif function == "override":
        if inp.get("op") == "parse":
            if set(got) != {"profile_id", "provider"}:
                problems.append("%s: parse keys %s" % (where, sorted(got)))
    elif function == "token_limit_key":
        if got["key"] not in (R.TOKEN_LIMIT["default"], R.TOKEN_LIMIT["completion"]):
            problems.append("%s: key %r" % (where, got["key"]))
    elif function == "local_catalog_state":
        rank = {s: i for i, s in enumerate(R.CATALOG_STATES)}
        last = -1
        for row in got["models"]:
            if row["state"] not in R.CATALOG_STATES:
                problems.append("%s: state %r" % (where, row["state"]))
                continue
            if rank[row["state"]] < last:
                problems.append("%s: rows are out of state order" % where)
            last = max(last, rank[row["state"]])
            if row["reason"] is not None and row["reason"] not in R.INELIGIBILITY:
                problems.append("%s: reason %r" % (where, row["reason"]))
            if row["state"] in ("installed", "available") and row["reason"] is not None:
                problems.append("%s: a usable model carries a blocking reason" % where)


# ---------------------------------------------------------------------------------------------
# Vectors
# ---------------------------------------------------------------------------------------------

def check_vectors(write, problems):
    counts = {}
    present = sorted(os.path.basename(p) for p in glob.glob(os.path.join(VECTORS, "*.json")))
    for name in sorted(EXPECTED_FILES):
        if name not in present:
            problems.append("test-vectors/%s missing" % name)
    for name in present:
        if name not in EXPECTED_FILES:
            problems.append("test-vectors/%s: unknown vector file (add it to EXPECTED_FILES)" % name)
            continue
        path = os.path.join(VECTORS, name)
        raw = open(path, encoding="utf-8").read()
        try:
            doc = json.loads(raw)
        except ValueError as e:
            problems.append("%s: invalid JSON: %s" % (name, e))
            continue
        if (doc.get("format") != FORMAT or doc.get("version") != 1
                or set(doc) != {"format", "version", "function", "cases"}
                or doc.get("function") != EXPECTED_FILES[name]
                or not isinstance(doc.get("cases"), list)):
            problems.append("%s: bad envelope" % name)
            continue
        seen = set()
        changed = False
        for c in doc["cases"]:
            if (set(c) - {"name", "input", "expected", "notes"}
                    or not isinstance(c.get("input"), dict) or not c.get("name")):
                problems.append("%s: bad case keys %s" % (name, sorted(c)))
                continue
            if c["name"] in seen:
                problems.append("%s: duplicate case %s" % (name, c["name"]))
            seen.add(c["name"])
            where = "%s/%s" % (name, c["name"])
            try:
                got = json.loads(json.dumps(R.run_case(doc["function"], c["input"]),
                                            ensure_ascii=False))
            except Exception as e:  # noqa: BLE001 - report, don't crash the whole check
                problems.append("%s: reference raised %s: %s" % (where, type(e).__name__, e))
                continue
            if write:
                if c.get("expected") != got:
                    c["expected"] = got
                    changed = True
            elif c.get("expected") != got:
                problems.append("%s: expected differs from the reference at %s"
                                % (where, _first_diff(c.get("expected"), got)))
            check_shape(doc["function"], got, where, problems, c["input"])
        counts[name] = len(doc["cases"])
        if write and (changed or raw != dumps(doc)):
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(dumps(doc))
        elif not write and raw != dumps(doc):
            problems.append("%s: not in canonical format (run --write)" % name)
    return counts


def check_constants(problems):
    for name, value in DOCUMENTED.items():
        if getattr(R, name, None) != value:
            problems.append("reference %s = %r, documented %r"
                            % (name, getattr(R, name, None), value))


def check_no_regexes(problems):
    for name in dir(R):
        if name.startswith("_RE_") or name.endswith("_PATTERN"):
            problems.append("reference owns a regex (%s); the ports must not need one" % name)


# ---------------------------------------------------------------------------------------------
# Unit tests
# ---------------------------------------------------------------------------------------------

SLOT = {"provider": "OpenAI", "model": "gpt-5.4-mini", "base_url": None, "enabled": True}


def _slots(**over):
    base = {"image": dict(SLOT), "text": {"provider": None, "model": "", "base_url": None,
                                          "enabled": False},
            "image_fallback": {"provider": None, "model": "", "base_url": None, "enabled": False},
            "text_fallback": {"provider": None, "model": "", "base_url": None, "enabled": False}}
    base.update(over)
    return base


class ReferenceTests(unittest.TestCase):
    def migrate(self, slots, **kw):
        payload = {"migration_version": 0, "now_ms": 1_700_000_000_000, "slots": slots,
                   "existing_profiles": []}
        payload.update(kw)
        return R.migrate_profiles(payload)

    def test_an_identical_primary_and_fallback_become_one_profile(self):
        out = self.migrate(_slots(image_fallback=dict(SLOT)))
        self.assertEqual(len(out["profiles"]), 1)
        self.assertEqual(out["roles"]["image"]["profile_id"],
                         out["roles"]["image_fallback"]["profile_id"])

    def test_the_same_provider_at_two_servers_stays_two_profiles(self):
        custom = {"provider": "Custom (OpenAI-compatible)", "model": "m",
                  "base_url": "http://10.0.0.5:8080/v1", "enabled": True}
        other = dict(custom, base_url="http://10.0.0.9:8080/v1")
        out = self.migrate(_slots(image=custom, image_fallback=other))
        self.assertEqual(len(out["profiles"]), 2)
        self.assertNotEqual(out["roles"]["image"]["profile_id"],
                            out["roles"]["image_fallback"]["profile_id"])

    def test_a_disabled_fallback_is_still_given_a_profile(self):
        gemini = {"provider": "Google Gemini", "model": "gemini-3.8-flash", "base_url": None,
                  "enabled": False}
        out = self.migrate(_slots(text_fallback=gemini))
        self.assertEqual(len(out["profiles"]), 2)
        self.assertIsNotNone(out["roles"]["text_fallback"]["profile_id"])
        self.assertFalse(out["roles"]["text_fallback"]["enabled"])

    def test_migration_is_idempotent(self):
        first = self.migrate(_slots(text=dict(SLOT, model="gpt-4.1")))
        second = R.migrate_profiles({"migration_version": first["migration_version"],
                                     "now_ms": 1, "slots": _slots(),
                                     "existing_profiles": first["profiles"],
                                     "roles": first["roles"]})
        self.assertEqual(first, second)

    def test_an_unknown_provider_drops_its_slot_without_losing_the_primary(self):
        out = self.migrate(_slots(text={"provider": "Nonesuch", "model": "x", "base_url": None,
                                        "enabled": True}))
        self.assertIsNone(out["roles"]["text"]["profile_id"])
        self.assertIsNotNone(out["roles"]["image"]["profile_id"])

    def test_an_empty_legacy_state_still_yields_a_usable_primary(self):
        out = self.migrate(_slots(image={"provider": None, "model": "", "base_url": None,
                                         "enabled": True}))
        primary = [p for p in out["profiles"]
                   if p["id"] == out["roles"]["image"]["profile_id"]][0]
        self.assertEqual(primary["provider"], R.DEFAULT_PROVIDER)

    def test_every_migrated_profile_keeps_the_provider_key(self):
        out = self.migrate(_slots(text=dict(SLOT, model="gpt-4.1")))
        for p in out["profiles"]:
            self.assertEqual(p["credential_ref"], "provider:" + p["provider"])

    def test_nicknames_collide_only_with_a_counter(self):
        a = {"provider": "Ollama (Local)", "model": "qwen3.8", "base_url": "http://a:11434/v1",
             "enabled": True}
        b = dict(a, base_url="http://b:11434/v1")
        out = self.migrate(_slots(image=a, image_fallback=b))
        names = [p["nickname"] for p in out["profiles"]]
        self.assertEqual(len(set(names)), len(names))
        self.assertTrue(names[1].endswith("(2)"))

    def test_onboarding_after_profiles_exist_is_adopted_not_ignored(self):
        migrated = self.migrate(_slots())
        projected = R.project_legacy(migrated["profiles"], migrated["roles"])
        out = R.adopt_legacy_primary({
            "observed": {"provider": "Anthropic Claude", "model": "claude-sonnet-5",
                         "base_url": None},
            "fingerprint": projected["fingerprint"], "profiles": migrated["profiles"],
            "roles": migrated["roles"], "now_ms": 2})
        self.assertEqual(out["reason"], "created")
        self.assertTrue(out["changed"])
        adopted = [p for p in out["profiles"] if p["id"] == out["roles"]["image"]["profile_id"]][0]
        self.assertEqual(adopted["provider"], "Anthropic Claude")

    def test_an_unchanged_projection_is_left_alone(self):
        migrated = self.migrate(_slots())
        projected = R.project_legacy(migrated["profiles"], migrated["roles"])
        out = R.adopt_legacy_primary({
            "observed": {"provider": "OpenAI", "model": "gpt-5.4-mini", "base_url": None},
            "fingerprint": projected["fingerprint"], "profiles": migrated["profiles"],
            "roles": migrated["roles"], "now_ms": 2})
        self.assertEqual(out["reason"], "match")
        self.assertFalse(out["changed"])

    def test_adoption_never_duplicates_an_existing_configuration(self):
        migrated = self.migrate(_slots())
        out = R.adopt_legacy_primary({
            "observed": {"provider": "OpenAI", "model": "gpt-5.4-mini", "base_url": None},
            "fingerprint": "stale", "profiles": migrated["profiles"], "roles": migrated["roles"],
            "now_ms": 2})
        self.assertEqual(out["reason"], "adopted")
        self.assertEqual(len(out["profiles"]), len(migrated["profiles"]))

    def test_a_vanished_profile_key_falls_back_to_the_provider_key(self):
        self.assertEqual(R.resolve_credential("profile:aip_0001", True, False, True), "provider")
        self.assertEqual(R.resolve_credential("profile:aip_0001", True, False, False), "missing")
        self.assertEqual(R.resolve_credential("profile:aip_0001", False, False, False), "none")

    def test_deleting_a_profile_never_touches_a_provider_key(self):
        out = R.keys_to_delete({"profile": {"id": "aip_0009", "provider": "OpenAI",
                                            "credential_ref": "profile:aip_0009"},
                                "remaining": []})
        self.assertEqual(out["provider_keys"], [])
        self.assertEqual(out["profile_keys"], ["aiprofilekey_aip_0009"])

    def test_a_text_only_local_model_is_blocked_for_an_image_request(self):
        profiles = [{"id": "aip_0001", "nickname": "Qwen3 4B",
                     "provider": "Gemma 4 E2B (On-Device)", "model_id": "qwen3-4b-litertlm",
                     "base_url": None, "vertex": None, "credential_ref": "none",
                     "created_ms": 0, "updated_ms": 0},
                    {"id": "aip_0002", "nickname": "Gemini", "provider": "Google Gemini",
                     "model_id": "gemini-3.8-flash", "base_url": None, "vertex": None,
                     "credential_ref": "provider:Google Gemini", "created_ms": 0, "updated_ms": 0}]
        roles = {r: {"profile_id": "aip_0001" if r == "image" else None,
                     "enabled": r == "image"} for r in R.ROLES}
        env = {"requires_vision": True, "max_response_tokens": 1024,
               "request_timeout_seconds": 180, "providers_with_keys": ["Google Gemini"],
               "local_models": {"qwen3-4b-litertlm": {"installed": True, "vision": False,
                                                      "context_tokens": 4096}}}
        route = R.resolve_role({"profiles": profiles, "roles": roles, "env": env})
        self.assertEqual(route["blocked"], "no_vision")
        self.assertEqual(route["profile_id"], "aip_0001")

    def test_an_uninstalled_local_model_is_blocked_not_swapped_for_the_cloud(self):
        profiles = [{"id": "aip_0001", "nickname": "Gemma", "provider": "Gemma 4 E2B (On-Device)",
                     "model_id": "gemma-4-e2b-it-litertlm", "base_url": None, "vertex": None,
                     "credential_ref": "none", "created_ms": 0, "updated_ms": 0},
                    {"id": "aip_0002", "nickname": "Gemini", "provider": "Google Gemini",
                     "model_id": "gemini-3.8-flash", "base_url": None, "vertex": None,
                     "credential_ref": "provider:Google Gemini", "created_ms": 0, "updated_ms": 0}]
        roles = {r: {"profile_id": "aip_0001" if r == "image" else None,
                     "enabled": r == "image"} for r in R.ROLES}
        env = {"requires_vision": False, "max_response_tokens": 1024,
               "providers_with_keys": ["Google Gemini"], "local_models": {}}
        route = R.resolve_role({"profiles": profiles, "roles": roles, "env": env})
        self.assertEqual(route["blocked"], "model_not_installed")
        # The substitution this refuses would put health data on someone else's server.
        self.assertEqual(route["provider"], "Gemma 4 E2B (On-Device)")

    def test_a_keyless_primary_is_blocked_rather_than_answered_elsewhere(self):
        profiles = [{"id": "aip_0001", "nickname": "Groq", "provider": "Groq",
                     "model_id": "qwen/qwen3.6-27b", "base_url": None, "vertex": None,
                     "credential_ref": "provider:Groq", "created_ms": 0, "updated_ms": 0},
                    {"id": "aip_0002", "nickname": "OpenAI", "provider": "OpenAI",
                     "model_id": "gpt-5.4-mini", "base_url": None, "vertex": None,
                     "credential_ref": "provider:OpenAI", "created_ms": 0, "updated_ms": 0}]
        roles = {r: {"profile_id": "aip_0001" if r == "image" else None,
                     "enabled": r == "image"} for r in R.ROLES}
        env = {"requires_vision": False, "max_response_tokens": 1024,
               "providers_with_keys": ["OpenAI"], "local_models": {}}
        route = R.resolve_role({"profiles": profiles, "roles": roles, "env": env})
        self.assertEqual(route["blocked"], "no_key")
        self.assertEqual(route["profile_id"], "aip_0001")

    def test_only_an_unconfigured_device_gets_the_default(self):
        roles = {r: {"profile_id": None, "enabled": r == "image"} for r in R.ROLES}
        env = {"requires_vision": False, "max_response_tokens": 1024, "local_models": {}}
        empty = R.resolve_role({"profiles": [], "roles": roles, "env": env})
        self.assertIsNone(empty["blocked"])
        self.assertEqual(empty["provider"], R.DEFAULT_PROVIDER)
        profiles = [{"id": "aip_0001", "nickname": "OpenAI", "provider": "OpenAI",
                     "model_id": "gpt-5.4-mini", "base_url": None, "vertex": None,
                     "credential_ref": "provider:OpenAI", "created_ms": 0, "updated_ms": 0}]
        dangling = {r: {"profile_id": "aip_9999" if r == "image" else None,
                        "enabled": r == "image"} for r in R.ROLES}
        out = R.resolve_role({"profiles": profiles, "roles": dangling, "env": env})
        self.assertEqual(out["blocked"], "no_profile")

    def test_two_profiles_on_one_endpoint_are_not_a_fallback(self):
        a = {"id": "aip_0001", "nickname": "A", "provider": "OpenAI", "model_id": "gpt-5.4-mini",
             "base_url": None, "vertex": None, "credential_ref": "provider:OpenAI",
             "created_ms": 0, "updated_ms": 0}
        b = dict(a, id="aip_0002", nickname="B")
        roles = {"image": {"profile_id": "aip_0001", "enabled": True},
                 "text": {"profile_id": None, "enabled": False},
                 "image_fallback": {"profile_id": "aip_0002", "enabled": True},
                 "text_fallback": {"profile_id": None, "enabled": False}}
        env = {"requires_vision": True, "providers_with_keys": ["OpenAI"],
               "max_response_tokens": 1024}
        primary = R.resolve_role({"profiles": [a, b], "roles": roles, "env": env})
        out = R.resolve_fallback({"profiles": [a, b], "roles": roles, "env": env,
                                  "primary": primary})
        self.assertIsNone(out["route"])
        self.assertEqual(out["reason"], "same_endpoint")

    def test_one_provider_two_models_is_a_real_fallback(self):
        a = {"id": "aip_0001", "nickname": "A", "provider": "Google Gemini",
             "model_id": "gemini-3.1-pro-preview", "base_url": None, "vertex": None,
             "credential_ref": "provider:Google Gemini", "created_ms": 0, "updated_ms": 0}
        b = dict(a, id="aip_0002", nickname="B", model_id="gemini-3.5-flash-lite")
        roles = {"image": {"profile_id": "aip_0001", "enabled": True},
                 "text": {"profile_id": None, "enabled": False},
                 "image_fallback": {"profile_id": "aip_0002", "enabled": True},
                 "text_fallback": {"profile_id": None, "enabled": False}}
        env = {"requires_vision": True, "providers_with_keys": ["Google Gemini"],
               "max_response_tokens": 1024}
        primary = R.resolve_role({"profiles": [a, b], "roles": roles, "env": env})
        out = R.resolve_fallback({"profiles": [a, b], "roles": roles, "env": env,
                                  "primary": primary})
        self.assertIsNotNone(out["route"])
        self.assertEqual(out["route"]["model"], "gemini-3.5-flash-lite")

    def test_an_override_round_trips_and_degrades_to_the_provider(self):
        raw = R.encode_override("aip_0004", "OpenAI")
        self.assertEqual(raw, "profile:aip_0004|OpenAI")
        self.assertEqual(R.parse_override(raw), {"profile_id": "aip_0004", "provider": "OpenAI"})
        self.assertEqual(R.parse_override("Gemma 4 E2B (On-Device)"),
                         {"profile_id": None, "provider": "Gemma 4 E2B (On-Device)"})
        self.assertEqual(R.parse_override(None), {"profile_id": None, "provider": None})
        self.assertIsNone(R.encode_override(None, ""))

    def test_no_provider_token_can_break_the_override_encoding(self):
        for token in R.PROVIDERS:
            self.assertNotIn(R.OVERRIDE_SEPARATOR, token)
            self.assertEqual(R.parse_override(R.encode_override("aip_0001", token))["provider"],
                             token)

    def test_vertex_routes_by_publisher_and_location(self):
        gem = R.vertex_endpoint("proj", "europe-west1", "gemini-3.8-flash")
        self.assertEqual(gem["transport"], "gemini")
        self.assertTrue(gem["url"].startswith("https://europe-west1-aiplatform.googleapis.com/"))
        claude = R.vertex_endpoint("proj", "us", "claude-sonnet-5")
        self.assertEqual(claude["transport"], "anthropic")
        self.assertTrue(claude["url"].startswith("https://aiplatform.us.rep.googleapis.com/"))
        self.assertEqual(claude["body_extras"]["anthropic_version"], "vertex-2023-10-16")
        other = R.vertex_endpoint("proj", "global", "meta/llama-4-maverick")
        self.assertEqual(other["transport"], "openai_compatible")
        self.assertTrue(other["url"].endswith("/endpoints/openapi/chat/completions"))
        self.assertFalse(R.vertex_endpoint("", "global", "gemini-3.8-flash")["ok"])

    def test_the_memory_gate_does_not_move_for_the_model_already_installed(self):
        gemma = [m for m in R.CATALOG_DOC["models"] if m["id"] == "gemma-4-e2b-it-litertlm"][0]
        self.assertEqual(gemma["memoryPolicy"]["minimumPhysicalMemoryBytes"], 8 * R.GIB)
        self.assertEqual(R.memory_gate(gemma["artifact"]["sizeBytes"]), 8 * R.GIB)

    def test_a_gated_model_is_not_offered_without_a_token(self):
        device = {"physical_memory_bytes": 16 * R.GIB, "abis": ["arm64-v8a"], "free_bytes": 60 * R.GIB}
        blocked = R.local_catalog_state({"device": device, "installed": [], "has_hf_token": False})
        states = {row["id"]: row["state"] for row in blocked["models"]}
        self.assertEqual(states["medgemma-1.5-4b-it-litertlm"], "needs_token")
        allowed = R.local_catalog_state({"device": device, "installed": [], "has_hf_token": True})
        states = {row["id"]: row["state"] for row in allowed["models"]}
        self.assertEqual(states["medgemma-1.5-4b-it-litertlm"], "available")

    def test_a_model_too_large_for_the_device_says_so_instead_of_failing_later(self):
        device = {"physical_memory_bytes": 8 * R.GIB, "abis": ["arm64-v8a"],
                  "free_bytes": 200 * R.GIB}
        out = R.local_catalog_state({"device": device, "installed": [], "has_hf_token": True})
        rows = {row["id"]: row for row in out["models"]}
        self.assertEqual(rows["qwen3-14b-litertlm"]["state"], "ineligible")
        self.assertEqual(rows["qwen3-14b-litertlm"]["reason"], "insufficient_memory")
        self.assertEqual(rows["qwen3-4b-litertlm"]["state"], "available")

    def test_installed_models_are_listed_first(self):
        device = {"physical_memory_bytes": 12 * R.GIB, "abis": ["arm64-v8a"],
                  "free_bytes": 200 * R.GIB}
        out = R.local_catalog_state({"device": device, "installed": ["qwen3-4b-litertlm"],
                                     "has_hf_token": True})
        self.assertEqual(out["models"][0]["id"], "qwen3-4b-litertlm")

    def test_the_projection_drops_what_the_flat_keys_cannot_hold(self):
        profiles = [{"id": "aip_0001", "nickname": "Work", "provider": "Google Vertex AI",
                     "model_id": "gemini-3.8-flash", "base_url": None,
                     "vertex": {"project_id": "p", "location": "global"},
                     "credential_ref": "profile:aip_0001", "created_ms": 0, "updated_ms": 0}]
        roles = {r: {"profile_id": "aip_0001" if r == "image" else None,
                     "enabled": r == "image"} for r in R.ROLES}
        out = R.project_legacy(profiles, roles)
        self.assertEqual(out["slots"]["image"]["provider"], "Google Vertex AI")
        self.assertNotIn("vertex", out["slots"]["image"])
        self.assertNotIn("nickname", out["slots"]["image"])


def main(argv):
    write = "--write" in argv
    problems = []
    n_providers = check_providers(problems)
    n_routes = check_vertex(problems)
    n_models = check_catalog(problems)
    check_constants(problems)
    check_no_regexes(problems)
    counts = check_vectors(write, problems)
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(ReferenceTests)
    result = unittest.TextTestRunner(stream=open(os.devnull, "w"), verbosity=0).run(suite)
    for failed, trace in result.failures + result.errors:
        problems.append("unittest %s failed:\n%s" % (failed.id(), trace.strip().splitlines()[-1]))
    print("%-45s %3d providers" % ("shared/ai/providers.json", n_providers))
    print("%-45s %3d routes" % ("shared/ai/vertex.json", n_routes))
    print("%-45s %3d models" % ("local-models/catalog.v2.json", n_models))
    for name, n in sorted(counts.items()):
        print("%-45s %3d cases" % ("test-vectors/" + name, n))
    print("%d unit tests" % result.testsRun)
    if problems:
        print("\n%d problem(s):" % len(problems))
        for p in problems:
            print(" - " + p)
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
