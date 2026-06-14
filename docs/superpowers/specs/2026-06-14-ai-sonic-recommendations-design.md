# AI Sonic Recommendations Design

> **Status:** Design — pending implementation plan.
> **Scope:** Introduce an on-device audio-embedding substrate (CLAP) and use it to power *sonically-grounded* recommendations — first as a passive "discoveries you'll like" mix (no input), later as a query-driven "ask the vibe" mix. Both ride the existing Stash Mix machinery.
> **Related:** Supersedes the tag-only ranking direction in `2026-05-11-stash-mix-recommendation-pivot-design.md`. Builds on the discovery pipeline (`discovery_queue` → `StashDiscoveryWorker`) and the `MixGenerator` scoring model (`2026-05-08-first-listen-tag-fallback-design.md`, `2026-05-08-discovery-survivor-cap.md`).

---

## Goal

Today's Stash Mixes rank and discover music using **Last.fm tags + play signals**. Tags are a poor proxy for how music actually *sounds*: two tracks tagged "rock" can be sonically unrelated. The user named three pains that all share this root cause:

1. **Sonic mismatch** — mixes feel tonally inconsistent because similarity is tag-based, not sound-based.
2. **Shallow / repetitive** — discovery is constrained to Last.fm's tag/artist graph.
3. **No intent control** — there's no way to say "calm piano for a rainy drive."

The fix is a shared **audio-embedding substrate**: represent every track as a vector in a space where *sonic* similarity is real distance, then use that space to (a) discover net-new music that sounds like the user's taste, and (b) eventually match free-text vibe queries. The same substrate serves both.

### Hard constraints (from the user)

- **Completely free, ideally open source.** No paid per-query LLM in the core path. (Rules out an LLM-parsing approach for "ask the vibe.")
- **Quality over offline.** Offline operation is a welcome side effect, not a requirement.
- **"It should know you."** Recommendations must be grounded in the user's actual listening — personalization is core, not garnish.
- **Discovery must be net-new.** Recommendations surface tracks the user does *not* already have, grounded in their library's sound — not a re-shuffle of owned tracks.
- **Passive mode is crucial.** The system must surface "discoveries it thinks you'll like" with **no input at all**, in addition to the query-driven mode.

## Non-goals

- **Paid/LLM query parsing.** Excluded by the "completely free" constraint. "Ask the vibe" uses the CLAP text encoder, not an LLM.
- **Library-only vibe results.** Explicitly rejected by the user — vibe and passive modes both must discover net-new tracks.
- **A backend embedding service.** Audio files live on-device and uploading them is infeasible (the user is on a metered/maxed hotspot). All embedding runs on-device. (See Approach B, rejected.)
- **Bundling the model in the APK.** The CLAP model is downloaded on first feature-enable, not shipped in the APK.
- **Replacing the existing tag/affinity engine.** The sonic layer is *additive*. With no vectors present, mixes behave exactly as they do today.
- **A vector-index database.** Brute-force cosine over a few thousand vectors is sub-millisecond; no FAISS/sqlite-vss needed.

## Chosen approach (and rejected alternatives)

**Approach A — Unified on-device CLAP substrate (chosen).** One open-source CLAP model (LAION-CLAP, music-tuned checkpoint, exported to ONNX, run via ONNX Runtime for Android) produces 512-dim vectors for both **audio** (each track) and **text** (a query) in one shared space. Both recommendation features fall out of this one substrate. Free, open source, private, offline-capable.

**Approach B — Backend-assisted CLAP (rejected).** Offload text-query encoding / model hosting to a server or Cloudflare Worker. Rejected: the audio encoder *must* run on-device (uploading audio is infeasible), so once it's present the text encoder is nearly free to run locally too. A backend adds cost and infra for negligible gain and conflicts with "completely free."

**Approach C — Metadata-only text embeddings (rejected, kept as fallback).** Embed tags/artist/genre with a small text model (e.g. all-MiniLM). Cheaper and smaller, but inherits the exact *sonic-mismatch* pain the user wants gone. Retained only as the escape hatch if the Phase-0 spike shows on-device CLAP is infeasible.

## Scale assumption

Target libraries are **~1k–5k downloaded tracks** ("medium"). This makes on-device embedding viable: a one-time backfill is an overnight charge, and new tracks are embedded incrementally at download. It also means brute-force cosine ranking (5k × 512 ≈ 2.5M mults) is trivial — no vector index required.

---

## Phase 0 — Gating spike (must pass before Phase 1)

