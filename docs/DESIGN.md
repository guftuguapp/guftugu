# Guftugu — design language

**Feel:** a hand-crafted riverbank: parchment pages, a clear-sky header, river-blue
actions, meadow greens, and gold hairlines everywhere something is precious.
Elaborate in detail, restrained in motion. **It must stay fast on a 2020 budget
phone** (Huawei Y6p, Helio P22) — every decoration below is a single draw pass.

The kit lives in `ui/theme/` (`Color.kt`, `Type.kt`, `Theme.kt`, `Craft.kt`).
Use it; don't invent parallel styles.

## Tokens

| Token | Light | Dark | Use |
|---|---|---|---|
| background | Parchment `#FBF7EE` (top fades from `#EAF5FD` sky) | NightSky `#0B1B33` | `RiverbankBackground` |
| surface / cards | ParchmentBright `#FFFDF8` + gold hairline | NightSurface `#10233F` | `ParchmentCard` |
| primary | River `#2F86D2` | RiverLight `#8FD2F4` | buttons, links, FAB |
| secondary | Grass `#4F9E33` | Meadow `#9AD56A` | online dots, success |
| tertiary | GoldDeep `#A8832A` | Gold `#E8C65C` | badges, accents |
| bubble mine / theirs | `#D9EEFB` / `#FFFDF8` | `#1E4F80` / `#1C324F` | `GuftuguTheme.craft.*` |
| gold gradient | `Brushes.gold` (light→deep→shadow) | same | borders, rings, CTA |
| sky bar | `Brushes.skyBar` | same | `SkyTopBar` |

Typography: **Cormorant Garamond** (serif, variable) for display/headline/title-large;
**Manrope** for everything else. Both bundled in `res/font` (no Google services needed).

Shapes: cards 22dp, inputs 16dp, bubbles 18dp with a 4dp "tail" corner (`MineBubbleShape`/`TheirsBubbleShape`), pills 50%.

## Building blocks (`Craft.kt`)

`RiverbankBackground` · `SkyTopBar(title, subtitle, navigationIcon, actions)` ·
`RiverbankHeader(title, actions)` (panorama header of the chat list) · `GoldBand()` ·
`ChatWallpaper(bottomInset)` (parchment + faint gold line-art landscape) ·
`ParchmentCard` · `Modifier.goldBorder()` · `SealAvatar(name)` (wax-seal monogram) ·
`GoldRingAvatar(name)` · `Ornament()` · `CraftHeading(title, caption)` · `GoldButton(text)` ·
`GoldFab` (quill) · `GoldBadge(count)` · `LogoMedallion(size)` · `CraftListRow`.

**Borders:** `Brushes.goldSoft` is the border/hairline brush and every stop in it
(`GoldEdge`, `GoldEdgeLight`, `GoldDeep`) is dark enough to read on parchment —
never use the pale `GoldLight` as the only colour of a thin line (it disappears on
one side of the shape). `Brushes.goldSheen`/`Brushes.gold` are for fills.

## Art (design/)

All illustrations come from `design/make_logo.py` and `design/make_art.py`
(deterministic generators — edit and re-run, then re-export):

| Asset | Source | Used by |
|---|---|---|
| `drawable-nodpi/logo_medallion.webp` | `logo.svg` — square gold frame with jewelled corner rosettes around a painted riverbank: clear sky, broad spreading trees with open meadow, a stream with a paper boat and ducks, a grassy landing with stepping stones | Join/Enroll/Unlock, empty states |
| `mipmap-*/ic_launcher_{foreground,background}.webp` | `icon_foreground.svg` (logo at 60 % so the square frame survives rounded-square masks) + `icon_background.svg` (engraved gold plate) | launcher |
| `drawable-nodpi/art_panorama.webp` | the same scene on a wide canvas | chat-list header |
| `drawable-nodpi/art_chat_footer.webp` | gold line-art landscape | chat and chat-list backgrounds |
| `drawable/ic_paper_boat.xml`, `ic_quill.xml` | hand-drawn vectors | send button, new-chat button |

The paper boat is the brand motif: a message on its way down the stream.

## Screens — art direction

- **Join a server** — full `RiverbankBackground`; `LogoMedallion(180dp)` centred with a
  soft gold glow ring; `CraftHeading("Guftugu", "A private place for your family")`;
  a `ParchmentCard` with three actions: *Scan invite* (primary, GoldButton), *Paste link*,
  *Enter manually* (server URL + code fields). Errors as a warm inline banner, never a dialog.
