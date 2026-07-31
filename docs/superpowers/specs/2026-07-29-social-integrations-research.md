# Social & cast integrations — research and design

Date: 2026-07-29
Status: research complete, design proposed, not yet approved

Four long-requested features: Discord Rich Presence, ListenBrainz scrobbling, a
fuller Last.fm surface, and Google Cast. Reference implementation studied:
[Echo Music](https://github.com/EchoMusicApp/Echo-Music) (Kotlin, GPL-3.0, ~2.9k
stars, actively pushed) — which does have working Discord presence on Android,
contradicting our earlier "not feasible until desktop" conclusion.

---

## 1. Discord Rich Presence

### Why we concluded it was infeasible, and why that was half-right

Discord's documented Rich Presence path is **IPC**: the game/app opens a local
named pipe or Unix socket to the *desktop Discord client*, which relays presence.
That reasoning is sound and still true — there is no Discord desktop client on
Android, so the IPC path genuinely cannot work. The error was generalising "the
documented path is impossible" into "the feature is impossible".

### Definitions

- **Gateway** — Discord's persistent WebSocket (`wss://gateway.discord.gg`).
  Clients authenticate, then send opcode 3 (`UPDATE_PRESENCE`) to publish an
  activity. This is how presence actually reaches Discord; IPC is only a local
  relay *to* a client that does this.
- **Rich Presence / Activity** — the structured status object: `name`, `details`,
  `state`, `timestamps`, `assets`, `buttons`, `type`, and a platform bitfield.
- **PKCE** (Proof Key for Code Exchange) — OAuth2 extension where the client
  generates a random `code_verifier`, sends `code_challenge = S256(verifier)`, and
  proves possession at token exchange. Lets a public client (a mobile app that
  cannot hold a secret) do OAuth safely.
- **External asset proxying** — rich presence images normally must be pre-uploaded
  to your Discord application. `POST /applications/{app_id}/external-assets` takes
  arbitrary image URLs and returns proxy IDs used as `mp:external/…`, so *any*
  album-art URL can be shown without pre-registration.
- **Headless session** — an OAuth2 application can update a user's activity over
  plain HTTP instead of holding a Gateway socket.

### The three mechanisms, evaluated

| # | Mechanism | Works on Android | ToS posture | Cost |
|---|---|---|---|---|
| 1 | Local IPC to desktop client | **No** — no client to talk to | Fine | n/a |
| 2 | Gateway + **scraped user account token** (Kizzy-style) | Yes | Self-botting. Gray at best; bans documented as a risk by Kizzy's own docs | Requires the user to surrender a **full account token** via an in-app WebView |
| 3 | **OAuth2 PKCE + `sdk.social_layer_presence`**, then Gateway (or headless HTTP) | Yes | Sanctioned — this is the Social SDK's own presence model | Needs a registered Discord application; token is scoped and revocable |

Echo implements **both 2 and 3**: `DiscordTokenWebView.kt` scrapes a token by
watching for navigation to `discord.com/channels`, while
`DiscordOAuthRepository.kt` runs a full PKCE flow (`code_verifier`, `SecureRandom`,
`MessageDigest`, refresh-token + expiry persistence) and
`DiscordSocialPresenceClient.kt` connects the Gateway with that **bearer** token.
`DiscordAssetRegistrar.kt` posts to
`/applications/{DISCORD_APPLICATION_ID}/external-assets` for artwork, and
`DiscordPresenceModels.kt` sets `supportedPlatforms = DiscordActivityPlatform.Android.bit`
so the presence carries a mobile platform badge.

### Decision: mechanism 3 only

We do **not** ship token scraping. A Discord account token grants complete account
control — messages, servers, payment surfaces — and storing one in Stash makes us
custodian of a credential far more dangerous than a Last.fm session key. It is
also the thing that gets users banned. Sanctioned OAuth costs us one registered
application and gives users a revocable grant.

Scope set: `openid sdk.social_layer_presence` (Discord's own
`GetDefaultPresenceScopes`).

### Presence payload (opcode 3)

```json
{
  "op": 3,
  "d": {
    "since": 0,
    "activities": [{
      "name": "Stash",
      "type": 2,
      "details": "Weird Fishes / Arpeggi",
      "state": "Radiohead — In Rainbows",
      "timestamps": { "start": 1785365937000, "end": 1785366255000 },
      "assets": {
        "large_image": "mp:external/AbC…/https/i.scdn.co/image/ab67…",
        "large_text": "In Rainbows",
        "small_image": "mp:external/…",
        "small_text": "FLAC 24/96"
      },
      "buttons": ["Listen on Stash"],
      "supported_platforms": ["android"]
    }],
    "status": "online",
    "afk": false
  }
}
```

`type: 2` is "Listening", which renders as *Listening to Stash*.

### Android-specific engineering

A persistent WebSocket is the part that bites on mobile: Doze and background
limits kill sockets, and reconnect loops drain battery.

Stash already owns the right lifecycle anchor — the **foreground playback
service**. Bind the Gateway connection to it: connect on first presence update,
disconnect after a pause/idle timeout, never hold a socket when nothing is
playing. If that still proves costly, the headless HTTP variant needs no socket at
all and becomes the fallback (one request per track change rather than a
connection).

