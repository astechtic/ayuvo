#!/usr/bin/env python3
"""Ayuvo AI: executable reference for provider selection, model profiles and the local catalog.

Contract: docs/ai-models.md. Where this file and the prose disagree, this file wins and the prose is
fixed. Android (Kotlin, `services/ai/AIReference.kt`) and iOS (Swift, `Models/AIReference.swift`)
port it line by line and run shared/ai/test-vectors/*.json in their unit tests.

Portability rules (same as scripts/coach_reference.py and scripts/metrics_reference.py):
  * Python 3 stdlib only. Every function is pure: no clock, locale or randomness. "Now" is always an
    explicit input. The only I/O is reading the catalogs once at import (shared/ai/providers.json,
    shared/ai/vertex.json, local-models/catalog.v2.json); ports load the same data from their
    bundled copies or from their own enums.
  * No regexes at all. Everything here is prefix/suffix work on ASCII, which every port can do with
    plain string operations.
  * Numbers compare by value in vectors (13 == 13.0); object key order never matters; array order does.
  * Text is measured in Unicode code points.

Vocabulary
  provider token  The persisted provider identifier: the iOS rawValue AND the Android @SerialName,
                  which are equal by contract ("Google Gemini"). Android's enum NAME ("GEMINI") is a
                  platform detail that never appears in shared data.
  profile         A saved {provider, model, base_url, vertex, credential_ref} the user named.
  role            One of image, text, image_fallback, text_fallback -- a pointer to a profile.
  legacy slot     The flat preference keys the four roles used before profiles existed. Onboarding
                  still writes them, so they remain an input, never an output.
"""

import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SHARED = os.path.join(ROOT, "shared", "ai")
PROVIDERS_PATH = os.path.join(SHARED, "providers.json")
VERTEX_PATH = os.path.join(SHARED, "vertex.json")
CATALOG_PATH = os.path.join(ROOT, "local-models", "catalog.v2.json")


def _load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


PROVIDERS_DOC = _load(PROVIDERS_PATH)
VERTEX_DOC = _load(VERTEX_PATH)
CATALOG_DOC = _load(CATALOG_PATH)

# ---------------------------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------------------------

ROLES = ("image", "text", "image_fallback", "text_fallback")
#: The role each fallback backs up.
FALLBACK_OF = {"image_fallback": "image", "text_fallback": "text"}
#: Profile ids are `aip_` + four digits: ASCII, sortable, and identical on both platforms, so the
#: per-profile credential account name is portable in a way the provider token is not.
PROFILE_ID_PREFIX = "aip_"
PROFILE_ID_DIGITS = 4
#: Characters a profile id may contain, checked when parsing an override written by another device.
PROFILE_ID_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_"
MAX_PROFILE_ID_LENGTH = 64
#: Separates the profile id from the provider token inside conversations.provider_override.
OVERRIDE_PREFIX = "profile:"
OVERRIDE_SEPARATOR = "|"
#: Joins the three parts of the primary-projection fingerprint. NUL can appear in no token.
FINGERPRINT_SEPARATOR = "\x00"
MIGRATION_VERSION = 1
#: Chosen when nothing else is usable, matching today's `executableAIProviderOrDefault(default:)`.
DEFAULT_PROVIDER = "Google Gemini"
CREDENTIAL_SOURCES = ("none", "profile", "provider", "missing")
#: The states Settings renders for a downloadable model.
CATALOG_STATES = ("installed", "available", "needs_token", "ineligible")
INELIGIBILITY = ("low_ram_device", "unsupported_abi", "insufficient_memory", "insufficient_space",
                 "needs_token")
GIB = 1024 ** 3

PROVIDERS = {row["id"]: row for row in PROVIDERS_DOC["providers"]}
PROVIDER_ORDER = [row["id"] for row in PROVIDERS_DOC["providers"]]
TOKEN_LIMIT = PROVIDERS_DOC["token_limit"]
#: Providers whose "endpoint" is the device itself: no base URL, no key, no network.
LOCAL_FORMATS = ("on_device", "litert_local")


# ---------------------------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------------------------

def _text(value):
    return value if isinstance(value, str) else ""


def _trim(value):
    return _text(value).strip()


def normalize_model_id(model):
    """Whitespace only.

    Deliberately NOT the platforms' `normalizedModelID`: their legacy-id upgrades (Gemini renames,
    retired presets) run BEFORE this reference is ever called, so folding them in here would let a
    migration depend on which upgrade version the device happened to be on.
    """
    return _trim(model)


