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
  nothing trusts a parse), `NutClient` (blocking socket; every read bounded by
  the socket timeout; STARTTLS upgrade reads the pre-TLS reply **unbuffered** so
  no handshake bytes are stolen), `TlsMode`.
- `com.nux.config` — pure `Config`/`Profile` records with withers +
  `ConfigStore` (properties file at `~/.nux/config.properties`, atomic
  temp+move writes, defaults on missing/malformed — never throws on load).
  The analytics/automation settings live in a nested `Advanced` record
  (persisted under an `adv.` prefix) so `Config`'s component list stays
  readable; add new settings there, not as more top-level components.
- `com.nux.store` — persistence beyond preferences: `HistoryStore`
  (`~/.nux/history/<ups>.csv`, append-only, downsampled by
  `shouldPersist` — a status change always writes so an outage is never
  smoothed away — with age-based `prune`; the UPS name is server-supplied so
  `safeName` reduces it to `[a-z0-9_-]`, dots included, rather than relying on
  slash-stripping alone) and `EventLog` (`~/.nux/events.csv`, capped, newest
  first for the UI). Both write on their own daemon thread; `load` is
  synchronous and belongs off the FX thread.
- `com.nux.analysis` — pure derivations over a snapshot/history:
  `BatteryHealth` (firmware date formats, age, aging/replace thresholds, and
  the UPS's own RB flag always winning), `EnergyEstimate` (measured watts,
  else load % × nominal, VA converted by power factor; kWh integration that
  **skips gaps over `MAX_GAP_SECONDS`** because a long gap means the app was
  closed, not that power was drawn), and `TestSchedule` (next-run/due, with
  "now" injected so the policy is testable).
- `com.nux.service` — `PollService` (ONE daemon thread owns the `NutClient`;
  polls every 5 s, posts to the FX thread via `Platform.runLater` under a
  monotonically-increasing **generation guard** so stale results are dropped;
  reconnects each tick, the poll interval doubling as backoff; `runInstCmd`
  runs on the same thread/connection so auth state stays accurate — upsd allows
  one USERNAME per connection, and a half-failed auth drops the connection so
  the next tick reconnects clean), `History` (NaN-tolerant sparkline ring
  buffer), `Notifier` (best-effort OS notifications off-thread: notify-send →
  gdbus fallback on Linux, osascript on macOS; failures swallowed).
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
  the FX thread (`nux-tray-init`). Config keys `tray` + `closeToTray` (both
  default true): with a tray, window close hides (`implicitExit(false)`);
  `stop()` ends with `System.exit(0)` so leftover AWT/D-Bus non-daemon threads
  can't keep a quit JVM alive.
- `com.nux.command` — `Command` (id/title/action) + `CommandRegistry`
  (insertion-ordered; dynamic families — `profile.connect.*`, `ups.cmd.*` —
  swap wholesale via `removeByPrefix`; pure ranked `filter` backs the palette).
  Every user-facing action should be registered here so the palette sees it.
- `com.nux.ui` + `com.nux.App` — the JavaFX layer: `Dashboard`, `ArcGauge` +
  `Sparkline` (Canvas), `CredentialsDialog`, `SettingsWindow` (connection +
  tray + notifications; hands a whole new `Config` to `App.applySettings`,
  which diffs and reconnects/re-inits only what changed), `CommandPalette`
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

See README. Deferred deliberately: tray icon (AWT SystemTray is unsupported on
Wayland/GNOME and mixing AWT into the FX process is flaky — needs an SNI/D-Bus
approach), Windows toast notifications, release CI.
