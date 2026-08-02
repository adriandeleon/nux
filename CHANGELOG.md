# Changelog

All notable changes to Nux are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/); versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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
