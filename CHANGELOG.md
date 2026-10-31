# Changelog

All notable changes to Nux are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/); versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed

- **The macOS app is signed and notarized.** Both dmgs (Apple silicon and Intel)
  are now signed with a Developer ID certificate and notarized by Apple, so Nux
  opens on first launch with no Gatekeeper warning. The Windows and Linux
  installers are still unsigned.

## [0.6.1] - 2026-10-24

### Fixed

- The Linux x64 **.deb was missing from the 0.6.0 release**: building the RPM
  in the same job deleted it. Only the arm64 .deb was published, and installing
  it on an amd64 machine fails with every dependency "not installable".

## [0.6.0] - 2026-10-24

### Added

- **Cost per hour, day, week and month** on the dashboard, from the current
  draw and the price in Settings → Power.
- History gains tabs: **Energy & cost** (kWh and cost per hour, day, week or
  month, with a total) and **Outages** (every on-battery stretch: start,
  duration, lowest charge, runtime left, low battery). The Charts tab exports
  the shown range as CSV and draws 30 days quickly (min/max per pixel).
- While on battery the dashboard says **how long, and how much runtime is
  left at the current load**.
- **Watch several servers at once**: other saved profiles are polled in the
  background, recorded, alerted on and shown as cards; click one to switch.
- **Per-UPS notification rules** (all events, critical only, none) from the
  dashboard's Notify menu; the event log still records everything.
- **Tray quick actions**: connect/disconnect, switch UPS, battery self-test and
  every instant command, without opening the window.
- **Light theme**, following the system's light/dark setting by default.
- **Keyboard shortcuts** for the toolbar (Ctrl/Cmd+K connect, +U UPS, +Shift+C
  commands, +Shift+H history, +Shift+E events, F1 about), shown in tooltips.
- README and About say plainly that Nux doesn't shut the computer down —
  NUT's upsmon does — with a minimal upsmon setup.
- CI builds the DEB, DMG and MSI on every PR and on master.

- History charts show where the UPS was on battery (shaded), break the line
  across stretches when Nux wasn't running, label dates on multi-day ranges,
  and read out the nearest sample under the pointer.
- The event log records when server contact is **restored**, with how long it
  was lost, and gains a Severity column.
- Settings has a **General** tab (language, notifications, start at login,
  single instance, update check), a *System default* language, a next-run line
  for scheduled self-tests, and validation that says why Save is disabled.
- Connection failures explain themselves: refused (is upsd running, does it
  LISTEN there?), unknown host, timeout, TLS, unknown UPS, stale data, driver
  down — instead of raw exception text.
- The MSI installs a Start-menu entry and a desktop shortcut.
- CI runs the tests on Linux, Windows and macOS; Dependabot watches Maven and
  GitHub Actions.

### Changed

- "Lost contact" is announced only after about 10 seconds without the server,
  and only for a server Nux had actually reached. A upsd restart that recovers
  on the next poll no longer raises a notification and an event each time.
- The runtime gauge is coloured by the UPS's own low-runtime threshold instead
  of by how full its 60-minute arc is — a healthy 27 minutes on mains was amber.
- Runtime and input-voltage sparklines keep a minimum scale and print it, so a
  1 V step no longer looks like a surge.
- History is stored per server as well as per UPS
  (`~/.nux/history/<ups>__<host>_<port>.csv`), so two servers that both call
  their UPS `ups` keep separate charts. Existing history is carried over.
- "Connect automatically on launch" is now only ever changed by its checkbox,
  and is on by default.
- Pressing Disconnect shows a neutral *Stopped* state, not the red fault pill,
  and clears the variables table and UPS commands instead of leaving stale ones.
- Secondary windows open once; asking again brings the open one forward.
- The interface is fully translated: the History and Variables windows, parts
  of Settings, palette entries and several messages were still English. A test
  now fails the build on untranslated text.

- Risky instant commands get a proper warning. Commands that cut power
  (`load.off`, `load.cycle`, `shutdown.*` and their `outlet.N` forms), run the
  battery down (calibration, deep test, simulated failure) or leave equipment
  unprotected (bypass) now show what will happen, a button naming the command,
  and **Cancel as the default** — Enter, Enter from the palette no longer
  switches off the equipment. Everyday commands keep the plain OK/Cancel.

### Internal

- `App`'s connection, command and tray logic moved into toolkit-free
  controllers (`com.nux.app`: `ConnectionController`, `CommandRunner`,
  `TrayController`) sharing one `ConfigHolder`, tested end to end against the
  in-process fake upsd and a fake tray backend. `App` is now UI construction
  and adapters.

### Fixed

- Scheduled battery self-tests never ran: the first run was recomputed from
  "now" on every poll, so it was always tomorrow. The schedule is now armed
  (`adv.selfTestArmed`) when it is switched on or its time changes, and the
  first run is the next scheduled time after that. Re-enabling a lapsed
  schedule no longer fires an overdue test straight away.
