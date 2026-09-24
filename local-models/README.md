# Local model artifact contract

Ayuvo offers two kinds of downloadable model:

- **Whisper Base** for speech-to-text on supported phones in every RAM tier.
- **A catalogue of LiteRT-LM chat models** for text (and, where the model has a
  vision tower, image) requests, listed in [`catalog.v2.json`](catalog.v2.json).

More than one chat model may be installed at once; exactly one is loaded into
the runtime at a time, and switching closes the previous engine.

GGUF / llama.cpp, MLX and safetensors builds are still out of scope: both
platforms run LiteRT-LM `0.16.0` and accept `.litertlm` packages only. **Qwen3
30B and 32B are absent because `litert-community` publishes no LiteRT-LM build
above 14B** — not because of a policy choice. The catalogue is data, so the day
such a build appears it is one entry plus a `--online` verification.

## The catalogue

| Model | Artifact | Exact size | Modality | Context | Licence | RAM gate |
| --- | --- | ---: | --- | ---: | --- | ---: |
| Gemma 4 E2B | `gemma-4-E2B-it.litertlm` | 2,588,147,712 B (2.41 GiB) | text + image | 4096 | Apache-2.0 | 8 GiB |
| Qwen3 1.7B | `Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm` | 977,184,032 B (0.91 GiB) | text | 4096 | Apache-2.0 | 6 GiB |
| Qwen3 4B | `qwen3_4b_mixed_int4.litertlm` | 2,659,057,664 B (2.48 GiB) | text | 4096 | Apache-2.0 | 8 GiB |
| Qwen3 8B | `qwen3_8b_mixed_int4.litertlm` | 4,887,412,736 B (4.55 GiB) | text | 4096 | Apache-2.0 | 12 GiB |
| Qwen3 14B | `qwen3_14b_mixed_int4.litertlm` | 8,655,863,808 B (8.06 GiB) | text | 4096 | Apache-2.0 | 20 GiB |
| MedGemma 1.5 4B | `medgemma-1.5-4b-it_q4_block32_vision_ekv2048.litertlm` | 3,023,069,488 B (2.82 GiB) | text + image | 2048 | Health AI Developer Foundations | 8 GiB |

Exact revisions and SHA-256 checksums live in `catalog.v2.json`; the checksum is
the file's Git-LFS object id as the Hugging Face API reports it, and
`verify_catalog.py --online` re-reads it from the Hub.

### Three rules the catalogue encodes

1. **The RAM gate is derived, never typed.** It is
   `max(6 GiB, artifact x 2 + 2 GiB rounded up to an even GiB)`, which reproduces
   the shipped Gemma gate of 8 GiB exactly, so adding models cannot quietly
   re-gate the one already installed on people's phones. Both
   `verify_catalog.py` and `scripts/ai_contract_check.py` assert it.
2. **Context length is per model.** MedGemma's build exports a 2048-entry KV
   cache; the engine's global 4096 would overrun it. The runtime clamps to the
   catalogue value, not to a constant.
3. **Vision is per model, not per provider.** Qwen3 has no vision tower, so a
   Qwen3 profile can serve the text role and never the image role. The
   provider-level `supportsVision` flag cannot express this.

### Gated artifacts

`MedGemma 1.5 4B` is gated on the Hub (`gated: "auto"`). The app therefore
supports an optional Hugging Face token, stored beside the AI keys, and sends it
as `Authorization: Bearer` for gated entries only. Without a token the row is
listed and blocked with the reason, never hidden — and never started and failed
halfway through a 3 GB download.

MedGemma is a research model under the Health AI Developer Foundations terms,
**not** a medical device and not a clinician. Coach's existing guardrails are
unchanged when it answers: describe, never diagnose, never suggest starting,
stopping or changing a dose, and defer to the prescriber.

Before release, each new model needs its notice bundle committed under
`legal/` alongside the LiteRT-LM and Whisper ones, and the MedGemma terms must
be accepted on the account whose token ships in CI.

Android determines the marketed memory class from
`ActivityManager.MemoryInfo.totalMem` because the OS reserves part of physical
RAM before reporting it. iOS similarly rounds the GiB value reported by
`ProcessInfo.physicalMemory` upward to the marketed memory class.
A model remains visible with its requirement on smaller phones, but it does not
appear in provider selectors until the artifact is verified and executable.

Before downloading, check free capacity on the app-private install volume.
Android reserves the larger of 256 MiB or 10% of the artifact; iOS reserves
1 GiB. Download to a sibling partial file, verify exact byte length and a
streaming SHA-256, then atomically move the verified file into place. A partial,
unverified, unsupported, gated-without-a-token or deleted model must never be
selectable.

Deleting an installed model first replaces every profile and role selection
that references it, closes the runtime, and removes only model files. It must
not remove meals, settings, or other user data.

## Whisper Base

Whisper uses platform-specific runtimes and formats, so it is not represented
as one shared artifact in `catalog.v2.json`:

- iOS pins `argmax-oss-swift` / WhisperKit `1.1.0`, variant `base`, which owns
  the multi-file `openai_whisper-base` Core ML download.
- Android pins `dev.ffmpegkit-maintained:whisper-android:1.0.0` and downloads
  `ggml-base.bin` from immutable `ggerganov/whisper.cpp` revision
  `5359861c739e955e79d9a303bcbc70fb988958b1` (147,951,465 bytes, SHA-256
  `60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe`).

Android's native wrapper is arm64-v8a only. Its recorded path uses 16 kHz mono
PCM WAV so local-primary/remote-fallback and remote-primary/local-fallback are
both executable. Native OS speech remains a separate live-partial provider on
both platforms.

## Runtime notices

The Gemma model license and the LiteRT-LM runtime notices are separate. The
exact LiteRT-LM `0.16.0` notice bundle is committed at
[`legal/THIRD_PARTY_NOTICES_LiteRTLM_v0.16.0.txt`](legal/THIRD_PARTY_NOTICES_LiteRTLM_v0.16.0.txt),
packaged by both apps, and exposed offline from Settings → Legal. Its SHA-256 is
`cbff17f4a653c07b4a13201927ab0fb46cc13e592485ee7fe4e2c1d1526ac52b`.

The exact Whisper Base source manifest and notices are committed at
[`legal/THIRD_PARTY_NOTICES_WhisperBase.txt`](legal/THIRD_PARTY_NOTICES_WhisperBase.txt).
It covers the OpenAI model weights, Argmax OSS Swift/WhisperKit `1.1.0`, the
exact iOS Core ML and tokenizer snapshots, the Android wrapper, its embedded
whisper.cpp/miniaudio code and NDK libc++, and Argmax's required
swift-transformers notice. Its SHA-256 is
`24a5707808d5a6545e15a31932e12a881f3bd1b1edef44e666b0f1ad77f63868`.
Bundle this file in both apps and expose it offline from Settings → Legal.

The upstream notice contains GPLv2 and MPL2 text under an ambiguous
`Google Runtime Environment` entry without SBOM relationships. Obtain upstream
or legal confirmation that no incompatible covered code is linked before
shipping the native LiteRT-LM binary through an app store.

## Verification

Run the structural check locally:

```sh
python3 local-models/verify_catalog.py
```

Confirm immutable remote metadata and anonymous range access before release:

```sh
python3 local-models/verify_catalog.py --online
```