The entire design depends on one unproven fact: that CLAP runs acceptably on a phone. Before committing to Phase 1, a throwaway spike on the Pixel must answer:

1. **Size** — how small does the ONNX model quantize to? fp32 is likely ~500 MB; int8 target ~150 MB. This decides the download-on-enable strategy.
2. **Speed** — seconds-per-track to embed, so we know whether a 5k backfill is an overnight job or a multi-day one.
3. **Signal** — does cosine actually separate music that sounds different? Embed ~20 hand-picked tracks across genres and inspect the similarity matrix.

**Exit criteria:** model quantizes to a downloadable size (≤ ~200 MB target), per-track embedding completes in seconds (not minutes) on the Pixel, and cosine visibly clusters same-genre/different-genre tracks. If size or speed fails, fall back toward Approach C. If signal fails, the substrate is unfit and the project stops. The spike is throwaway code; nothing from it ships as-is.

---

## Architecture overview

```
┌─────────────────────────────────────────────────────────────────────┐
│ Substrate (Phase 1)                                                  │
│   ClapModelProvider     download + checksum the ONNX model on enable │
│   ClapEmbedder          ONNX sessions; audio→mel preprocessing;      │
│                         embedAudio(file) / embedText(query) → 512-d  │
│   track_embedding (Room) track_id, vector BLOB, model_version, ts    │
│   TrackEmbeddingWorker  incremental (on download-finalize)           │
│   TrackEmbeddingBackfillWorker  one-time catch-up (charging)         │
│                                                                      │
│ Passive discovery (Phase 1)                                          │
│   SonicFingerprint      CLAP centroid(s) of most-played/loved tracks │
│   MixGenerator          + sonic-affinity scoring term (additive)     │
│   StashMixRefreshWorker  steers candidate generation by nearest-     │
│                         sounding library tracks → track.getSimilar   │
│   (existing discovery_queue → StashDiscoveryWorker fetch/download)   │
│                                                                      │
│ Active "ask the vibe" (Phase 2)                                      │
│   stash_mix_recipes      + vibe_query column, seedStrategy = "VIBE"  │
│   MixGenerator          VIBE path: embedText(query) → sonic ranking  │
│   Mix Builder UI         "Describe a vibe…" field; immediate         │
│                         materialize on create                        │
└─────────────────────────────────────────────────────────────────────┘
```

The sonic layer is **additive to the existing engine**. Net-new discovery reuses the existing `discovery_queue` → `StashDiscoveryWorker` download pipeline; the sonic layer only changes how candidates are *steered* and *ranked*. No new bandwidth burden beyond discovery that already happens.

---

## Component 1 — The substrate

### Model

LAION-CLAP (open source), music-tuned checkpoint, exported to ONNX and run via ONNX Runtime for Android (`onnxruntime-android` AAR). Two encoders share one 512-dim space:

- `embedAudio(file): FloatArray` — audio encoder.
- `embedText(query): FloatArray` — text encoder.

The audio encoder *must* run on-device (files never leave the phone). Because it's already on-device, the text encoder runs locally too — keeping the whole feature free and offline-capable.

### Storage

New Room table `track_embedding`:

| column | type | notes |
|---|---|---|
| `track_id` | INTEGER PK | FK → `tracks.id`, `onDelete = CASCADE` |
| `vector` | BLOB | 512 × float32 = 2048 bytes |
| `model_version` | INTEGER | bump to trigger lazy re-embed on model swap |
| `status` | TEXT | `ok` / `failed` (un-embeddable file) |
| `created_at` | INTEGER | epoch ms |

~2 KB/track → ~10 MB for 5k tracks. Vectors are loaded into memory and cosine-scanned directly; no vector-index DB. A `failed` row records the model version so a bad decode isn't retried until a model bump.

### `ClapEmbedder`

