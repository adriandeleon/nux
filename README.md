# Nux

<img src="branding/nux.svg" width="96" align="right" alt="Nux icon">

[![CI](https://github.com/adriandeleon/nux/actions/workflows/ci.yml/badge.svg)](https://github.com/adriandeleon/nux/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/github/license/adriandeleon/nux)](LICENSE)
![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk&logoColor=white)
![JavaFX](https://img.shields.io/badge/JavaFX-26-1e90ff)
![Platforms](https://img.shields.io/badge/platform-Windows%20%7C%20macOS%20%7C%20Linux-lightgrey)
[![Release](https://img.shields.io/github/v/release/adriandeleon/nux)](https://github.com/adriandeleon/nux/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/adriandeleon/nux/total)](https://github.com/adriandeleon/nux/releases)
[![Release workflow](https://github.com/adriandeleon/nux/actions/workflows/release.yml/badge.svg)](https://github.com/adriandeleon/nux/actions/workflows/release.yml)
[![Stars](https://img.shields.io/github/stars/adriandeleon/nux?style=flat)](https://github.com/adriandeleon/nux/stargazers)

A simple, powerful, multiplatform client for
[**Network UPS Tools**](https://networkupstools.org/) (NUT), built with **JDK 25**,
**JavaFX 26** and **Maven**. *Nux* is Latin for "nut."

Nux is a pure network client: it speaks the [NUT network
protocol](https://networkupstools.org/docs/developer-guide.chunked/net-protocol.html)
(TCP 3493) directly to `upsd`, so it runs on Linux, macOS, and Windows without any
drivers — the NUT server stays wherever the UPS is plugged in.

Nux is an independent client, not affiliated with or endorsed by the NUT project.
It is built with the help of AI coding tools.

📥 **Downloads:** [latest release](https://github.com/adriandeleon/nux/releases/latest)
— `.deb` / `.rpm` (Linux x64 + arm64), `.dmg` (macOS Intel + Apple silicon), `.msi`
(Windows). Installers are currently unsigned.

![Nux dashboard](docs/screenshot.png)

## Status

Working: connect to a `upsd`, pick a UPS, live dashboard (status pill,
battery/load/runtime gauges with history sparklines, an input-voltage strip,
voltages) polling every 5 s with automatic reconnect, a raw variables table,
**connection profiles + persisted config with auto-connect on launch**
(`~/.nux/config.properties`), and **desktop notifications** on power events
(on battery / low battery / power restored / replace battery / forced
shutdown, plus lost/restored server contact) via `notify-send` on Linux
(falling back to `gdbus` when libnotify isn't installed) and `osascript` on
macOS. Also: **instant commands** (`INSTCMD` — beeper toggle, battery
self-test, …) from a Commands menu with confirmation and session-cached
authentication, **STARTTLS** (per-connection TLS toggle), a **settings
window** (`Ctrl/Cmd+,`) for connection/tray/notification options, a
**command palette** (`Ctrl/Cmd+Shift+P`), and a **selectable tray metric**
(battery % / load % / runtime minutes).

Beyond the live view it keeps **persistent history** (charts over the last
hour to 30 days, with kWh and cost estimates), a **persistent event log**
(exportable as CSV), **battery age/health** tracking, an editor for the UPS's
**settable variables** (`upsrw`), **scheduled battery self-tests**, a
**multi-UPS overview** for servers with more than one UPS, **command
tracking** (NUT 2.8 — reports what the UPS actually did, not just that the
request was accepted), and **LAN discovery** of NUT servers.

## Requirements

- JDK 25+ (the Maven wrapper supplies Maven itself)
- A reachable [NUT](https://networkupstools.org/) server (`upsd`)

## Run

```bash
./mvnw javafx:run
```

Tests (pure protocol core, no toolkit needed):

```bash
./mvnw test
```

Native package (jlink runtime + jpackage; all dependencies are real JPMS
modules, so no moditect ceremony). The app version is derived from the project
version automatically:

```bash
./mvnw -Pdist package                      # portable app image → target/dist/Nux/
./mvnw -Pdist package -Djpackage.type=DEB  # or RPM / DMG / PKG / MSI / EXE
```

On Linux, a run from the source tree has no installed desktop entry, so the
window shows a generic icon until you register one once:

```bash
scripts/install-dev-desktop.sh
```

## Setting up the NUT server (Linux, CyberPower over USB)

Nux talks to a NUT server; it does not talk to the UPS itself. If you don't have
one yet, install NUT from your distribution and see the project's
[installation guide](https://networkupstools.org/docs/user-manual.chunked/index.html)
and [hardware compatibility list](https://networkupstools.org/stable-hcl.html).
A minimal single-machine setup:

```ini
# /etc/nut/ups.conf
[cyberpower]
    driver = usbhid-ups
    port = auto
    desc = "Desk UPS"
```

```ini
# /etc/nut/nut.conf
MODE=standalone        # or netserver to allow LAN clients
```

Then `sudo systemctl enable --now nut-server` and check with
`upsc cyberpower@localhost`.

To run instant commands (beeper toggle, self-test) Nux needs a `upsd.users`
entry with `instcmds` permission:

```ini
# /etc/nut/upsd.users
[nuxuser]
    password = changeme
    instcmds = ALL
```

### TLS

The toolbar TLS checkbox upgrades the connection with the NUT `STARTTLS`
command; the server needs `CERTFILE` configured in `upsd.conf`. Certificates
are verified against the system truststore by default. For the common
self-signed upsd cert, set `tlsTrustAll=true` by hand in
`~/.nux/config.properties` — that encrypts the wire without authenticating the
server, which is why there's deliberately no UI checkbox for it.

## Architecture

- `com.nux.protocol` — pure, unit-tested core: line tokenizer/parsers
  (`NutParser`), status decoding (`UpsStatus`), transition alerts
  (`StatusTransitions`), snapshot model (`UpsSnapshot`), and the blocking
  socket client (`NutClient`).
- `com.nux.config` — pure, unit-tested `Config`/`Profile` + `ConfigStore`
  (properties file, atomic writes, defaults on missing/malformed).
- `com.nux.service` — `PollService` (one daemon thread polls `LIST VAR`,
  posts snapshots to the FX thread with a generation guard, reconnects with
  the poll interval as backoff), `History` (sparkline ring buffer),
  `Notifier` (best-effort OS notifications, off-thread).
- `com.nux.ui` / `com.nux.App` — the JavaFX dashboard (AtlantaFX-themed).

## Roadmap

- [x] Multi-server / multi-UPS profiles, persisted config, auto-connect
- [x] Status-transition desktop notifications (OL → OB, LB, power restored…)
- [x] History sparklines (charge/load/runtime + input voltage)
- [x] `INSTCMD` support (beeper toggle, battery self-test) with auth
- [x] STARTTLS (verified by default; `tlsTrustAll` config escape hatch)
- [x] jpackage native packaging (`-Pdist`, APP_IMAGE/DEB/RPM/DMG/MSI)
- [x] App icon (`branding/nux.svg`, PNGs via `java scripts/gen_icon.java`)
- [x] Tray icon with charge % — SNI (StatusNotifierItem over D-Bus) on Linux
      (KDE native; GNOME via the AppIndicator extension; Wayland OK), AWT on
      Windows/macOS; click restores the window (see
      `docs/tray-icon-research.md`; disable with `tray=false` in the config)
- [x] Tray context menu (com.canonical.dbusmenu on Linux, AWT PopupMenu
      elsewhere): live status line + Open + Quit. The status line also stands
      in for the tooltip on GNOME, whose AppIndicator extension doesn't render
      SNI tooltips (KDE shows them)
- [x] Close-to-tray (window close hides; tray-menu Quit exits;
      `closeToTray=false` restores plain close-means-quit)
- [x] Release CI: a `v*` tag builds DEB/RPM (Linux x64), DEB (Linux arm64),
      DMG (macOS x64 + arm64), MSI (Windows) and attaches them, with
      checksums, to a GitHub release (`.github/workflows/release.yml`;
      installers are unsigned — signing/notarization is a follow-up)
- [ ] Windows toast notifications

## Project files

- [CHANGELOG.md](CHANGELOG.md) — release history
- [TODO.md](TODO.md) — planned work and known gaps
- [AUTHORS](AUTHORS) — authors and acknowledgements
- [NOTICE](NOTICE) — third-party components and their licenses
- [docs/tray-icon-research.md](docs/tray-icon-research.md) — why the Linux tray
  is a hand-rolled StatusNotifierItem implementation

## License

[MIT](LICENSE) — copyright (c) 2026 Adrián Arturo De León Saldivar.

Nux depends on components under their own licenses (OpenJFX under GPLv2 with the
Classpath Exception; AtlantaFX, dbus-java and SLF4J under MIT) — see [NOTICE](NOTICE).