def provider_defaults(env=None):
    """The provider table the resolver reasons over.

    Ports may pass their own map, built from their enum. Otherwise shared/ai/providers.json is used,
    narrowed to `env.platform` when one is given: Apple Intelligence is iOS-only, and a build that
    does not have a provider must not be able to migrate a profile onto it or resolve a route to it.
    A vector that omits `platform` sees every row, which is what a platform-agnostic case wants.
    """
    env = env or {}
    supplied = env.get("provider_defaults")
    if isinstance(supplied, dict) and supplied:
        return supplied
    platform = env.get("platform")
    out = {}
    for row in PROVIDERS_DOC["providers"]:
        if platform and platform not in row.get("platforms", []):
            continue
        out[row["id"]] = {
            "api_format": row["api_format"],
            "base_url": row["base_url"],
            "requires_key": row["requires_key"],
            "supports_vision": row["supports_vision"],
            "configurable_timeout": row["configurable_timeout"],
            "models": list(row["models"]),
        }
    return out


def _known(provider, table):
    return isinstance(provider, str) and provider in table


def _is_local(spec):
    return spec.get("api_format") in LOCAL_FORMATS


def default_model_for(provider, table):
    models = (table.get(provider) or {}).get("models") or []
    return models[0] if models else ""


def token_limit_key(provider, model):
    """Which key carries the output cap in an OpenAI-compatible body."""
    provider = _text(provider)
    if provider in TOKEN_LIMIT["always_completion"]:
        return TOKEN_LIMIT["completion"]
    if provider in TOKEN_LIMIT["conditional"]:
        tail = _trim(model).lower().split("/")[-1]
        for prefix in TOKEN_LIMIT["completion_prefixes"]:
            if tail.startswith(prefix):
                return TOKEN_LIMIT["completion"]
    return TOKEN_LIMIT["default"]


# ---------------------------------------------------------------------------------------------
# Profiles
# ---------------------------------------------------------------------------------------------

def profile_key(provider, model, base_url):
    """The identity of a configuration. Two slots with this key are one profile."""
    return (_trim(provider), normalize_model_id(model), _trim(base_url))


def _next_profile_number(profiles):
    highest = 0
    for p in profiles:
        pid = _text(p.get("id"))
        if not pid.startswith(PROFILE_ID_PREFIX):
            continue
        tail = pid[len(PROFILE_ID_PREFIX):]
        if tail.isdigit():
            highest = max(highest, int(tail))
    return highest + 1


def _format_profile_id(number):
    return PROFILE_ID_PREFIX + str(number).rjust(PROFILE_ID_DIGITS, "0")


def _nickname(provider, model, taken):
    base = _auto_nickname(provider, model)
    folded = " ".join(base.split()).lower()
    if folded not in taken:
        taken.add(folded)
        return base
    n = 2
    while True:
        candidate = "%s (%d)" % (base, n)
        folded = " ".join(candidate.split()).lower()
        if folded not in taken:
            taken.add(folded)
            return candidate
        n += 1


def _new_profile(provider, model, base_url, now_ms, profiles, taken):
    profile = {
        "id": _format_profile_id(_next_profile_number(profiles)),
        "nickname": _nickname(provider, model, taken),
        "provider": provider,
        "model_id": model,
        "base_url": base_url or None,
        "vertex": None,
        # Every migrated or adopted profile inherits the provider's key. That single line is the
        # whole backward-compatibility story: nobody has to re-enter a key.
        "credential_ref": "provider:" + provider,
        "created_ms": now_ms,
        "updated_ms": now_ms,
    }
    profiles.append(profile)
    return profile


def _find_or_create(provider, model, base_url, now_ms, profiles, index, taken):
    key = profile_key(provider, model, base_url)
    if key in index:
        return index[key], False
    profile = _new_profile(key[0], key[1], key[2], now_ms, profiles, taken)
    index[key] = profile
    return profile, True


def _index_profiles(profiles):
    index = {}
    taken = set()
    for p in profiles:
        index.setdefault(profile_key(p.get("provider"), p.get("model_id"), p.get("base_url")), p)
        taken.add(" ".join(_text(p.get("nickname")).split()).lower())
    return index, taken