### Mapping onto Stash

- New module `:data:discord` — `GatewayClient`, `DiscordOAuthRepository`,
  `DiscordAssetRegistrar`, `DiscordPresenceController`.
- `DISCORD_APPLICATION_ID` via `buildConfigField`, following the existing
  `LASTFM_API_KEY` pattern in `app/build.gradle.kts` (empty ⇒ feature hidden, so
  forks and unconfigured builds are unaffected).
- `DiscordPresenceController` observes existing player state; artwork URL comes
  from `TrackEntity.albumArtUrl`, already upgraded by `ArtUrlUpgrader`.
- Settings UI mirrors `feature/settings/.../components/LastFmSection.kt`, whose
  `NotConfigured / Disconnected / AwaitingAuth / Connected / Error` state machine
  is exactly the shape an OAuth connection needs.

---

## 2. ListenBrainz

### Definitions

- **ListenBrainz** — MetaBrainz's open scrobbling service; open data, unlike
  Last.fm.
- **Listen** — one playback event: `listened_at` (unix seconds) plus
  `track_metadata`.
- **`listen_type`** — `single` (one finished listen), `playing_now` (now-playing,
  no timestamp), `import` (bulk backfill, up to ~1000 per request).
- **MSID / MBID** — MessyBrainz id for an unmatched submission; MusicBrainz id
  once matched. `return_msid=true` on a `playing_now` yields a `recording_msid`
  usable for love/hate feedback.

### API surface

```
POST https://api.listenbrainz.org/1/submit-listens
Authorization: Token <user token from listenbrainz.org/profile>
Content-Type: application/json

{ "listen_type": "single",
  "payload": [{ "listened_at": 1785365937,
                "track_metadata": { "artist_name": "Radiohead",
                                    "track_name": "Weird Fishes / Arpeggi",
                                    "release_name": "In Rainbows",
                                    "additional_info": { "media_player": "Stash",
                                                         "submission_client": "Stash",
                                                         "duration_ms": 318000 } } }] }
```

`200` accepted · `400` malformed · `401` bad token. `GET /1/validate-token`
verifies a token before saving it.

Token-based auth means **no OAuth dance** — the user pastes a token, exactly like
the existing captcha-cookie paste field pattern in Audio & Quality.

### The finding that changes the design

Stash already contains **three structurally parallel scrobblers**, and the code
says so out loud:

- `core/data/.../lastfm/LastFmScrobbler.kt` — `start()`, `drainNow()`,
  `notifyNowPlaying()`, `drainQueue(session)`, `submit(session, event, track)`
- `core/data/.../sync/workers/AutoSaveScrobbler.kt` — *"Architecture mirrors
  `LastFmScrobbler`"*
- `core/data/.../youtube/YouTubeHistoryScrobbler.kt` — *"Structurally parallel to
  `LastFmScrobbler`"*

Writing a fourth copy for ListenBrainz is the obvious move and the wrong one. The
third copy is already the signal to extract the shared machinery:

```kotlin
/** One destination for finished listens. Queue, retry, and batching are the
 *  drain loop's job; an implementation only has to submit. */
interface ListenSink {
    val id: String                       // "lastfm" | "listenbrainz" | …
    suspend fun isEnabled(): Boolean
    suspend fun submit(batch: List<Listen>): SinkResult
    suspend fun nowPlaying(listen: Listen) {}   // optional
}
```

With that, ListenBrainz is roughly 80 lines and Last.fm keeps its behaviour.

**Required schema change.** `ListeningEventEntity.scrobbled` is a single boolean —
it cannot express "sent to Last.fm, not yet to ListenBrainz". Add a per-target
table so future sinks need no migration:

```sql
CREATE TABLE listen_submissions (
  event_id INTEGER NOT NULL,
  target   TEXT    NOT NULL,   -- sink id
  state    TEXT    NOT NULL,   -- PENDING | SENT | FAILED
  attempts INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (event_id, target),
  FOREIGN KEY (event_id) REFERENCES listening_events(id) ON DELETE CASCADE
);
```

Keep the legacy `scrobbled` column writing through for one release so a rollback
doesn't double-submit.

---

## 3. Last.fm — what "more options" concretely means

Measured from the code, Stash currently calls:

| Direction | Methods |
|---|---|
| Read | `artist.getInfo` · `artist.getSimilar` · `artist.getTopTags` · `artist.getTopTracks` · `track.getInfo` · `track.getSimilar` · `track.getTopTags` · `user.getLovedTracks` · `user.getTopArtists` · `user.getTopTracks` |
| Write | `track.scrobble` · `track.updateNowPlaying` |

Supporting cast already built: `LastFmSessionPreference`, `LastFmRateLimitGate`,
`LastFmReadKeySelector` (extra-key pool), `LastFmColdStartImporter`,
`LastFmCacheDao`, `LastFmPersonas`, and the `infra/lastfm-proxy` Worker.

