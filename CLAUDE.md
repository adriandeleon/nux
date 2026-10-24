# Nux

A simple, powerful, multiplatform **NUT (Network UPS Tools) client** in JavaFX
(*nux* = Latin "nut"). Pure network client speaking the NUT protocol (TCP 3493)
directly to `upsd` — no drivers, no native code, no external tools required at
runtime. JDK 25 + JavaFX 26 + Maven, modular (JPMS, module `com.nux`).

## Commands

- Run the app: `mvn javafx:run`
- Run tests: `mvn test` (pure-logic only; no FX toolkit needed)
- Native package: `mvn -Pdist package` → portable app image in `target/dist/Nux/`
  (launcher `bin/Nux`); installers via `-Djpackage.type=DEB|RPM|DMG|PKG|MSI|EXE`.
  All dependencies are real JPMS modules, so jlink links directly — **no moditect,
  no jar surgery** (keep it that way when adding dependencies).
- Regenerate the icon: `java scripts/gen_icon.java` (headless Java2D; writes
  `branding/nux-*.png` + the window icons under `src/main/resources/com/nux/icons/`,
  plus `nux.ico`/`nux.icns` for the Windows/macOS installers).
  The vector source of truth is `branding/nux.svg` — keep both in sync.
- Dev desktop entry: `scripts/install-dev-desktop.sh` (`--uninstall` to remove).

## App icon on Linux — the StartupWMClass rule

`Stage.getIcons()` only sets `_NET_WM_ICON`, which **Wayland ignores and GNOME
deprioritises**. Desktops identify a window by matching its `WM_CLASS` against a
`.desktop` file's `StartupWMClass`; with no match you get a generic icon no matter
what the app sets at runtime. Nux's real `WM_CLASS` is **`com.nux.App`** (JavaFX
derives it from the module main class, *not* the app name — verified with
`xwininfo -root -tree`). So:

- **Packaged builds** ship `packaging/linux/Nux.desktop`, staged into the jpackage
  `resourceDir` by the dist profile's antrun step. jpackage substitutes its
  `APPLICATION_*` placeholders; our overrides add `StartupWMClass` and real
  `Categories` (it would otherwise emit `Unknown`).
- **The icon must also be staged into that resource dir as `Nux.png`** — the DEB
  bundler regenerates `lib/<name>.png` from its own resources and would otherwise
  clobber `--icon`. Verify a built `.deb` with
  `dpkg-deb --fsys-tarfile … | tar -xO ./opt/nux/lib/Nux.png | sha256sum` against
  `branding/nux.png`; they must match.
- **Source runs** (`mvn javafx:run`) have no installed entry at all, so run
  `scripts/install-dev-desktop.sh` once; the app must be restarted afterwards for
  the desktop to re-match the window.

## Architecture

- `com.nux.protocol` — **pure, unit-tested core** (no JavaFX, no I/O except
  `NutClient`): `NutParser` (quote/escape-aware tokenizer + line parsers),
  `UpsStatus` (flag decoding → label + OK/WARNING/CRITICAL severity; unknown
  status is never OK), `StatusTransitions` (poll-to-poll alert detection,
  severity-ordered), `UpsSnapshot` (one `LIST VAR` poll with `Optional`-typed
  numeric accessors — UPS values are strings and cheap units report junk, so
  nothing trusts a parse — `number` accepts only finite decimals), `NutClient`
  (blocking socket; **nothing a server sends is unbounded**: each read by the
  socket timeout, each request by a deadline of 3× it, lines at 8 KiB, lists at
  20k lines — discovery aims this code at every host on the subnet; credentials
  are quoted when they need it; STARTTLS upgrade reads the pre-TLS reply
  **unbuffered** so no handshake bytes are stolen), `TlsMode`, `CommandRisk`
  (classifies an INSTCMD by segment — cuts power / drains battery / unprotected —
  so the confirmation can warn and default to Cancel), and
  `NutException.isAuthFailure` (the only codes where re-asking for credentials
  helps).
