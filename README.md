# Nux

<img src="branding/nux.svg" width="96" align="right" alt="Nux icon">

A simple, powerful, multiplatform **NUT (Network UPS Tools) client** in JavaFX.
*Nux* is Latin for "nut."

Nux is a pure network client: it speaks the NUT protocol (TCP 3493) directly to
`upsd`, so it runs on Linux, macOS, and Windows without any drivers — the NUT
server stays wherever the UPS is plugged in.

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
authentication, and **STARTTLS** (per-connection TLS toggle).

## Requirements

- JDK 25+, Maven
- A reachable NUT server (`upsd`)

## Run

```bash
mvn javafx:run
```

Tests (pure protocol core, no toolkit needed):

```bash
mvn test
```

Native package (jlink runtime + jpackage; all dependencies are real JPMS
modules, so no moditect ceremony):

```bash
mvn -Pdist package                      # portable app image → target/dist/Nux/
mvn -Pdist package -Djpackage.type=DEB  # or RPM / DMG / PKG / MSI / EXE
```

## Setting up the NUT server (Linux, CyberPower over USB)

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

## License

[MIT](LICENSE)