- Pointing Settings at another server, or renaming a UPS in `ups.conf`, could
  leave Nux polling a UPS name the server doesn't have — reconnecting every
  5 seconds with a lost/restored notification pair each time. The poller now
  falls back to the first listed UPS and the toolbar shows exactly that.
- The writable-variables window wrote to whichever UPS the main window had
  selected, not the one it showed.
- An unexpected (non-I/O) error, such as a port out of range, stopped polling
  for good with the UI stuck on "connecting". It is now reported and retried.
- On a multi-UPS server, one UPS the server refused (e.g. `DATA-STALE`) hid the
  whole overview; it now keeps its card, shown as unknown.
- Every UPS switch left another no-op poll task waking every 5 seconds for the
  life of the app.
- `driver.killpower` is treated as a power-cutting command (warning, Cancel
  default) like `load.off`.
- Config saves no longer run on the UI thread.
- A rising sparkline no longer draws over its scale label, and extra dashboard
  strips can't push the window taller than the screen.
- Saving Settings no longer rolls back anything written while the dialog was
  open (the selected UPS, a self-test's last run, the update-check throttle).
- Changing "Close to tray" while running no longer makes closing the window
  quit (or leave an invisible process behind).
- A command the UPS doesn't support no longer drops the connection or asks for
  the password again; credentials containing spaces or quotes now work.
- Scheduled self-tests wait while the UPS is on battery.
- A hostile or broken server (or any host answering on port 3493 during
  discovery) can no longer exhaust memory or hold the poll thread: replies are
  bounded in size and time.
- History pruning could lose a sample written at the same moment, ran only for
  the last-used UPS and only at launch; it now runs safely, for every file,
  daily, and a retention change applies without a restart. The event log's cap
  now holds across many short sessions.
- A discovery rescan no longer revives the previous scan.
- Two quick launches could both start; the single-instance lock is now taken
  before the window opens, and only the same user's Nux can raise it.
- Windows start-at-login works when Nux is installed under `C:\Program Files`;
  macOS start-at-login no longer launches a second copy when enabled.
- Packaged builds format numbers for the UI language (the locale data was
  missing from the runtime image), keeping the system's region.
- The tray falls back cleanly when the desktop's tray host rejects it.
- Release tags must match the pom version; `-rc` tags publish as pre-releases.
- About showed "SNAPSHOT(development build)" with no space; Discovery printed
  the port as "3,493".

## [0.5.0] - 2026-08-03

### Added

- Update notice: Nux checks GitHub once a day for a newer release and shows an
  "Update available" pill in the toolbar; clicking it opens the release page
  and stops mentioning that version. The About window shows the same line, and
  the palette has a manual **Check for updates…** that ignores the throttle.
  Turn the automatic check off in Settings → Tray. The check is an ordinary
  HTTPS GET of a public endpoint and sends nothing about the user.
  The timestamp is stamped *before* the request, so a failing check throttles
  itself instead of retrying on every launch.
  Nux has no JSON library, and taking one for four fields would have been a
  poor trade, so the payload is read by a small depth-aware scanner
  (`TopLevelJson`) that only sees top-level keys — necessary because a release
  payload repeats `html_url` and `name` inside its nested `author` and
  `assets[]` objects, where naive extraction would happily return an asset's
  name as the release name. Tested against that exact shape, and the parser
  verified against a real 13 KB payload from the live API.

## [0.4.0] - 2026-08-03

### Added

- Multiple languages: **English, Spanish, French, German, Portuguese and
  Italian**. The language follows the system unless overridden in Settings →
  Tray; a change applies on the next launch, since the catalogue is loaded
  once at startup. `Locale.setDefault` follows it too, so JavaFX's own
  OK/Cancel buttons match.
  Status labels and power-event alerts are produced as catalogue *keys* by
  the pure `UpsStatus`/`StatusTransitions` cores and translated at the edge,
  keeping those decisions testable and language-independent.
  `MessagesTest` enforces that every language defines exactly the base key
  set, that no value is blank, that no translation drops a `{0}` placeholder,
  and that no apostrophe silently swallows one (MessageFormat treats a lone
  `'` as an escape — a real hazard in French, Italian and Portuguese).

## [0.3.0] - 2026-08-03

### Added

- Single instance: launching Nux again raises the running window instead of
  starting a second copy that would poll the same UPS twice. The hand-off
  happens before the toolkit starts, so there is no window flash. It is a
  loopback socket on an ephemeral port recorded in `~/.nux/instance.port`,
  and the running instance must complete a handshake before the new launch
  stands down — after a crash that port can be reused by unrelated software,
  and trusting *any* answer would leave the user with no Nux at all. Turn it
  off in Settings → Tray to run several copies side by side.

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