- `com.nux.config` — pure `Config`/`Profile` records with withers +
  `ConfigStore` (properties file at `~/.nux/config.properties`, atomic
  temp+move writes, defaults on missing/malformed — never throws on load).
  The analytics/automation settings live in a nested `Advanced` record
  (persisted under an `adv.` prefix) so `Config`'s component list stays
  readable; add new settings there, not as more top-level components.
- `com.nux.store` — persistence beyond preferences: `HistoryStore`
  (`~/.nux/history/<ups>__<host>_<port>.csv` — keyed by server too, because
  `ups` is the commonest UPS name there is; a pre-0.6 `<ups>.csv` is adopted by
  the first server that writes it. Append-only, downsampled per file by
  `shouldPersist` — a status change always writes so an outage is never
  smoothed away. Pruning runs **on the writer thread** (a rewrite racing an
  append loses the row), over every file, daily via `schedulePruning`, and
  follows `setRetentionDays` live; server-supplied names go through `safeName`
  → `[a-z0-9_-]`, dots included) and `EventLog` (`~/.nux/events.csv`, capped,
  trimmed on each session's first append so short sessions can't grow it
  forever; newest first for the UI). Both write on their own daemon thread;
  `load` is synchronous and belongs off the FX thread.
- `com.nux.analysis` — pure derivations over a snapshot/history:
  `BatteryHealth` (firmware date formats, age, aging/replace thresholds, and
  the UPS's own RB flag always winning), `EnergyEstimate` (measured watts,
  else load % × nominal, VA converted by power factor; kWh integration that
  **skips gaps over `MAX_GAP_SECONDS`** because a long gap means the app was
  closed, not that power was drawn), `TestSchedule` (next-run/due, with
  "now" injected so the policy is testable; the first run hangs off the
  persisted `selfTestArmed` instant — recomputing it from "now" each poll meant
  it never came due), and `RuntimeHealth` (runtime colour judged against the
  UPS's own `battery.runtime.low`, not the gauge's 60-minute arc).
- `com.nux.service` — `PollService` (ONE daemon thread owns the `NutClient`;
  polls every 5 s, posts to the FX thread via `Platform.runLater` under a
  monotonically-increasing **generation guard** so stale results are dropped;
  reconnects each tick, the poll interval doubling as backoff; `runInstCmd`
  runs on the same thread/connection so auth state stays accurate — upsd allows
  one USERNAME per connection, and a half-failed auth drops the connection so
  the next tick reconnects clean, while a refusal like CMD-NOT-SUPPORTED leaves
  the connection alone; the polled UPS is resolved once per connection —
  requested if listed, else the first — and announced via `onConnected`, so the
  UI never shows one UPS while the poller asks for a missing one; tick catches
  RuntimeException too, because one escaping a fixed-delay task cancels it
  silently; `start`/`stop` cancel the previous poll task), `ContactTracker`
  (pure: "lost contact" only after 3 failed polls of a server we'd reached,
  "restored" with the outage length), `FailureText` (connection failures as
  sentences that say where to look), `DiscoveryService` (a cancel flag per
  scan, so a rescan can't revive the last one), `History` (NaN-tolerant
  sparkline ring buffer), `Notifier` (best-effort OS notifications off-thread:
  notify-send → gdbus fallback on Linux, osascript on macOS; failures
  swallowed).