def migrate_profiles(payload):
    """One-shot conversion of the four legacy slots into profiles + role pointers.

    Deterministic and idempotent: `migrate(migrate(x)) == migrate(x)`, which is what lets it run on
    every launch behind a version guard instead of being a script somebody has to remember.
    """
    version = payload.get("migration_version") or 0
    profiles = [dict(p) for p in (payload.get("existing_profiles") or [])]
    roles_in = payload.get("roles") or {}
    if version >= MIGRATION_VERSION:
        roles = {}
        for role in ROLES:
            row = roles_in.get(role) or {}
            roles[role] = {"profile_id": row.get("profile_id"),
                           "enabled": bool(row.get("enabled", role == "image"))}
        return {"migration_version": version, "profiles": profiles, "roles": roles}

    now_ms = payload.get("now_ms") or 0
    slots = payload.get("slots") or {}
    table = provider_defaults(payload.get("env"))
    index, taken = _index_profiles(profiles)
    roles = {}
    for role in ROLES:
        slot = slots.get(role) or {}
        provider = _trim(slot.get("provider"))
        model = normalize_model_id(slot.get("model"))
        enabled = bool(slot.get("enabled", role == "image"))
        if not _known(provider, table) or (not model and not _is_local(table.get(provider, {}))):
            # A slot naming a provider this build does not have, or carrying no model, describes
            # nothing runnable. Dropping it is safer than inventing a model it never had.
            roles[role] = {"profile_id": None, "enabled": False}
            continue
        profile, _ = _find_or_create(provider, model, _trim(slot.get("base_url")), now_ms,
                                     profiles, index, taken)
        roles[role] = {"profile_id": profile["id"], "enabled": enabled}

    if roles["image"]["profile_id"] is None:
        # Resolution must never come up empty, so the primary always ends with something real.
        fallback_model = default_model_for(DEFAULT_PROVIDER, table)
        profile, _ = _find_or_create(DEFAULT_PROVIDER, fallback_model, "", now_ms, profiles,
                                     index, taken)
        roles["image"] = {"profile_id": profile["id"], "enabled": True}
    return {"migration_version": MIGRATION_VERSION, "profiles": profiles, "roles": roles}


def _auto_nickname(provider, model):
    return "%s · %s" % (provider, model) if model else provider


def add_profile(payload):
    """Add a saved configuration without wiring it to any role.

    Settings' "Add model" writes through this, so a standalone profile gets its id and its unique
    nickname from the same place a migrated one does. An identical configuration is returned rather
    than duplicated -- two rows naming one endpoint would be a list nobody can reason about.
    """
    profiles = [dict(p) for p in (payload.get("profiles") or [])]
    table = provider_defaults(payload.get("env"))
    provider = _trim(payload.get("provider"))
    model = normalize_model_id(payload.get("model"))
    base_url = _trim(payload.get("base_url"))
    if not _known(provider, table):
        return {"profiles": profiles, "profile_id": None, "action": "ignored"}
    index, taken = _index_profiles(profiles)
    key = profile_key(provider, model, base_url)
    existing = index.get(key)
    if existing is not None:
        return {"profiles": profiles, "profile_id": existing["id"], "action": "reused"}
    profile = _new_profile(provider, model, base_url, payload.get("now_ms") or 0, profiles, taken)
    nickname = _trim(payload.get("nickname"))
    if nickname:
        profile["nickname"] = nickname
    return {"profiles": profiles, "profile_id": profile["id"], "action": "created"}