Lives in a focused module (`data:embedding`, or `core:data` if module overhead isn't justified). Wraps the ONNX sessions and owns audio preprocessing:

- CLAP expects **48 kHz mono log-mel** input. Reuse the ffmpeg already in the app (download/remux path) to decode.
- Sample **3 × 10 s windows** (skip intro, middle, near-end), embed each, **mean-pool to one vector**. Cheaper than embedding the whole track and robust to intros/outros.
- Lazy-load the ONNX session on first use; hold it for the worker's lifetime; release on worker completion to bound memory.

### `ClapModelProvider`

Gates all embedding work. If the model isn't present, embedding workers no-op and reschedule, and the sonic scoring term contributes nothing (graceful degradation). On first feature-enable, downloads the quantized ONNX model from a GitHub release / CDN and verifies a checksum before use.

### Embedding workers

- **`TrackEmbeddingWorker` (incremental)** — enqueued one-shot when a download finalizes (hook into `TrackFinalizer`). Embeds the single new track. New music is vectorized as it arrives.
- **`TrackEmbeddingBackfillWorker` (one-time catch-up)** — finds tracks with no current-version embedding and processes bounded batches, constrained to **charging** (and idle). Self-terminates when nothing is left. Treats stale-`model_version` rows the same as missing.

---

## Component 2 — Passive sonic discovery (Phase 1, the crucial no-input mode)

### Sonic fingerprint

`SonicFingerprint` computes the user's taste in CLAP space:

- Take the CLAP vectors of the user's **most-played / loved** tracks (reuse the existing decayed play-affinity to weight/select; `loved` from Last.fm).
- Cluster them into **a small number of centroids** (light k-means, small fixed k, e.g. 2–4, with a minimum tracks-per-cluster) so a multi-genre taste (e.g. metal *and* ambient) isn't averaged into a muddy single centroid.
- Centroids are computed from a **stable daily snapshot** (see Determinism below).

**Cold-start guard:** below a threshold of embedded *played* tracks (e.g. `< N`), there is no reliable fingerprint — fall back to today's tag/affinity discovery and form centroids once enough signal exists.

### The discovery loop (shared by both modes)

1. **Seed** — passive mode seeds from the sonic-fingerprint centroids.
2. **Steer candidate generation** — run the existing `track.getSimilar` on the *library tracks closest to the centroids* (discovery anchored to how the user's favorites actually *sound*, not just their tags). This feeds the existing `discovery_queue`.
3. **Fetch + download** — the existing `StashDiscoveryWorker` resolves and downloads candidates (unchanged).
4. **Embed** — downloaded candidates get CLAP-embedded by the incremental worker (already in Component 1).
5. **Sonic rank/filter** — score survivors by cosine to the nearest centroid, **blended** with the existing affinity/tag/skip signals.

### `MixGenerator` integration

The sonic-affinity term is **additive** to the existing linear scoring model in `MixGenerator`:

- Add `sonicAffinity(track) = cosine(trackVector, nearestCentroid)` as a new weighted term alongside the existing affinity, tag-cosine, completion, loved, and skip terms.
- When a track has no embedding (model absent, not yet backfilled, streamed-only), `sonicAffinity` contributes **neutral/zero** — the engine degrades to exactly today's behavior. This is the regression-safety property.
- Surfaces as a builtin mix: the existing "Daily Discover"-style mix, upgraded from tag-grounded to sonically-grounded. (Exact recipe wiring — whether the sonic term applies to all builtins or a dedicated sonic mix — is settled in the implementation plan; the engine change is the same either way.)

---

## Component 3 — Active "ask the vibe" (Phase 2)

A vibe is a **new kind of Stash Mix recipe**, reusing the entire existing recipe → generator → refresh-worker → Home-card machinery.

### Data model

- Add nullable column `vibe_query` (TEXT) to `stash_mix_recipes`.
- New `seedStrategy = "VIBE"`.

No new tables, no parallel system. A vibe mix sits next to Daily Discover and custom mixes on Home.

### Creation flow

The existing Mix Builder gains a **"Describe a vibe…"** field alongside the genre/mood pickers. Saving *"calm piano for a rainy drive"*:

1. Creates a `VIBE` recipe with `vibe_query` set.
2. **Materializes immediately** (synchronous embed + rank + populate) so the user hears it at once — no waiting for a refresh cycle.
3. Thereafter it's a normal **living** mix: it re-runs the query on every refresh and rotates like the others.

### `MixGenerator` VIBE path

When `seedStrategy == VIBE`:

- `seedVector = embedText(vibe_query)`.
- Steer candidate generation by the **library tracks nearest `seedVector`** → `track.getSimilar` on those → net-new tracks that *sound like the vibe* → download → embed (same loop as passive).
- Rank by cosine to `seedVector`, **blended** with the same affinity/taste signals every mix uses (the "balanced blend" — sonic match sets the candidate pool, taste meaningfully re-ranks).
- If the model isn't ready, the recipe's Home card shows a "preparing…" state rather than a broken mix.

### Personalization model ("balanced blend")

For both modes, final score ≈ `α · sonicCosine + β · tasteScore` with `α ≈ β`, where `tasteScore` reuses the existing `MixGenerator` affinity + tag-cosine machinery. "Knows you" is literally the same taste profile the mixes already use — no separate taste model. Exact weights are tuned during implementation against on-device results.

---

## Determinism & refresh idempotency

The current refresh loop is deterministic-per-day on purpose — unseeded jitter previously caused the "load 40 then repopulate" bug (`project_mix_refresh_loop_fix`). The sonic additions must preserve this:

- Sonic-fingerprint centroids are computed from a **stable daily snapshot** (same day → same centroids), so a sonic/VIBE mix is idempotent within a day and the existing materialize-only short-circuit still fires.
- The sonic term feeds the same **seeded per-recipe-per-day jitter** the generator already uses; it must not introduce a fresh source of nondeterminism.

---

## Failure modes & edge cases

1. **Model not downloaded / mid-backfill** — sonic term contributes nothing; builtins behave exactly like today. Optional quiet "getting to know your library… X / Y" hint on the card.
2. **New user / too little history** — no reliable fingerprint; fall back to current tag/affinity discovery until `≥ N` embedded played tracks exist.
3. **Streamed-only / un-embeddable track** — neutral sonic score; never excluded from the existing tag-based path.
4. **Off-vibe candidate downloaded** — wasted download, bounded by existing discovery caps; reduced by steering generation sonically before download.
5. **Corrupt file / decode failure** — mark `track_embedding.status = failed` at the current model version; never retried until a model bump; never crashes the app.
6. **Model version bump** — lazy re-embed (backfill treats stale rows as missing); centroids recomputed from the new vectors.
7. **ONNX OOM / native crash** — guarded; the embedding work fails the single track/batch, not the app.
8. **Multi-genre taste averaged to mush** — mitigated by multiple centroids (k-means), not a single mean vector.

---

## Testing

The ranking is the part that matters and it is pure math, so it tests cleanly. Match existing module conventions: **MockK** for `MixGenerator` / worker tests (cf. `MixGeneratorComputeUserTopTagsTest`, `LosslessRetryWorkerTest`); **Robolectric + Room in-memory** for DAO/store tests (cf. `DiscoveryQueueDaoCapTest`).

- **Unit (MockK):**
  - Cosine + balanced-blend ranking with *synthetic injected* vectors (deterministic).
  - `SonicFingerprint` centroid/clustering from synthetic played-track vectors (correct number of centroids, correct grouping).
  - **No-vectors regression guard** — with no embeddings, `MixGenerator` output is identical to today's tag-based behavior.
  - VIBE path with an injected `embedText` stub ranks candidates by query cosine as expected.
- **Worker (Robolectric + Room):**
  - `TrackEmbeddingBackfillWorker` selects only missing/stale embeddings, respects bounded batch + charging constraint.
  - `track_embedding` round-trips a vector BLOB and `model_version`.
- **ONNX inference itself** — not unit-friendly; covered by the **Phase-0 spike** plus one instrumented sanity test (known clips → expected cosine separation).

### Manual test on Pixel

1. Enable the feature → model downloads + checksum verifies; no crash if interrupted/resumed.
2. Let the backfill run while charging; confirm progress and self-termination.
3. With a fingerprint formed, refresh the passive discovery mix → expect net-new tracks that audibly cohere with heavy-rotation taste, distinct from a tag-only run.
4. (Phase 2) Create a vibe mix "calm piano for a rainy drive" → materializes immediately; results match the vibe and lean toward personal taste; it persists on Home and refreshes.
5. Toggle the model off / fresh install → confirm mixes fall back to today's behavior with no errors.

---

## Phasing

- **Phase 0** — gating spike (size / speed / signal). Throwaway. Blocks everything.
- **Phase 1** — substrate (`ClapEmbedder`, `ClapModelProvider`, `track_embedding`, embedding workers) **+ passive sonic-fingerprint discovery** (the crucial no-input mode) wired into `MixGenerator` additively.
- **Phase 2** — active "ask the vibe": `vibe_query` column, `VIBE` seedStrategy, Mix Builder field, immediate materialize. Layered on once Phase 1 proves the substrate works.

## Open questions

1. **Module placement** — new `data:embedding` Gradle module vs. folding `ClapEmbedder` into `core:data`. Decide in the plan based on dependency weight (ONNX Runtime AAR).
2. **Exact CLAP checkpoint + quantization** — pinned by the Phase-0 spike results (size/speed), not in advance.
3. **Centroid count `k` and cold-start threshold `N`** — start with small defaults (k ≈ 2–4, N ≈ a few dozen embedded played tracks); tune against on-device results.
4. **Sonic term: all builtins vs. one dedicated sonic mix** — engine change is identical; recipe wiring decided in the plan.