- `com.nux.tray` — the tray icon (see `docs/tray-icon-research.md`). `Trays`
  picks a backend at runtime: Linux → `SniTray` (a pure-Java StatusNotifierItem
  exported on the D-Bus session bus via dbus-java; fails fast when no
  `org.kde.StatusNotifierWatcher` exists, re-registers on shell restart via a
  NameOwnerChanged handler) with AWT fallback; Windows/macOS → `AwtTray`
  (EDT-marshaled, multi-resolution images). Both render through
  `TrayIconRenderer` from the pure `TrayDisplay` (charge % text or bolt,
  severity color; value-equality lets `App.pushTray` skip redundant repaints).
  The tray menu is a full `com.canonical.dbusmenu` implementation (`SniMenu` +
  the pure `SniMenuModel`) on Linux and an AWT `PopupMenu` elsewhere; its first
  item is a disabled live status line — deliberate, because GNOME's
  AppIndicator extension renders SNI menus but **not** SNI tooltips.
  **Gotchas (all found against the live bus):** (1) `Properties.Get` must
  return the stored `Variant` itself, never `getValue()` — a raw
  `List<Struct>` can't be re-wrapped (erased generics), which broke
  `Get("IconPixmap")` while `GetAll` worked. (2) A dbus-java `Tuple` subclass
  must be **generic** (`MenuPair<A,B>`) — the D-Bus signature is derived from
  the exporting method's generic return type, and a non-generic tuple with
  concrete `List` fields makes the whole object non-exportable ("Exporting
  non-exportable type: interface java.util.List"), which silently dropped the
  SNI backend to the unreadable AWT/XEmbed fallback. Backend creation runs off
  the FX thread (`nux-tray-init`, generation-guarded, always posts an outcome).
  Config keys `tray` + `closeToTray` (both default true): with a tray, window
  close hides — `App.updateImplicitExit()` is the single place that policy is
  computed, re-run after every settings change and tray outcome;
  `stop()` ends with `System.exit(0)` so leftover AWT/D-Bus non-daemon threads
  can't keep a quit JVM alive.
- `com.nux.command` — `Command` (id/title/action) + `CommandRegistry`
  (insertion-ordered; dynamic families — `profile.connect.*`, `ups.cmd.*` —
  swap wholesale via `removeByPrefix`; pure ranked `filter` backs the palette).
  Every user-facing action should be registered here so the palette sees it.
- `com.nux.instance` — `SingleInstance.claim` runs in `main()`, **before** the
  toolkit: signal a running copy (it raises its window) or take a
  `FileChannel.tryLock` on `~/.nux/instance.lock` for the process lifetime —
  claiming only after `stage.show()` let two quick launches both win. The port
  file (temp+move, 0600) carries per-instance request/response tokens, so
  another local user can't raise the window and a stale port isn't trusted.
- `com.nux.autostart` — pure `AutostartSpec` (desktop entry, LaunchAgent plist,
  Windows `.reg` file — `reg import`, because `reg add /d "\"…\" --minimized"`
  was mangled by Windows argv quoting) + `Autostart` (OS calls; never on the FX
  thread). macOS doesn't `launchctl load`: that would start a second Nux now.
- `com.nux.ui` + `com.nux.App` — the JavaFX layer: `Dashboard`, `ArcGauge` +
  `Sparkline` (Canvas; both expose their reading as accessible text),
  `CredentialsDialog`, `SettingsWindow` (general/connection/tray/power/
  automation; Save hands `App.applySettings` an **edit** — a
  `UnaryOperator<Config>` applied to the live config — never a Config rebuilt
  from the copy taken at open, which rolled back state written meanwhile),
  `CommandConfirmation` (risky commands: warning, named button, Cancel
  default), `Windows` (`raise`/`track`: a secondary window asked for twice comes
  to the front instead of stacking a copy), `CommandPalette`
  (an in-scene overlay on the root StackPane — never a Popup, so keyboard
  focus can't strand in a separate native window; Ctrl/Cmd+Shift+P).
  AtlantaFX (PrimerDark) is the user-agent stylesheet; app styles in
  `resources/com/nux/nux.css` use AtlantaFX `-color-*` semantic variables
  where possible. The tray metric (`TrayMetric` battery/load/runtime, config
  key `trayMetric`) recomputes from `App.lastSnapshot` on switch.

## Conventions

- **Pure core first**: protocol/config/decision logic lives in toolkit-free
  classes with unit tests (canned `upsd` transcript strings — no UPS, no
  network, no FX in tests). UI classes stay thin.
- **Three test tiers.** (1) Pure unit tests over the toolkit-free cores.
  (2) `FakeNutServer` (test-scope, `com.nux.protocol`) — an in-process upsd
  speaking canned NUT over a loopback socket, so `NutClient` and `PollService`
  are asserted in both directions: the exact bytes sent and how replies are
  read. Unscripted requests get `ERR UNKNOWN-COMMAND`, so a typo fails loudly.
  Anything the client sends *without* reading a reply (`LOGOUT` on close) must
  be asserted with `awaitReceived`, since the server records on its own thread.
  (3) **Headless FX** — `FxTestSupport` boots JavaFX 26's built-in Headless
  Glass platform via `Platform.startup`; **no TestFX and no Monocle are
  needed**, and the tests never drive a robot, so that platform's prototype
  input/render limits don't apply. The surefire `glass.platform=Headless`
  property is **required for CI**: a dev box passes without it purely because
  it has a display, so always verify FX work with
  `DISPLAY= WAYLAND_DISPLAY= ./mvnw test`. FX tests are `@Tag("fx")`;
  `./mvnw test -DexcludedGroups=fx` runs the pure suite alone.
- **Never block the FX thread**: sockets and subprocesses run on named daemon
  threads (`nux-poll`, `nux-notify`); results marshal back via
  `Platform.runLater` with the generation guard.
- Credentials are session-only, never persisted. The TLS trust-all mode is a
  config-file-only escape hatch (`tlsTrustAll`) — deliberately no UI for it.
  This is why scheduled self-tests only run once the user has authenticated
  this session; don't "fix" that by storing a password. The schedule stamps
  `selfTestLastRun` **before** issuing the command, so a failing test can't
  retry on every 5-second poll.
- Surefire runs tests on the **classpath** (`useModulePath=false`).
- **Never hardcode the version.** `AppInfo.VERSION` reads a Maven-filtered
  `build-info.properties`, so `pom.xml` is the only place a release number lives
  (`AppInfo` falls back to `0.0.0` on an unfiltered run and never shows a raw
  `${...}` placeholder). Note the pom filters **only** that one resource — running
  the filter over `src/main/resources` would corrupt the icon PNGs.
- Versions: `jpackage.appVersion` in the pom must stay a plain dotted number
  (jpackage rejects `-SNAPSHOT`), and **jpackage on macOS rejects a version
  whose first number is 0** — release.yml bumps `0.x.y` → `1.x.y` for the
  bundle metadata on the mac legs only.
- Release: push a `v*` tag → `.github/workflows/release.yml` (5-runner matrix,
  no cross-building) builds DEB/RPM/DMG/MSI and publishes a GitHub release
  with checksums. The per-OS jpackage icon (`png`/`ico`/`icns`, all emitted by
  `scripts/gen_icon.java`) is picked by OS-activated `os-*` Maven profiles.

## Why not jNut?

The NUT project ships an official Java client
([networkupstools/jNut](https://github.com/networkupstools/jNut), actively
maintained). It was evaluated and deliberately not adopted: it is **not on
Maven Central** (its README states there is no `release:perform`, so it would
have to be vendored or built in CI), it has **no `module-info.java`** (an
automatic module, which would drag moditect into the currently ceremony-free
jlink build), and it is GPL-2.0 — workable only because of its Classpath
Exception. Our ~300-line client is shaped for this app (Optional-typed
accessors, severity, client-side RW validation, generation-guarded async) and
is well covered by tests. **Command TRACKING was taken from jNut as an idea
and reimplemented**; revisit the dependency only if Nux ever needs broad
protocol coverage, or if jNut publishes a modular artifact.

## Localisation

Every user-facing string goes through `com.nux.i18n.Messages.tr(key)` — never a
literal in a control. Strings live in
`resources/com/nux/i18n/messages[_<lang>].properties` (English is the base and the
fallback); bundled languages are en/es/fr/de/pt/it. Adding one is a complete
`messages_<lang>.properties` plus an entry in `Messages.LANGUAGES`;
`MessagesTest` fails the build on any key drift.

Three rules worth keeping:

- **`Messages.init` happens in `main()`, not `start()`.** `App`'s field
  initialisers call `tr()`, and they run when JavaFX constructs the Application —
  before `start()` is entered. Initialising later renders raw keys in the toolbar.
  `tr()` also lazily loads English if nothing initialised it, so a stray early
  lookup can never leak a key into the UI.
- **Pure cores return keys, not text.** `UpsStatus.labelKey()` and
  `StatusTransitions.alert()` yield catalogue keys; the UI resolves them. That
  keeps the decisions testable and independent of the active language.
- **Double the apostrophes in parameterised values.** MessageFormat treats a lone
  `'` as an escape, so `l'onduleur … {0}` silently loses its placeholder. Only
  strings containing `{0}` are affected (plain `tr(key)` skips MessageFormat), and
  a test formats every such value to prove the placeholder survives.
- **Keys alone don't prove a string is translated.** `NoLiteralUiTextTest` scans
  the sources for words handed straight to a control or palette entry — whole
  windows once shipped in English while their keys sat unused. Also: a
  `.properties` value loses its leading spaces, so separators belong in code, and
  pass a port to `tr()` as a String or MessageFormat prints `3,493`.

## Update check

`com.nux.update` mirrors Editora's design: a pure `UpdateCheck` (parse, compare,
throttle) plus an impure `UpdateService` (one daemon thread, results marshalled to
the FX thread, injectable endpoint + marshaller for tests).

- **Versions compare numerically per component**, so `0.10.0 > 0.9.0` — the case a
  lexicographic compare gets backwards. A `-SNAPSHOT`/`-rc` suffix sorts *below*
  its release, so a dev build is offered the release it precedes but never a
  downgrade.
- **`TopLevelJson` is depth-aware on purpose.** Nux has no JSON dependency, and a
  GitHub release payload repeats `html_url` and `name` inside `author` and
  `assets[]`; anything that searched the text would return an asset's name as the
  release name. Only depth-1 keys are read, and the test payload includes those
  decoys.
- **The throttle timestamp is written before the request**, so a check that fails
  (offline, rate-limited) doesn't retry on every launch for the rest of the day.
- Drafts and pre-releases are refused: never nudge a user toward something the
  author hasn't published.

## NUT protocol notes

Line-based text over TCP 3493. Requests like `LIST VAR <ups>`; replies
`BEGIN LIST` … `VAR <ups> <name> "<value>"` … `END LIST`; errors are
`ERR <CODE>`. Values are double-quoted with `\"`/`\\` escapes. Auth is
`USERNAME`/`PASSWORD` then `INSTCMD` (needs an `instcmds` entry in the
server's `upsd.users`). `STARTTLS` upgrades to TLS when upsd has `CERTFILE`.
**Tracking (NUT 2.8):** `SET TRACKING ON` **must follow authentication** —
upsd answers `ERR USERNAME-REQUIRED` otherwise (verified against a live 2.8
server). With it on, `INSTCMD`/`SET VAR` reply `OK TRACKING <id>` and
`GET TRACKING <id>` yields `PENDING` / `SUCCESS` / `ERR <code>`. Only a
driver-reported code counts as a failure: `UNKNOWN` and
`FEATURE-NOT-CONFIGURED` mean the server cannot answer, and reporting those
as failures would blame the command for the server's bookkeeping. Tracking is
polled via `executor.schedule`, never by sleeping, so the 5-second
measurement poll keeps its cadence.
`ups.status` is space-separated flags: OL (online), OB (on battery), LB (low
battery), CHRG/DISCHRG, RB (replace battery), FSD (forced shutdown), BYPASS,
OVER, OFF, ALARM.

## Roadmap pointers

See README and TODO.md. The tray (SNI on Linux), Windows notifications (via the
tray icon) and release CI have all shipped. Still deliberately open: signed
installers, a light theme, and pulling `App`'s connection/command/tray state
into tested coordinators (it is ~1050 lines with no direct coverage).