def assign_role(payload):
    """Point a role at the configuration {provider, model, base_url}.

    Settings' per-role editors write through this. It is copy-on-write: editing the primary must not
    silently change a fallback that happens to share its profile, which is exactly what the four
    separate flat slots used to guarantee. So a profile another role also points at is never edited
    in place -- a new one is created instead.

    Editing in place keeps the profile id, so a conversation pinned to it follows the change rather
    than losing its pin. Changing the provider resets `credential_ref` to that provider's key and
    reports `cleared_profile_key`, because a key issued for one provider is meaningless at another.
    """
    profiles = [dict(p) for p in (payload.get("profiles") or [])]
    roles_in = payload.get("roles") or {}
    roles = {}
    for name in ROLES:
        row = roles_in.get(name) or {}
        roles[name] = {"profile_id": row.get("profile_id"),
                       "enabled": bool(row.get("enabled", name == "image"))}
    role = payload.get("role")
    if role not in ROLES:
        return {"profiles": profiles, "roles": roles, "profile_id": None, "action": "ignored",
                "cleared_profile_key": None}

    table = provider_defaults(payload.get("env"))
    provider = _trim(payload.get("provider"))
    model = normalize_model_id(payload.get("model"))
    base_url = _trim(payload.get("base_url"))
    if not _known(provider, table):
        return {"profiles": profiles, "roles": roles, "profile_id": roles[role]["profile_id"],
                "action": "ignored", "cleared_profile_key": None}

    now_ms = payload.get("now_ms") or 0
    index, taken = _index_profiles(profiles)
    by_id = {p.get("id"): p for p in profiles}
    key = profile_key(provider, model, base_url)

    existing = index.get(key)
    if existing is not None:
        roles[role]["profile_id"] = existing["id"]
        return {"profiles": profiles, "roles": roles, "profile_id": existing["id"],
                "action": "reused", "cleared_profile_key": None}

    current = by_id.get(roles[role]["profile_id"])
    shared = current is not None and any(
        roles[other]["profile_id"] == current["id"] for other in ROLES if other != role)
    if current is not None and not shared:
        was_auto = current.get("nickname") == _auto_nickname(current.get("provider"),
                                                             current.get("model_id"))
        provider_changed = current.get("provider") != provider
        cleared = provider_changed and _text(current.get("credential_ref")).startswith("profile:")
        current["provider"] = provider
        current["model_id"] = model
        current["base_url"] = base_url or None
        current["updated_ms"] = now_ms
        if provider_changed:
            current["credential_ref"] = "provider:" + provider
            current["vertex"] = None
        if was_auto:
            # The name was ours, so it follows the configuration. A name the user typed does not.
            taken.discard(" ".join(_text(current.get("nickname")).split()).lower())
            current["nickname"] = _nickname(provider, model, taken)
        return {"profiles": profiles, "roles": roles, "profile_id": current["id"],
                "action": "edited", "cleared_profile_key": current["id"] if cleared else None}

    profile = _new_profile(provider, model, base_url, now_ms, profiles, taken)
    roles[role]["profile_id"] = profile["id"]
    return {"profiles": profiles, "roles": roles, "profile_id": profile["id"],
            "action": "created", "cleared_profile_key": None}


def fingerprint(provider, model, base_url):
    """What the mirror last wrote for the primary role."""
    key = profile_key(provider, model, base_url)
    return FINGERPRINT_SEPARATOR.join(key)


def project_legacy(profiles, roles):
    """Profiles -> the flat keys, the one direction writes flow in.

    Lossy on purpose: nickname, id, vertex{} and credential_ref have no legacy home, and nothing
    that reads the flat keys wants them.
    """
    by_id = {p.get("id"): p for p in profiles}
    out = {"slots": {}, "fingerprint": None}
    for role in ROLES:
        row = (roles or {}).get(role) or {}
        profile = by_id.get(row.get("profile_id"))
        if profile is None:
            out["slots"][role] = {"provider": None, "model": "", "base_url": None,
                                  "enabled": False}
            continue
        out["slots"][role] = {
            "provider": profile.get("provider"),
            "model": profile.get("model_id"),
            "base_url": profile.get("base_url") or None,
            "enabled": bool(row.get("enabled", role == "image")),
        }
    primary = out["slots"]["image"]
    out["fingerprint"] = fingerprint(primary["provider"] or "", primary["model"],
                                     primary["base_url"] or "")
    return out


def adopt_legacy_primary(payload):
    """The one inbound channel: a flat primary that the mirror did not write.

    Onboarding is unchanged and still writes only the flat keys. If what it wrote differs from the
    last projection, the user picked something in onboarding and that choice is adopted -- never
    ignored, and never duplicated, because find-or-create uses the migration's dedupe key.
    """
    observed = payload.get("observed") or {}
    provider = _trim(observed.get("provider"))
    model = normalize_model_id(observed.get("model"))
    base_url = _trim(observed.get("base_url"))
    profiles = [dict(p) for p in (payload.get("profiles") or [])]
    roles_in = payload.get("roles") or {}
    roles = {}
    for role in ROLES:
        row = roles_in.get(role) or {}
        roles[role] = {"profile_id": row.get("profile_id"),
                       "enabled": bool(row.get("enabled", role == "image"))}
    table = provider_defaults(payload.get("env"))
    stored = payload.get("fingerprint")
    observed_print = fingerprint(provider, model, base_url)

    if not _known(provider, table):
        return {"reason": "ignored", "changed": False, "profiles": profiles, "roles": roles,
                "fingerprint": stored}
    if isinstance(stored, str) and stored == observed_print:
        return {"reason": "match", "changed": False, "profiles": profiles, "roles": roles,
                "fingerprint": stored}

    index, taken = _index_profiles(profiles)
    profile, created = _find_or_create(provider, model, base_url, payload.get("now_ms") or 0,
                                       profiles, index, taken)
    changed = created or roles["image"].get("profile_id") != profile["id"]
    roles["image"] = {"profile_id": profile["id"], "enabled": True}
    return {"reason": "created" if created else "adopted", "changed": changed, "profiles": profiles,
            "roles": roles, "fingerprint": observed_print}


