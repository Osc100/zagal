# Architecture

How Zagal is put together, for anyone working on it. Building is in the
[README](../README.md#building).

## Modules

```
core/      protocol client: framing, JSON-RPC, events, session model, layout
           documents, themes. Kotlin Multiplatform (JVM/Android, iOS); no UI.
terminal/  the terminal view: ghostty's VT core (its Zig module, through a JNI
           bridge in Zig) drawn by an Android View; started from
           expo-libghostty 0.8.1 (MIT).
ssh/       SSH (JSch) and the phone's key, kept sealed by the Android Keystore.
app/       the Android app: Jetpack Compose, Material 3 in Herdr's colors.
```

## App

- `AppContainer` builds the dependencies once (store, live session, layout
  actions, selection); ViewModels get them by constructor (`AppViewModels`).
- `session/`: `Store` (DataStore: hosts, prefs), `Live` (one link per host: SSH → `hc connect` →
  its own core `Session`, all connected at once and merged; ids carry their
  host, `user@host:22 beta/t1`; each link reconnects with backoff, and all
  try again when the app opens or comes back), `Actions` (new/close tabs and spaces), `Types`
  (what screens draw).
- `ssh/`: `Ssh` (JSch exec channel as a `Duplex`, host-key probing,
  ssh-copy-id) and `PhoneKey` (the phone's ed25519 key, sealed with an
  Android Keystore key; shown only after a fingerprint or screen-lock check).
- `ui/`: one screen per destination, stock Material 3 components. Only the
  attention marks and space avatars are drawn by hand. `TerminalHost` keeps
  the terminal view and its attach stream for the activity's lifetime, so
  switching tabs doesn't reattach.
- Program icons and tiles are the desktop's: `core` resolves what a terminal
  runs with herdr-app's table and rules (`ProgramTable.kt`, `Programs.kt`,
  copied from `defaults/programs.json` and `src/programs.rs`); the SVGs are
  herdr-app's `defaults/icons`, in `app/src/main/assets/icons`. Copy both
  again when the desktop's change.
- Every icon is an SVG in assets (program icons in `icons/`, Lucide glyphs in
  `lucide/`), parsed in the background at start and drawn by AndroidSVG
  straight onto the canvas (`ui/Svg.kt`). A new glyph: add its Lucide SVG
  (stroke-width 1.75) and a line in `Lucide`.
- SSH trust: a new host's key is fetched without logging in and shown as a
  fingerprint; nothing logs in until you trust it (with a password to install
  the phone's key, or "key already installed"). Pinned keys must match after.
- Android 17+ needs the "nearby devices" (local network) permission to reach
  LAN hosts (`.local` names, private addresses). Until it's allowed, Settings
  opens with a banner to allow it, and a LAN host waits for it (no retries). The app is `io.github.osc100.zagal`;
  debug builds install beside it as `io.github.osc100.zagal.dev` ("Zagal dev").
- Navigation Compose with type-safe routes; deep links `zagal://agents`,
  `zagal://spaces`, `zagal://settings`, `zagal://terminal?address=<machine/t1>`
  (`zagal-dev://` in debug builds).
  At 840dp and wider (tablets), one screen with a sidebar instead (`WideApp.kt`).
- Themes: a Herdr preset (graphite, ink, moss, paper), graphite/paper
  following the system, or Material You (wallpaper colors, Android 12+). The
  terminal always uses a Herdr palette.

## Tests

`mise run test` runs the protocol tests (recorded fixtures), alerts and
session merge. `HERDR_IT=1 ./gradlew :core:jvmTest --tests '*IntegrationTest*' --rerun`
runs the protocol tests against a local `hc connect`.

The launcher icon is Zagal's own (`art/zagal-icon.svg`, drawn as an adaptive
icon in `res/drawable/ic_launcher_*.xml`).
