# Settings Redesign — Premium Hub-and-Spoke (design spec)

**Date:** 2026-06-13
**Status:** Approved (brainstorm complete) — ready for implementation planning
**Feature module:** `feature/settings`
**Mockups:** `.superpowers/brainstorm/877-1781322634/` (`hub-refined.html`, `category-map.html`, `subscreens.html`, `appearance.html`, `flow.html`)

## Problem

Settings is a single ~1,970-line composable (`feature/settings/.../SettingsScreen.kt`) that renders **12 stacked sections** in one long scroll: Support, Playback, Accounts, Audio Quality, Lossless, Downloads, Stash Mixes, Appearance, Audio Effects, Storage, Diagnostics, About. To reach anything near the bottom you scroll past everything. Several sections (Lossless especially) are deep mini-screens crammed inline — a toggle, routing health, an account connect, a quality radio group, and two expanders all in one card. The result reads as a cluttered wall of cards and does not match the premium feel of the rest of Stash.

## Goals

- **Findable:** turn the 12-section scroll into a short, scannable hub of categories you tap into.
- **Premium:** restyle every surface in Stash's real design language (near-black, frosted glass, Space Grotesk + Inter, purple accent used sparingly) — no generic dark-mode, no emoji.
- **Maintainable:** decompose the 1,970-line monolith into a hub screen + six focused category screens + a small reusable component kit.

## Non-goals

- **No behavior changes.** Every existing control keeps its current function and wiring. This is a re-organize + re-skin, not a logic change.
- No new settings beyond what exists today (the just-shipped "Sync your likes" mirror toggles are included; they live under Accounts & Sync).
- The theme picker is a visual upgrade only (same three options: Light / Dark / Follow system).

## Approved decisions

1. **Navigation model: hub-and-spoke (direction A1).** Settings home becomes a short list of six category rows; tapping a row opens that category's own screen. Confirmed over a grouped single-scroll (B) and over pure-type rows (A2).
2. **Support banner pinned at the top of the hub** — above the search field and category list (not a category). Keeps "Support Stash · Donate · Star" prominent.
3. **Settings search field** directly under the support banner.
4. **Category rows carry thin monochrome line-marks** (A1) plus a **current-state subtitle** (e.g. "Online · Cellular off", "Lossless · Max 24/192", "20.8 GB · 1,171 tracks").
5. **Six categories**, with the contents mapping in the IA section below. Every one of today's 12 sections has a home; nothing is dropped.
6. **Appearance stays its own category** even though it holds only Theme today (room to grow — accent colour, Now-Playing background, etc.).
7. **Audio Effects dissolves:** Equalizer → Audio & Quality; Library Health → Library & Storage.
8. **One shared premium component language** (below) is used by the hub and all six interiors.

## Information architecture

Hub (top → bottom): **Support banner** · **Search** · six category rows.