# ---------------------------------------------------------------------------------------------
# Credentials
# ---------------------------------------------------------------------------------------------

def resolve_credential(credential_ref, requires_key, has_profile_key, has_provider_key):
    """Which store holds the key for this profile.

    The profile -> provider fall-through is load-bearing: KeyStore.openOrRecover deliberately wipes
    every key after an AEADBadTagException, and without it a profile whose own key vanished would be
    permanently dead rather than merely back on the provider key.
    """
    ref = _text(credential_ref)
    if not requires_key or ref == "none" or not ref:
        return "none"
    if ref.startswith("profile:"):
        if has_profile_key:
            return "profile"
        return "provider" if has_provider_key else "missing"
    if ref.startswith("provider:"):
        return "provider" if has_provider_key else "missing"
    return "missing"


def keys_to_delete(payload):
    """What deleting a profile may remove from the two credential stores."""
    profile = payload.get("profile") or {}
    pid = _text(profile.get("id"))
    return {
        "profile_keys": ["aiprofilekey_" + pid] if pid else [],
        # Never, under any condition. The provider key has owners outside the profile layer:
        # onboarding, the legacy per-provider screen, and KeyStore.speechApiKey, which falls back to
        # the matching AI provider's key -- deleting it would silently break speech-to-text.
        "provider_keys": [],
    }


# ---------------------------------------------------------------------------------------------
# Role resolution
# ---------------------------------------------------------------------------------------------

def _local_model(env, model_id):
    return ((env or {}).get("local_models") or {}).get(model_id)


def _credential_for(profile, spec, env):
    ref = _text(profile.get("credential_ref"))
    pid = _text(profile.get("id"))
    provider = _text(profile.get("provider"))
    source = resolve_credential(
        ref,
        bool(spec.get("requires_key")),
        pid in ((env or {}).get("profiles_with_keys") or []),
        provider in ((env or {}).get("providers_with_keys") or []),
    )
    if source == "profile":
        return {"source": source, "ref": "aiprofilekey_" + pid}
    if source == "provider":
        return {"source": source, "ref": "apikey_" + provider}
    return {"source": source, "ref": None}


def _effective_base_url(profile, spec):
    own = _trim(profile.get("base_url"))
    return own if own else _text(spec.get("base_url"))


def _usable(profile, env, table, requires_vision):
    """Why this profile cannot answer, or None when it can."""
    provider = _text(profile.get("provider"))
    spec = table.get(provider)
    if spec is None:
        return "unknown_provider"
    if requires_vision and not spec.get("supports_vision"):
        return "no_vision"
    if spec.get("api_format") == "litert_local":
        local = _local_model(env, _text(profile.get("model_id")))
        if not local or not local.get("installed"):
            return "model_not_installed"
        if requires_vision and not local.get("vision"):
            return "no_vision"
    elif spec.get("api_format") == "on_device":
        if not (env or {}).get("on_device_available"):
            return "model_not_installed"
    if _credential_for(profile, spec, env)["source"] == "missing":
        return "no_key"
    if not _is_local(spec) and not _effective_base_url(profile, spec):
        return "no_base_url"
    return None


def _route(profile, env, table, role):
    provider = _text(profile.get("provider"))
    spec = table.get(provider) or {}
    model = normalize_model_id(profile.get("model_id"))
    local = _is_local(spec)
    timeout = env.get("request_timeout_seconds") if spec.get("configurable_timeout") else None
    context = None
    if spec.get("api_format") == "litert_local":
        entry = _local_model(env, model) or {}
        context = entry.get("context_tokens")
    return {
        "role": role,
        "profile_id": profile.get("id"),
        "provider": provider,
        "model": model,
        "base_url": "" if local else _effective_base_url(profile, spec),
        "api_format": spec.get("api_format"),
        "credential": _credential_for(profile, spec, env),
        "vertex": profile.get("vertex"),
        "request_timeout_seconds": timeout,
        "max_response_tokens": env.get("max_response_tokens"),
        "token_limit_key": token_limit_key(provider, model),
        "context_tokens": context,
    }