- **Enroll** — same background; card with display name, password + confirm (strength hint
  as a thin gold/green bar), device name (prefilled `Brand Model`), a switch
  "Unlock with fingerprint" (only if hardware present), and a one-line explanation
  "Your keys are created on this phone and never leave it". GoldButton *Join*.
- **Unlock** — the medallion large (220dp) over a sky→parchment gradient, the family/server
  name in display serif beneath, a gold-ringed fingerprint glyph that pulses gently
  (one `infiniteTransition` scale 1.0→1.06, 1.6 s) while the BiometricPrompt shows;
  *Use password instead* text button; on failure a short shake (`animateFloat` ×3, 300 ms).
- **Chats list** — `SkyTopBar("Guftugu")` with a search action and overflow (New group,
  Settings). Rows: `GoldRingAvatar` (group rows use a two-tone ring), name in
  titleMedium, preview in bodyMedium (italic-free, ✓/✓✓ for mine), time on the right,
  `GoldBadge` for unread. A thin `Ornament` as the section header "Conversations".
  FAB: river-blue with a gold hairline, pencil icon. Empty state: medallion + caption.
- **Chat** — `SkyTopBar(name, subtitle = "online · typing…" | member count)` with call
  buttons (audio, video) for direct chats. Background: `ChatWallpaper` — parchment with
  the faint gold line-art landscape resting just above the composer (one pre-rendered
  bitmap drawn once; no tiled or runtime-generated patterns). Bubbles: mine
  right, theirs left with sender name (groups) in Grass; text bodyLarge; time + ticks in
  labelSmall inside the bubble bottom-right; reply quote as a gold-left-bordered inset;
  system messages as centred pills; call logs as a compact row with a phone icon; date
  separators as parchment chips with a gold hairline. Media bubbles: image/video fill
  240×(aspect) with 14dp corners; voice note = play button + waveform-bars + duration.
  Locked (key pending) bubbles: a small padlock and "Waiting for keys…" in
  onSurfaceVariant. Long-press: reply, copy, delete (own) — a Material bottom sheet.
- **Composer** — parchment pill with gold hairline on focus, attach (+) opens a small grid
  sheet (Photo, Video, Camera, File); mic button on the right turns into send when text
  is present (crossfade 120 ms); hold-to-record shows a red dot + timer + "slide to cancel".
- **Media viewer** — black background, pinch-zoom image / ExoPlayer video, share + save.
- **Incoming call** — full-screen night gradient, `GoldRingAvatar(120dp)` pulsing
  (scale ring), caller name display serif, "Guftugu audio/video call", green answer and
  red decline round buttons with labels. **Respect the ringer: honour silent/vibrate
  mode (AudioManager.ringerMode) — no sound when the phone is silent.**
- **In-call** — remote video full-bleed (or night gradient + avatar for audio),
  local preview as a 110dp rounded card with gold hairline bottom-right, timer at the top,
  bottom controls: mute, camera, flip, speaker, hang up (red). Controls fade after 4 s of
  no touch during video calls.
- **Settings** — grouped `ParchmentCard`s: Profile (avatar with edit), Security (change
  password, lock after N minutes), Connection (stay connected toggle + battery
  optimisation hint), Devices, Link another phone (QR), About (server name, version).

Motion: navigation = fade-through 200 ms; new incoming message = slide-in 4dp + fade
120 ms (only for items added while on screen); everything else instant. No shared
element transitions, no blur.

## Performance rules (non-negotiable)

1. Lists: `LazyColumn` with stable `key = { it.msgId }` and `contentType`; message
   bubbles take an `@Immutable` model; never pass a whole list into an item.
2. No `Modifier.blur`, no `RenderEffect`, no `graphicsLayer { alpha }` on list items,
   no shadows above 2dp, no nested `Surface` stacks. Draw decorations with
   `drawBehind`/`border`, and reuse `Brushes.*` (never allocate a Brush per frame).
3. Images: Coil with `size()` hints and `crossfade(120)`; thumbnails ≤ 512px; full images
   loaded only in the viewer; videos never decoded in the list (thumbnail PNG only).
4. Text: `remember` formatted dates/times; keep `String.format` and regex out of composition.
5. State: hoist input text into the ViewModel; `collectAsStateWithLifecycle`;
   `derivedStateOf` for computed flags (send-enabled, scroll-to-bottom visible).
6. Startup: MainActivity does one DataStore read + Room query before the first frame;
   WebRTC, camera, and ExoPlayer are created lazily on first use.
7. Fonts are bundled; never use downloadable fonts (Google services are not guaranteed).
8. Release builds: R8 `isMinifyEnabled = true` + `isShrinkResources = true`, arm64-v8a +
   armeabi-v7a only (WebRTC natives dominate APK size).
