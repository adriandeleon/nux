# Changelog

All notable changes to Nux are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/); versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed

- Headless UI tests: 154 → 179. `FxTestSupport` boots JavaFX 26's built-in
  Headless Glass platform through `Platform.startup`, so the UI is testable
  with **no new dependency** (no TestFX, no Monocle) and no display or Xvfb.
  Covers the dashboard's presentation logic (severity classes not
  accumulating across updates, readings cleared on disconnect, the multi-UPS
  strip's visibility/selection/click), the command palette's keyboard flow
  (filter, arrow-key bounds, Enter runs and closes, Escape runs nothing), and
  a smoke test that every secondary window actually opens — which catches
  mistyped stylesheet paths and missing resources that would otherwise ship
  as a dead menu item. FX tests are tagged `fx`, so
  `./mvnw test -DexcludedGroups=fx` still runs the pure suite alone.

## [0.2.0] - 2026-08-03

### Added

- `AUTHORS`, `NOTICE` (third-party components and their licenses), `TODO.md`,
  and an `.editorconfig`.
- Maven wrapper (`./mvnw`), so a clone needs only a JDK.
- Start at login (Settings → Tray, and a palette command): an XDG autostart
  entry on Linux, a per-user LaunchAgent on macOS, and an `HKCU\…\Run` value
  on Windows. Available only in an installed build — the OS holds the state,
  so there is no config key to drift out of sync. With the tray enabled the
  entry adds `--minimized` so Nux comes up in the tray; that flag is ignored
  when no tray is available, so the window can never start unreachable.
- `--screenshot=<file>` saves the window as a PNG a few seconds after start,
  for documentation and bug reports.
- README screenshot.
- About window (toolbar **?** button, or "About Nux" in the command palette):
  icon, version, author, copyright, and links to the license, the source
  repository, the issue tracker, and the Network UPS Tools project. It also
  shows Java/JavaFX/OS versions and the config directory, with a **Copy
  details** button for pasting into a bug report. The version comes from a
  Maven-filtered resource rather than a constant, so `pom.xml` remains the
  single source of truth, and a `-SNAPSHOT` build is labelled a development
  build so it can't be mistaken for a release.

### Fixed

- Windows had no notifications at all: `Notifier` handled Linux and macOS and
  fell through to nothing, so the one thing a UPS monitor exists to tell you
  was silently dropped on a supported platform. Windows now raises native
  notifications through the tray icon (`TrayIcon.displayMessage`), which is
  also a fallback everywhere else if the platform tool is missing.
- The settable-variable editor did not refresh after a successful write, so
  the row kept showing the old value and the change looked like it had
  failed. It now reloads and re-selects the variable you were editing.

### Changed

- Tests: 107 → 154. The two most consequential classes had no coverage at all;
  both are now exercised against a scripted in-process upsd
  (`FakeNutServer`) that asserts both directions — the exact bytes sent and
  how replies are interpreted. `NutClient` gains 15 tests (command lines,
  `SET VAR` quote/backslash escaping, `LIST RW` type/enum/range round-trip,
  `ERR` mapping, tracking id extraction, the pre-2.8 `enableTracking` → false
  fallback, `LOGOUT` on close); `PollService` gains 12 covering the polling
  loop, auth-once-per-connection, the refusal to switch user mid-connection,
  tracking poll/resolve/fail, and the multi-UPS overview. `HistoryStore` and
  `EventLog` gain file-level tests (downsampling, status-change override,
  retention pruning, corrupt-line tolerance, size cap).
- `PollService` takes its UI marshaller as a constructor argument (defaulting
  to `Platform::runLater`) so its concurrency logic is testable without a
  JavaFX toolkit.
- `EventLog` no longer re-reads the whole file on every append to enforce its
  size cap; it checks every `maxEvents/10` appends instead, trading a bounded
  overshoot for dropping an O(n) read per event.
- The jpackage app version is now derived from `project.version` instead of
  being hardcoded (strip `-SNAPSHOT`; on macOS bump a leading `0.` to `1.`,
  which jpackage requires). An explicit `-Djpackage.appVersion` still wins,
  which is what release CI passes.
- README: badges, links to the Network UPS Tools project, and download
  pointers.
- LICENSE/copyright now carries the author's full name.

## [0.1.0] - 2026-08-02

First release.

### Added

- NUT protocol client core: quote/escape-aware line parsing, `LIST UPS` /
  `LIST VAR` / `LIST CMD` / `GET VAR`, `ups.status` decoding with severity,
  timeout-bounded blocking socket client.
- Live dashboard: status pill, battery/load/runtime arc gauges, input/output/
  battery voltage, raw variables table; 5 s polling with automatic reconnect
  and stale-result guarding.
- History sparklines: ~1 h ring buffers under each gauge plus a wide input-
  voltage strip; NaN-tolerant (gaps render as line breaks).
- Persisted configuration (`~/.nux/config.properties`, atomic writes):
  last connection, auto-connect on launch, named connection profiles
  (save/connect/delete from a toolbar menu).