def _synthesized_default(env, table, role, now_ms=0):
    profile = {
        "id": None,
        "nickname": DEFAULT_PROVIDER,
        "provider": DEFAULT_PROVIDER,
        "model_id": default_model_for(DEFAULT_PROVIDER, table),
        "base_url": None,
        "vertex": None,
        "credential_ref": "provider:" + DEFAULT_PROVIDER,
        "created_ms": now_ms,
        "updated_ms": now_ms,
    }
    return _route(profile, env, table, role)


def role_for(roles, requires_vision):
    """Image requests always use the vision role; text requests use the dedicated one when on."""
    if requires_vision:
        return "image"
    text = (roles or {}).get("text") or {}
    return "text" if text.get("enabled") else "image"


def resolve_role(payload):
    """The single answer to 'which provider, which model, which key'.

    Replaces AIProviderSettings.currentConfig(requiresVision:) and the inline block in Android's
    ChatService.send.

    It NEVER substitutes a different profile for the one the user chose. A chosen profile that
    cannot answer comes back with `blocked` set and the caller refuses -- silently answering
    elsewhere would be wrong for an ordinary cloud profile and unacceptable for an on-device one,
    where the substitute would put health data on someone else's server without a word (rule 1).

    A route always comes back so the caller has something to name in that refusal. Only a device
    with nothing configured at all gets the unblocked Gemini default, which is what the app shows
    today before the first key is entered.
    """
    env = payload.get("env") or {}
    table = provider_defaults(env)
    profiles = payload.get("profiles") or []
    roles = payload.get("roles") or {}
    requires_vision = bool(env.get("requires_vision"))
    role = payload.get("role") or role_for(roles, requires_vision)
    by_id = {p.get("id"): p for p in profiles}
    pointer = (roles.get(role) or {}).get("profile_id")
    profile = by_id.get(pointer)

    if profile is None:
        route = _synthesized_default(env, table, role)
        route["fell_back"] = True
        # A dangling pointer means a profile was deleted without repointing its role, which rule 1
        # forbids; an absent one on an empty device means nothing is set up yet. Only the second is
        # allowed to answer.
        route["blocked"] = None if (not profiles and pointer is None) else "no_profile"
        return route

    route = _route(profile, env, table, role)
    route["fell_back"] = False
    route["blocked"] = _usable(profile, env, table, requires_vision)
    return route


def _same_endpoint(a, b):
    return (a.get("provider") == b.get("provider") and a.get("model") == b.get("model")
            and _text(a.get("base_url")) == _text(b.get("base_url")))


def resolve_fallback(payload):
    """The one retry, or nothing.

    A fallback pointing at the same endpoint as the primary is not a fallback; it is a guaranteed
    second failure. Compare the endpoint triple, never the profile id: two profiles can describe one
    endpoint, and one profile can never be its own fallback.
    """
    env = payload.get("env") or {}
    table = provider_defaults(env)
    profiles = payload.get("profiles") or []
    roles = payload.get("roles") or {}
    primary = payload.get("primary") or {}
    requires_vision = bool(env.get("requires_vision"))
    # Unchanged from today: an image request falls back on the image pair, anything else on the text
    # pair, whether or not a separate text provider is configured.
    role = payload.get("role") or ("image_fallback" if requires_vision else "text_fallback")
    if role not in FALLBACK_OF:
        return {"route": None, "reason": "not_a_fallback_role"}

    row = roles.get(role) or {}
    if not row.get("enabled"):
        return {"route": None, "reason": "disabled"}
    by_id = {p.get("id"): p for p in profiles}
    profile = by_id.get(row.get("profile_id"))
    if profile is None:
        return {"route": None, "reason": "no_profile"}
    reason = _usable(profile, env, table, requires_vision)
    if reason is not None:
        return {"route": None, "reason": reason}
    route = _route(profile, env, table, role)
    if _same_endpoint(route, primary):
        return {"route": None, "reason": "same_endpoint"}
    return {"route": route, "reason": None}


# ---------------------------------------------------------------------------------------------
# Per-conversation override (conversations.provider_override, no schema change)
# ---------------------------------------------------------------------------------------------

