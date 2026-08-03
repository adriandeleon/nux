# TODO

Planned work, roughly in priority order. Items marked **(unverified)** are shipped
but have never been exercised against real hardware or a real server configuration.

## Verification gaps

- [ ] **Command tracking end-to-end (unverified).** The NUT 2.8 tracking path degrades
      correctly on a server without it, but a full tracked round-trip has never run:
      it needs `TRACKINGDELAY` in `upsd.conf` plus credentials. Confirm that a real
      driver failure surfaces as a failure and not as a silent success.
- [ ] **Multi-UPS overview (unverified).** Correct by construction, but no test rig
      with more than one UPS on a single `upsd` has been available.
- [ ] **Windows and macOS installers (unverified beyond building).** The DMG and MSI
      build cleanly in CI; nobody has installed and launched them.
- [ ] **Windows notifications (unverified).** They now route through
      `TrayIcon.displayMessage`, which is the documented native path, but no one has
      seen one appear on a real Windows desktop.
- [ ] Verify the `.desktop`/`StartupWMClass` icon fix from an actually-installed `.deb`
      (it is verified inside the package, not after installation).

## Known gaps

- [ ] The app theme is hardcoded to AtlantaFX PrimerDark; no light/dark switch.
- [ ] No localisation: all strings are English literals in the source.

## Distribution

- [ ] Sign and notarise the macOS DMG; sign the Windows MSI. Both currently warn the
      user on first launch.
- [ ] Linux portable formats: AppImage and/or a tarball, plus a Flatpak manifest.
- [ ] Derive the Homebrew cask / winget manifest from the release assets.

## Features

- [ ] History: aggregate (downsample) long ranges so a 30-day chart doesn't plot
      40k points; export the selected range as CSV.
- [ ] Energy: per-day and per-month breakdown rather than a single total for the range.
- [ ] Self-tests: notify on completion, and keep a visible test history (the event log
      records them, but there is no dedicated view).
- [ ] Tray menu: switch UPS and run the most common instant commands without opening
      the window.
- [ ] Discovery: IPv6 support, and remember previously discovered servers.
- [ ] Per-UPS notification rules (e.g. ignore a UPS that is expected to be on battery).

## Engineering

- [x] Headless-FX harness (`FxTestSupport`, JavaFX 26's built-in Headless platform, no
      TestFX dependency) covering the dashboard, the command palette, and a smoke test
      that every secondary window opens.
- [ ] `App` itself (~700 lines) still has no coverage — the snapshot→dashboard→tray
      wiring, the self-test scheduler and settings-apply diffing all live there. Pulling
      those into a testable coordinator (as Editora does) is the prerequisite.
- [ ] A settings round-trip test (does Save produce the Config you expect?) needs
      `SettingsWindow` to expose its controls or return its built Config; today the
      smoke test only proves it opens.
- [ ] The tray backends (`SniTray`, `AwtTray`, `Trays`) are untested — they need a
      D-Bus session or an AWT tray. `SniMenu.Impl`'s GetLayout/GetGroupProperties/Event
      logic is plain Java, though, and could be tested without a bus.
- [ ] Extract the growing `App` class into per-feature coordinators once it passes
      ~700 lines, mirroring Editora's `ui/*Coordinator` split.
- [ ] Spotless + a formatter, so formatting stops being a matter of habit.
- [ ] Dependency and CI hygiene: Dependabot, and a `verify` job that also builds the
      dist app-image on each platform to catch packaging breakage before a tag.