| Category | Current-state subtitle (example) | Contents (from today's sections) |
|---|---|---|
| **Playback** | Online · Cellular off | Online/Offline mode (segmented); Stream on cellular; Stream via YouTube (fallback); Force antra only (test) |
| **Audio & Quality** | Lossless · Max 24/192 · EQ on | Download quality tier; Lossless downloads toggle; Lossless quality (Max/Hi-Res/CD); Sources & routing health (kennyy/squid) + antra account; YouTube fallback; Advanced; **Equalizer ›** |
| **Accounts & Sync** | Spotify · YouTube · Last.fm | Spotify (connect + Auto-save liked); YouTube Music (connect + Send plays); Last.fm (connect); **Sync your likes** (Spotify / YT Music mirror toggles) |
| **Library & Storage** | 20.8 GB · 1,171 tracks | Stash Mixes (Beta); Downloads — network rules; Storage used + download location; Export/Import backup; Move library; **Library Health ›** |
| **Appearance** | Follow system | Theme — Light / Dark / Follow system |
| **About & Help** | v0.9.51 | Share diagnostics; Share latest crash report; Version · License; Check for updates |

Pinned on the hub (not a category): **Support Stash** — Donate · Star.

Sub-screens that themselves drill further (kept as nested routes, not inlined): **Equalizer**, **Library Health**, **antra account**, **Advanced** (lossless). These already exist as their own destinations or expanders today.

## Visual / component language

Design tokens come straight from `core/ui/.../theme/Color.kt` + `Type.kt` (dark theme):

- **Background** `#06060C`; **glass card** = `rgba(255,255,255,0.04)` fill, `1dp` `rgba(255,255,255,0.06)` border, `shapes.large` radius (existing `GlassCard`); **divider** ≈ `rgba(255,255,255,0.045)` hairline.
- **Text** primary `#E8E8F0`, secondary `#A0A0B8`, tertiary `#606078`.
- **Accent** purple `#8B5CF6` (switches, selected radio, primary button), light `#A78BFA` (group labels).
- **Type:** Space Grotesk (SemiBold/Bold) for the screen title, group labels, and account/service names; Inter (Regular/Medium) for row titles, subtitles, values.
- **Service identity colours** stay: Spotify green `#1DB954`, YouTube red `#FF0033`; routing health dots use cyan `#22D3EE`/green.

Reusable components (new, in `feature/settings/components/`):

- **`SettingsSectionLabel`** — purple Space Grotesk, uppercase, `letterSpacing ≈ .09em`; optional trailing Beta pill.
- **`SettingsGroupCard`** — `GlassCard` wrapper that lays children as rows separated by hairline dividers.
- **`SettingsToggleRow`** — title + optional subtitle + purple `Switch`.
- **`SettingsNavRow`** — optional leading line-mark + title + subtitle/value + chevron (used by hub rows and drill-ins).
- **`SettingsSegmented`** — pill segmented control, active segment gradient-filled (Online/Offline, theme Dark/Light/Auto).
- **`SettingsPickerRow`** — selectable row with purple radio dot + title + metadata subtitle (lossless quality tiers, download quality).
- **`SettingsStatusRow`** — leading glow dot (cyan/green) + name + status text (routing health).
- **`SettingsAccountCard`** — service dot badge, name, "Connected as …", ghost Disconnect, optional nested `extraContent` (auto-save / send-plays toggles, Beta pill).
- **`SupportBanner`** — purple→cyan gradient-edged card, title + one-line pitch + Donate (filled) / Star (ghost).
- **`SettingsSearchField`** — glass pill with search glyph.
- **`BetaPill`** — already extracted (from the like-mirroring work).

## Per-screen layout

- **Hub** (`SettingsHubScreen`): title "Settings" → `SupportBanner` → `SettingsSearchField` → one `SettingsGroupCard` of six `SettingsNavRow`s with line-marks + live current-state subtitles. Each row navigates to its category route.
- **Playback:** "Mode" label → Online/Offline `SettingsSegmented`; "Streaming" label → group of toggle rows (cellular, YT fallback, force-antra test).
- **Audio & Quality:** "Lossless" label → toggle + quality `SettingsPickerRow`s with size/min metadata; "Sources" label → `SettingsStatusRow`s (kennyy/squid) + antra account nav + Advanced nav; "Effects" label → Equalizer nav row. (Download-quality tier sits at the top under its own label.)
- **Accounts & Sync:** "Connections" → `SettingsAccountCard`s for Spotify / YouTube Music (nested auto-save / send-plays) + Last.fm; "Sync your likes" label + Beta → mirror toggles.
- **Library & Storage:** Stash Mixes toggle; "Downloads" network picker; "Storage" meter + location + backup buttons + move library; Library Health nav row.
- **Appearance:** theme picker — **visual preview cards (T1)**: three thumbnails (Dark / Light / Follow system) each rendering a faithful mini-preview in that palette (Light uses the real `#F6F3FF` lavender-white / aubergine light tokens), selected card gets a purple ring + check. T2 (single live preview + segmented) is the recorded alternative.
- **About & Help:** value rows (Version, License) + action rows (Share diagnostics, Share crash report, Check for updates).

## Architecture notes (for the implementation plan)

- **Navigation:** add a nested settings nav graph (or sibling routes) — `settings/hub` + one route per category — wired through `StashNavHost`. The hub navigates; the bottom-nav "Settings" tab lands on the hub.
- **Decomposition:** split `SettingsScreen.kt` into `SettingsHubScreen` + six `Settings<Category>Screen` files + the component kit. This is the maintainability win (1,970-line file → focused files).
- **State:** the existing `SettingsViewModel` (30-flow `combine`) can be reused; each screen collects the same `uiState` and reads its slice, OR the combine is split per-screen. The plan picks one — reuse-whole is lower-risk for a first pass. The hub's current-state subtitles derive from `uiState` (e.g. lossless tier, storage bytes, theme, connection states).
- **Search:** the search field is part of the approved hub. Real cross-settings search (index → jump to the owning screen/row) is non-trivial; the plan may **phase** it — ship the hub + interiors first, land search as a follow-up — or stub the field initially. Flag for planning.
- **Preserved behavior:** every control keeps its existing callback into `SettingsViewModel`. "Sync your likes" (just built) moves verbatim into Accounts & Sync.

## Out of scope

- Any change to what a setting *does*.
- New settings/features beyond today's set.
- Light-theme polish beyond an accurate theme-picker preview.

## Open items to confirm at spec review

1. **Theme picker:** T1 (preview cards) is the chosen direction; confirm vs T2 (live preview + segmented).
2. **Settings search:** in the first implementation pass, or phased as a follow-up?