- Desktop notifications on power events (on battery, low battery, power
  restored, replace battery, forced shutdown) and on lost/restored server
  contact — `notify-send` on Linux with a `gdbus` fallback, `osascript` on
  macOS.
- Instant commands (`INSTCMD`): Commands menu populated from the server, with
  confirmation, session-cached authentication, and readable error messages.
- STARTTLS: per-connection TLS toggle (persisted, per-profile); certificate +
  hostname verification by default, `tlsTrustAll` config escape hatch for
  self-signed upsd certificates.
- Native packaging: `mvn -Pdist package` builds a jlink runtime + jpackage
  app image (installers via `-Djpackage.type=DEB|RPM|DMG|PKG|MSI|EXE`).
- App icon (hex nut + lightning bolt), generated headlessly from
  `scripts/gen_icon.java`; vector source `branding/nux.svg`.
- Tray icon showing charge % (bolt glyph when unknown), colored by severity,
  with a live tooltip; click restores the window. Linux uses a pure-Java
  StatusNotifierItem over D-Bus (dbus-java — KDE native, GNOME via the
  AppIndicator extension, Wayland OK, re-registers after shell restarts);
  Windows/macOS use AWT. Disable with `tray=false` in the config.
- Tray context menu (native on every platform): a live status line — which also
  covers GNOME never rendering SNI tooltips — plus Open Nux and Quit Nux. On
  Linux this is a full com.canonical.dbusmenu implementation; AWT uses a
  PopupMenu.
- Close-to-tray: closing the window hides Nux to the tray (polling and
  notifications continue); Quit Nux in the tray menu exits. Disable with
  `closeToTray=false`.
- Tray charge text is measured and shrunk to fit, so "100" no longer clips.
- Platform installer icons: `scripts/gen_icon.java` now also emits `nux.ico`
  (Windows) and `nux.icns` (macOS) — pure-Java ICO/ICNS writers with embedded
  PNGs; OS-activated Maven profiles pick the right format for jpackage.
- Release CI: a `v*` tag builds DEB/RPM/DMG/MSI across a 5-runner matrix and
  publishes a GitHub release with checksums; `ci.yml` runs the tests on every
  push.
- Settings window (Ctrl/Cmd+, or the toolbar button): connection host/port/TLS
  and auto-connect moved off the toolbar, plus tray options (enable,
  close-to-tray, displayed metric) and a notifications toggle. The toolbar
  slims to Profiles / UPS / Connect / Commands with a read-only target label.
- Command palette (Ctrl/Cmd+Shift+P): searchable commands — connect, settings,
  save profile, quit, per-profile connect, per-UPS instant commands, and tray
  metric switching.
- Selectable tray metric: Battery %, Load %, or Runtime minutes (runtime shows
  an "m" suffix); switch from Settings or the palette, applied instantly.
- Persistent history: measurements are appended to
  `~/.nux/history/<ups>.csv` (downsampled to one row a minute, but a status
  change always writes so outages aren't smoothed away) and pruned to a
  configurable retention window. A History window charts battery, load, and
  input voltage over the last hour/day/week/month.
- Persistent event log (`~/.nux/events.csv`): every power transition, lost or
  restored server contact, self-test, and variable change, viewable in an
  Events window with CSV export.
- Battery age and health from `battery.date`/`battery.mfr.date` (several
  firmware date formats parsed), flagged as aging at 3 years and replace at 4
  — or immediately when the UPS raises its own replace-battery flag.
- Power and energy estimates: watts from the UPS's own reading, else load %
  against the nominal rating (VA converted with a power factor); the History
  window totals kWh over the selected range and, with a configured tariff,
  the cost.
- Settable-variable editor (NUT `upsrw`): reads `LIST RW` with each variable's
  type, choices, and range, and offers a matching control; values are
  validated client-side before being written.
- Scheduled battery self-tests: run every N days at a chosen time, with the
  result recorded in the event log. Runs only while Nux is open and after
  credentials have been entered this session — they are never stored on disk.
- Multi-UPS overview: on a server with several UPSes, a strip of status cards
  appears above the gauges; click one to switch the dashboard to it.
- Command tracking (NUT 2.8): instant commands and variable writes now report
  what the UPS actually did, not merely that upsd accepted the request. The
  tracking id is polled on the shared scheduler, so waiting never stalls the
  measurement poll; servers without tracking (or with it switched off) fall
  back to the previous accept-only behaviour automatically.
- Linux app icon: packaged builds now ship a `.desktop` entry carrying
  `StartupWMClass=com.nux.App` (the window's real class) plus proper
  categories and description, so the desktop matches the running window to
  the launcher and shows the Nux icon instead of a generic one. The icon is
  staged into the jpackage resource dir as well, because the DEB bundler
  regenerates it and would otherwise discard `--icon`.
  `scripts/install-dev-desktop.sh` does the same for runs from the source
  tree.
- LAN discovery: "Find servers…" in Settings (and a command-palette entry)
  scans the machine's own IPv4 subnets for NUT servers, confirming each hit
  with a real `LIST UPS` exchange before listing it. Ranges wider than a /22
  are refused so discovery can never become an unbounded sweep.
