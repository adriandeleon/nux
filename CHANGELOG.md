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