**The standout gap: `track.love` / `track.unlove` are absent.** We already *read*
`user.getLovedTracks`, so the import direction exists while the export direction
does not — meaning Stash mirrors likes to Spotify and YouTube but silently drops
Last.fm. That is the highest-value addition and it plugs into the existing
like-mirroring infrastructure rather than needing new machinery.

Ranked additions:

1. **Love mirroring** (`track.love` / `track.unlove`) wired into the existing like
   mirror — closes a real asymmetry.
2. **Separate now-playing toggle** — some users want scrobbles without live status.
3. **Per-source scrobble filter** — e.g. don't scrobble YouTube-fallback plays,
   which are often wrong-version matches.
4. **Pending-queue visibility** — `drainNow(): DrainResult` already exists and is
   surfaced in `SettingsUiState.scrobbleDrainResult`; a queue count and last-error
   line is a small, honest win.
5. **Threshold exposure** — `ListeningRecorder.thresholdFor()` hardcodes Last.fm's
   spec (min of 4 minutes or half the track). Exposing it is possible but
   deviating from spec produces scrobbles Last.fm may reject; recommend leaving
   the default and only documenting it.

---

## 4. Google Cast

### Definitions

- **CastContext** — process-wide Cast singleton, configured by an
  `OptionsProvider` naming a receiver application id.
- **MediaRouter / MediaRouteButton** — the discovery UI (the cast icon).
- **CastPlayer** (`androidx.media3:media3-cast`) — a media3 `Player`
  implementation that proxies to a Cast session, so UI bound to `Player` can be
  switched between local and remote.
- **Receiver** — the app on the Chromecast. The Default Media Receiver plays
  common formats, including FLAC on current hardware, and needs a **URL it can
  fetch itself**.

### Three constraints that decide the design

1. **Every local audio feature is bypassed.** The receiver decodes; the phone only
   sends a URL. Stash's `LoudnessGainProcessor`, crossfade role-swap engine,
   `LazyResolvingDataSource` and `RefreshingDataSourceFactory` all live in the
   local ExoPlayer pipeline. Cast is therefore a **parallel, feature-reduced
   path**, not a drop-in `Player` swap — EQ, crossfade and loudness normalisation
   must visibly disable while casting rather than silently no-op.
2. **Downloaded files are unreachable.** `file:///…` means nothing to a device
   across the room. Casting local FLAC requires serving it over the LAN.
3. **Signed URLs expire and the receiver won't recover.** qbdlx URLs carry `etsp`,
   YouTube URLs carry `expire=`; our 403-refresh seam is local-only, so a
   mid-track expiry on the receiver is an unrecoverable stall.

### Proposed design: one local HTTP proxy solves 2 and 3 together

Run a small on-device HTTP server (bound to the LAN address, random port,
per-session token in the path) and give the receiver **only** proxy URLs:

```
Chromecast  ──GET──▶  http://192.168.1.42:PORT/<session>/<trackId>
                        │
                        ├── downloaded track → stream the local file
                        └── streaming track  → fetch upstream, and on 401/403
                                               re-resolve through StreamSourceRegistry
                                               and continue transparently
```

This gives one code path for both cases, preserves our existing re-resolution
logic on the *server* side where it still works, and keeps expiring credentials
off the wire to the receiver. Cost: a server component and careful lifecycle
(stop with the session; never bind while not casting).

New dependencies, neither currently in `gradle/libs.versions.toml` (which has
media3 1.9.2 but no cast extension): `androidx.media3:media3-cast` and
`com.google.android.gms:play-services-cast-framework`.

---

## Sequencing

Ordered so each step de-risks the next, cheapest certainty first:

1. **Extract `ListenSink` + per-target submission state.** Pure refactor of code
   that already exists in triplicate; unblocks 2 and 3.
2. **ListenBrainz.** Smallest new surface, token auth, no OAuth, no new UI
   patterns. ~80 lines on top of step 1.
3. **Last.fm love mirroring + options.** Reuses the existing like-mirror path.
4. **Discord.** New module and an OAuth flow, but every piece has a local
   precedent (BuildConfig gating, `LastFmSection` state machine, foreground
   service lifecycle).
5. **Google Cast.** Largest and riskiest: new deps, a local HTTP server, and a
   second playback path with honest feature degradation.

**Recommendation:** commit to 1-4 for the next release and treat 5 as its own
release. Cast is not a feature that degrades gracefully when rushed — a cast
button that stalls on downloaded FLAC or dies mid-track on an expired URL is worse
than no cast button, and it is the only one of the four that touches the playback
pipeline users already trust.

## Sources

- Echo Music: `app/src/main/kotlin/com/music/echo/discord/` (GatewayClient,
  DiscordOAuthRepository, DiscordSocialPresenceClient, DiscordAssetRegistrar,
  DiscordPresenceModels) and `ui/screens/settings/DiscordTokenWebView.kt`
- Discord Social SDK OAuth2 scopes — `sdk.social_layer_presence`
- Kizzy / KizzyRPC — the user-token Gateway approach and its stated risk
- ListenBrainz API docs — `submit-listens`, `validate-token`, listen types
