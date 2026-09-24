#!/usr/bin/env python3
"""Validate the pinned LiteRT-LM artifacts without downloading them.

    python3 local-models/verify_catalog.py
    python3 local-models/verify_catalog.py --online   # also re-read every artifact from the Hub

v1 validated one hardcoded Gemma entry field by field. v2 holds several models, so this validates
the SCHEMA and the per-entry invariants instead: every new model is checked by the same rules the
shipped one is, and adding one is a catalog edit rather than a code edit.

The shape rules here and in scripts/ai_contract_check.py deliberately overlap: this file is the one
that can also talk to the network, and it is what pins a new artifact's revision, size and checksum
before that artifact is ever offered to anyone.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

REVISION_RE = re.compile(r"^[0-9a-f]{40}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
MODEL_ID_RE = re.compile(r"^[a-z0-9][a-z0-9.-]*$")
GIB = 1024 ** 3
RUNTIME = {"family": "LiteRT-LM", "version": "0.16.0"}
PLATFORMS = ("android", "ios")
CAPABILITIES = ("text", "image")
USER_AGENT = "Ayuvo-Catalog-Validator/2"


class ValidationError(Exception):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValidationError(message)


def memory_gate(size_bytes: int) -> int:
    """max(6 GiB, artifact * 2 + 2 GiB rounded up to an even GiB).

    Reproduces the shipped Gemma 4 E2B gate of 8 GiB exactly, so adding models cannot quietly
    re-gate the one already installed on people's phones.
    """
    needed = size_bytes / float(GIB) * 2 + 2
    return max(6, int(math.ceil(needed / 2.0)) * 2) * GIB


def read_catalog(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValidationError(f"Cannot read {path}: {error}") from error
    require(isinstance(value, dict), "catalog must be a JSON object")
    return value


def validate_model(model: dict[str, Any], policy: dict[str, Any], seen: set[str]) -> None:
    model_id = model.get("id")
    require(isinstance(model_id, str) and MODEL_ID_RE.fullmatch(model_id) is not None,
            f"bad model id {model_id!r}")
    require(model_id not in seen, f"duplicate model id {model_id}")
    seen.add(model_id)
    where = f"{model_id}: "

    require(bool(model.get("displayName")), where + "no displayName")
    platforms = model.get("platforms")
    require(isinstance(platforms, list) and platforms
            and all(p in PLATFORMS for p in platforms), where + "bad platforms")
    require(model.get("runtime") == RUNTIME, where + "unexpected runtime")
    require(model.get("format") == "litertlm", where + "only LiteRT-LM packages are runnable")

    capabilities = model.get("capabilities")
    require(isinstance(capabilities, list) and capabilities[:1] == ["text"]
            and all(c in CAPABILITIES for c in capabilities), where + "bad capabilities")
    context = model.get("contextTokens")
    require(isinstance(context, int) and 0 < context <= 8192, where + "bad contextTokens")

    memory = model.get("memoryPolicy")
    require(isinstance(memory, dict), where + "memoryPolicy is required")

    artifact = model.get("artifact")
    require(isinstance(artifact, dict), where + "artifact is required")
    repository = artifact.get("repository")
    revision = artifact.get("revision")
    filename = artifact.get("filename")
    require(isinstance(repository, str) and repository.count("/") == 1, where + "bad repository")
    require(isinstance(revision, str) and REVISION_RE.fullmatch(revision) is not None,
            where + "revision must be a full commit SHA")
    require(isinstance(filename, str) and filename == Path(filename).name and ".." not in filename,
            where + "unsafe filename")
    require(filename.endswith(".litertlm"), where + "artifact is not a .litertlm package")
    expected_url = f"https://huggingface.co/{repository}/resolve/{revision}/{urllib.parse.quote(filename)}"
    require(artifact.get("url") == expected_url, where + "URL must be pinned to its revision")

    size = artifact.get("sizeBytes")
    require(isinstance(size, int) and size > 0, where + "bad sizeBytes")
    require(memory.get("minimumPhysicalMemoryBytes") == memory_gate(size),
            where + f"RAM gate must be {memory_gate(size) // GIB} GiB by the documented rule")

    sha256 = artifact.get("sha256")
    require(isinstance(sha256, str) and SHA256_RE.fullmatch(sha256) is not None,
            where + "invalid SHA-256")

    access = artifact.get("access")
    require(isinstance(access, dict) and set(access) == {"anonymous", "gated", "supportsByteRanges"},
            where + "bad access policy")
    require(access["anonymous"] is not access["gated"],
            where + "a gated artifact cannot also be anonymous")

    license_ = model.get("license") or {}
    require(bool(license_.get("spdx")), where + "no SPDX licence id")
    require(bool(license_.get("textURL")), where + "no licence text URL")
    require(bool(license_.get("declaredByURL")), where + "no licence source URL")
    if access["gated"]:
        # A gated artifact needs a token AND a human acceptance of its terms; both platforms show
        # the licence link on the blocked row, so the URL is not optional.
        require(license_["textURL"].startswith("https://"), where + "gated models need a terms URL")

    require(policy["minimumHeadroomBytes"] > 0, "minimumHeadroomBytes must be positive")


def validate_catalog(catalog: dict[str, Any]) -> list[dict[str, Any]]:
    require(catalog.get("schemaVersion") == 2, "schemaVersion must be 2")
    require(bool(catalog.get("catalogVersion")), "catalogVersion is required")
    models = catalog.get("models")
    require(isinstance(models, list) and models, "catalog must contain at least one model")

    policy = catalog.get("downloadPolicy")
    require(isinstance(policy, dict), "downloadPolicy is required")
    require(policy.get("checksumAlgorithm") == "sha256", "checksum algorithm must be sha256")
    require(policy.get("minimumHeadroomBytes") == 256 * 1024 ** 2, "minimum headroom must be 256 MiB")
    require(policy.get("headroomPercent") == 10, "headroom must be 10 percent")
    require(policy.get("stagingSuffix") == ".part", "staging suffix must be .part")

    abis = catalog.get("supportedAbis")
    require(isinstance(abis, list) and "arm64-v8a" in abis, "arm64-v8a must be supported")

    seen: set[str] = set()
    for model in models:
        validate_model(model, policy, seen)
    return models


def fetch_json(url: str) -> dict[str, Any]:
    request = urllib.request.Request(
        url, headers={"Accept": "application/json", "User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)
    except Exception as error:  # noqa: BLE001 - the message is the whole point
        raise ValidationError(f"Cannot fetch {url}: {error}") from error


def validate_online(model: dict[str, Any]) -> None:
    artifact = model["artifact"]
    repository = artifact["repository"]
    revision = artifact["revision"]
    gated = artifact["access"]["gated"]
    where = f"{model['id']}: "

    metadata = fetch_json(
        f"https://huggingface.co/api/models/{repository}/revision/{revision}?blobs=true")
    require(metadata.get("sha") == revision, where + "revision metadata mismatch")
    require(metadata.get("private") is False, where + "repository became private")
    remote_gated = bool(metadata.get("gated"))
    require(remote_gated == gated,
            where + f"repository gating changed (catalog says {gated}, hub says {metadata.get('gated')!r})")

    remote = next((item for item in metadata.get("siblings", [])
                   if item.get("rfilename") == artifact["filename"]), None)
    require(remote is not None, where + "artifact disappeared")
    lfs = remote.get("lfs") or {}
    require(lfs.get("size") == artifact["sizeBytes"], where + "remote size changed")
    # The Hub reports the file's SHA-256 as `sha256` on the revision endpoint and as the LFS `oid`
    # elsewhere; they are the same value.
    remote_sha = lfs.get("sha256") or lfs.get("oid")
    require(remote_sha == artifact["sha256"], where + "remote checksum changed")

    if gated:
        # A gated file cannot be HEADed anonymously, and the app will not try: it asks for a token
        # first. Pinning the metadata above is as far as an unauthenticated check can honestly go.
        return

    request = urllib.request.Request(artifact["url"], method="HEAD",
                                     headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            content_length = int(response.headers.get("Content-Length", "0"))
            accept_ranges = response.headers.get("Accept-Ranges", "").lower()
    except Exception as error:  # noqa: BLE001
        raise ValidationError(where + f"anonymous HEAD failed: {error}") from error
    require(content_length == artifact["sizeBytes"], where + "anonymous content length changed")
    require(accept_ranges == "bytes", where + "byte-range resume is no longer advertised")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path,
                        default=Path(__file__).resolve().with_name("catalog.v2.json"))
    parser.add_argument("--online", action="store_true")
    parser.add_argument("--only", help="check a single model id")
    args = parser.parse_args()
    try:
        models = validate_catalog(read_catalog(args.catalog))
        if args.only:
            models = [m for m in models if m["id"] == args.only]
            require(bool(models), f"no model with id {args.only}")
        if args.online:
            for model in models:
                validate_online(model)
    except ValidationError as error:
        print(f"catalog invalid: {error}", file=sys.stderr)
        return 1
    checked = "online" if args.online else "offline"
    print(f"catalog valid ({checked}): {len(models)} model(s), {len(models)} pinned artifact(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
