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

The app icon, the medallion and the chat-list banner are realistic renders made in Blender from the scene
scripts in `design/blender/scene/` (see *Rendering the riverbank* below); `design/rendered/{logo,banner}.png` hold the
approved renders, and `export.sh` uses them in place of the vector scene. Everything else comes from
`design/make_logo.py` and `design/make_art.py` (deterministic generators — edit and re-run, then re-export):

| Asset | Source | Used by |
|---|---|---|
| `drawable-nodpi/logo_medallion.webp` | `rendered/logo.png` — the rendered riverbank (lush meadow, full round-crowned trees, a river curving away with a paper boat, a low golden sun in a cloudy blue sky) in a rendered gilded frame: gold moulding, a row of pearls, sapphire rosettes at the corners and diamond studs mid-edge | Join/Enroll/Unlock, empty states |
| `mipmap-*/ic_launcher_{foreground,background}.webp` | the framed render at 660/1080 (the square frame survives rounded-square masks) + `icon_background.svg` (engraved gold plate) | launcher |
| `drawable-nodpi/art_panorama.webp` | `rendered/banner.png` — the same riverbank from a wider view, with more trees | chat-list header |
| `drawable-nodpi/art_chat_footer.webp` | gold line-art landscape | page backgrounds (`RiverbankBackground`) |
| `drawable-nodpi/art_chat_leaves.webp` | full-screen gold floral line drawing (`chat_leaves`): composed sprays of petalled flowers (rose, peony, dahlia, anemone, cosmos, sakura, lily, lotus, tulip) and ornate leaves; every leaf and flower drawn from its own random parameters, so nothing repeats; stored as flat gold + alpha, decoded in-app as an alpha mask and tinted | chat room and chat list (`ChatWallpaper(leaves = true)`) |
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

## Owner's art notes (October 2026)

- Trees: taller than wide (not the earlier broad, flat crowns); a wider river; soft puffy clouds; the chat-list
  banner carries more trees than the icon (`scene(..., banner=True)`).
- Chat room: filled with gold line-art only (no colour fills), mostly ornate leaves plus beautiful petalled
  flowers, every shape unique. Rejected on the way: outline trees ("cactuses"), a coloured leaf print, a
  scatter of small motifs ("a microscope of dust insects"), paisleys and round leaves ("amoebas").
- Chat header: the contact's name left-aligned in the chat's body font, "Online" / "Last seen …" under it.
- `python3 make_art.py` + `bash export.sh` regenerate everything; `chat_leaves(sheet='leaves')` and
  `chat_leaves(sheet=True)` draw review sheets of leaves and flowers.
- Icon and banner: the vector versions were "beautiful but not professional" ("looks like its drawn in MS Paint"),
  so they are now realistic Blender renders. What the owner asked for on the way: full, fresh-green trees with
  neat round crowns (scanned trees looked like reeds, then like "Kramer from Seinfeld's hair"); lush green grass,
  wild and unkempt, never savannah-pale; a visible sun with real sunlight around it, low and golden, and every
  shadow cast away from it; trees framing the sides so the river's flow and curve stay visible in the middle;
  no dark tree reflections in the water; no bushes; distant hills covered in the same grass as the meadow.
  Kept from the start: the sky and clouds, the water reflections and the paper boat.
- The "Guftugu" title on the banner sits at the lower right, its middle 70% of the way down (the owner's choice;
  the sky and the sun stay clear), with a dark outline under the gold letters so every letter reads on any art.
  The top scrim is light: a dark one made the rendered sky look gloomy.

## Rendering the riverbank

`design/blender/scene/` holds the scene (Blender 5.2, Cycles on the CPU; assets are CC0 from Poly Haven, fetched by
`design/blender/fetch_assets.py`; the steps are in `design/blender/README.md`):

- `riverbank.py -- banner|icon OUT.png preview|final` builds the landscape: a meandering river that arches away
  into a grassy valley, terrain with attributes that drive grass, flower, reed and forest scattering (Geometry
  Nodes), groves placed on the sides, a paper boat, a cumulus sky photograph for the sky and reflections, and a low
  sun lamp that has a visible disc in the same direction. It writes `OUT.png.sun`, the sun's spot in the frame.
- `trees.py` builds the trees: a trunk that forks into limbs, each carrying a leafy mass made of the asset's real
  leaf clusters scattered over a shaped crown, with a dark core so no sky shows through.
- `glow.py IN OUT` adds the warm halo around the sun and a soft bloom.
- `frame.py OUT.png final` renders the gilded frame alone (the opening is a shadow catcher), and
  `frame_compose.py FRAME PICTURE OUT` lays the icon picture under it, so the picture is never tone-mapped twice.
- Preview renders take about 3–4 minutes each; finals render at the app's sizes (1440×560 and 1080×1080).