def _valid_profile_id(value):
    if not value or len(value) > MAX_PROFILE_ID_LENGTH:
        return False
    for ch in value:
        if ch not in PROFILE_ID_CHARS:
            return False
    return True


def parse_override(raw):
    """Read `conversations.provider_override`, old form or new.

    Old rows hold a bare provider token, written by the Health-Records "Use on-device Coach" flow.
    New rows hold `profile:<id>|<provider token>`. The provider is carried AFTER the id on purpose:
    this column travels in the chat archive, so a conversation imported onto another device names a
    profile that does not exist there and must degrade to the provider rather than quietly answer on
    the wrong model.
    """
    value = _trim(raw)
    if not value:
        return {"profile_id": None, "provider": None}
    if value.startswith(OVERRIDE_PREFIX):
        body = value[len(OVERRIDE_PREFIX):]
        if OVERRIDE_SEPARATOR in body:
            pid, provider = body.split(OVERRIDE_SEPARATOR, 1)
        else:
            pid, provider = body, ""
        pid = pid.strip()
        provider = provider.strip()
        if _valid_profile_id(pid):
            return {"profile_id": pid, "provider": provider or None}
        # Not a profile id after all; the whole string is somebody's provider token.
        return {"profile_id": None, "provider": value}
    return {"profile_id": None, "provider": value}


def encode_override(profile_id, provider):
    """Write the column. `profile_id` None gives the bare token the records flow has always written."""
    pid = _trim(profile_id)
    token = _trim(provider)
    if OVERRIDE_SEPARATOR in token or OVERRIDE_SEPARATOR in pid:
        raise ValueError("a provider token or profile id may not contain %r" % OVERRIDE_SEPARATOR)
    if not pid:
        return token or None
    if not _valid_profile_id(pid):
        raise ValueError("bad profile id %r" % pid)
    return OVERRIDE_PREFIX + pid + OVERRIDE_SEPARATOR + token


# ---------------------------------------------------------------------------------------------
# Google Vertex AI routing
# ---------------------------------------------------------------------------------------------

def _vertex_host(location):
    for rule in VERTEX_DOC["hosts"]:
        if rule["match"] == "global" and location == "global":
            return rule["host"]
        if rule["match"] == "multi_region" and location in rule["values"]:
            return rule["host"].replace("{location}", location)
    for rule in VERTEX_DOC["hosts"]:
        if rule["match"] == "region":
            return rule["host"].replace("{location}", location)
    return ""


def _vertex_route(model_id):
    lowered = model_id.lower()
    for route in VERTEX_DOC["routes"]:
        for prefix in route["prefixes"]:
            if lowered.startswith(prefix):
                return route
    for route in VERTEX_DOC["routes"]:
        if not route["prefixes"]:
            return route
    return None


def vertex_endpoint(project, location, model_id):
    """Host + path + transport for one Vertex call.

    One provider, three transports: Gemini and Claude are different APIs that happen to share a
    host, and everything else goes through the OpenAI-compatible surface.
    """
    project = _trim(project)
    location = _trim(location) or VERTEX_DOC["default_location"]
    model_id = _trim(model_id)
    if not project:
        return {"ok": False, "reason": "no_project"}
    if not model_id:
        return {"ok": False, "reason": "no_model"}
    route = _vertex_route(model_id)
    if route is None:
        return {"ok": False, "reason": "no_route"}
    host = _vertex_host(location)
    if not host:
        return {"ok": False, "reason": "no_host"}
    prefix = VERTEX_DOC["path_prefix"].replace("{project}", project).replace("{location}", location)
    path = route["path"].replace("{model}", model_id)
    return {
        "ok": True,
        "url": host + prefix + path,
        "publisher": route["publisher"],
        "transport": route["transport"],
        "body_extras": dict(route["body_extras"]),
        "drops_model_from_body": bool(route["drops_model_from_body"]),
        "location": location,
    }


# ---------------------------------------------------------------------------------------------
# Local model catalog
# ---------------------------------------------------------------------------------------------

def memory_gate(size_bytes):
    """max(6 GiB, artifact * 2 + 2 GiB rounded up to an even GiB).

    Reproduces the shipped Gemma 4 E2B gate of 8 GiB exactly, so adding models does not quietly
    re-gate the one already installed on people's phones.
    """
    needed = size_bytes / float(GIB) * 2 + 2
    even = int(math.ceil(needed / 2.0)) * 2
    return max(6, even) * GIB


def memory_class_gb(physical_memory_bytes):
    """Total RAM in GiB, rounded UP -- an "8 GB" phone reports a little less than 8 GiB."""
    return int(math.ceil((physical_memory_bytes or 0) / float(GIB)))


def required_headroom(size_bytes, policy=None):
    policy = policy or CATALOG_DOC["downloadPolicy"]
    return max(policy["minimumHeadroomBytes"], size_bytes * policy["headroomPercent"] // 100)


def local_catalog_state(payload):
    """What Settings shows for every downloadable model, in the order it shows them."""
    catalog = payload.get("catalog") or CATALOG_DOC
    device = payload.get("device") or {}
    installed = payload.get("installed") or []
    has_token = bool(payload.get("has_hf_token"))
    platform = payload.get("platform") or "android"
    abis = device.get("abis") or []
    supported = catalog.get("supportedAbis") or []
    memory = memory_class_gb(device.get("physical_memory_bytes"))
    free = device.get("free_bytes")

    rows = []
    for model in catalog["models"]:
        artifact = model["artifact"]
        size = artifact["sizeBytes"]
        gate = model["memoryPolicy"]["minimumPhysicalMemoryBytes"]
        row = {
            "id": model["id"],
            "display_name": model["displayName"],
            "size_bytes": size,
            "vision": "image" in model["capabilities"],
            "context_tokens": model["contextTokens"],
            "gated": bool(artifact["access"]["gated"]),
            "minimum_memory_bytes": gate,
            "required_headroom_bytes": required_headroom(size, catalog.get("downloadPolicy")),
        }
        if platform not in model["platforms"]:
            continue
        if model["id"] in installed:
            row["state"] = "installed"
            row["reason"] = None
        elif device.get("low_ram"):
            row["state"] = "ineligible"
            row["reason"] = "low_ram_device"
        elif supported and abis and not [a for a in abis if a in supported]:
            row["state"] = "ineligible"
            row["reason"] = "unsupported_abi"
        elif memory * GIB < gate:
            row["state"] = "ineligible"
            row["reason"] = "insufficient_memory"
        elif row["gated"] and not has_token:
            row["state"] = "needs_token"
            row["reason"] = "needs_token"
        elif free is not None and free < size + row["required_headroom_bytes"]:
            row["state"] = "ineligible"
            row["reason"] = "insufficient_space"
        else:
            row["state"] = "available"
            row["reason"] = None
        rows.append(row)

    order = {"installed": 0, "available": 1, "needs_token": 2, "ineligible": 3}
    ranked = sorted(range(len(rows)), key=lambda i: (order[rows[i]["state"]], i))
    return {"models": [rows[i] for i in ranked]}


# ---------------------------------------------------------------------------------------------
# Vector dispatch
# ---------------------------------------------------------------------------------------------

def run_case(function, inp):
    if function == "migrate_profiles":
        return migrate_profiles(inp)
    if function == "adopt_legacy_primary":
        return adopt_legacy_primary(inp)
    if function == "add_profile":
        return add_profile(inp)
    if function == "assign_role":
        return assign_role(inp)
    if function == "project_legacy":
        return project_legacy(inp.get("profiles") or [], inp.get("roles") or {})
    if function == "resolve_role":
        return resolve_role(inp)
    if function == "resolve_fallback":
        return resolve_fallback(inp)
    if function == "resolve_credential":
        return {"source": resolve_credential(inp.get("credential_ref"),
                                             bool(inp.get("requires_key", True)),
                                             bool(inp.get("has_profile_key")),
                                             bool(inp.get("has_provider_key")))}
    if function == "keys_to_delete":
        return keys_to_delete(inp)
    if function == "vertex_endpoint":
        return vertex_endpoint(inp.get("project"), inp.get("location"), inp.get("model"))
    if function == "override":
        op = inp.get("op")
        if op == "parse":
            return parse_override(inp.get("raw"))
        if op == "encode":
            try:
                return {"raw": encode_override(inp.get("profile_id"), inp.get("provider"))}
            except ValueError as e:
                return {"raw": None, "error": str(e)}
        raise ValueError(op)
    if function == "token_limit_key":
        return {"key": token_limit_key(inp.get("provider"), inp.get("model"))}
    if function == "local_catalog_state":
        return local_catalog_state(inp)
    raise ValueError("unknown function %r" % (function,))
