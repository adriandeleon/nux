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
- [ ] **Windows and macOS installers (unverified beyond building).** The 0.1.0 DMG and
      MSI build cleanly in CI; nobody has installed and launched them.
- [ ] Verify the `.desktop`/`StartupWMClass` icon fix from an actually-installed `.deb`
      (it is verified inside the package, not after installation).

## Known gaps

- [ ] The settable-variable editor does not refresh its list after a successful write,
      so the row still shows the previous value until the window is reopened
      (`VariablesWindow` is passed a no-op reload callback).
- [ ] Desktop notifications are a no-op on Windows — needs a toast implementation.
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

- [ ] FX-level tests. The suite is 100% pure logic today; the UI classes have no
      coverage at all. A headless-FX harness (as in Editora) would cover the
      dashboard, tray display wiring, and settings round-trip.
- [ ] Extract the growing `App` class into per-feature coordinators once it passes
      ~700 lines, mirroring Editora's `ui/*Coordinator` split.
- [ ] Spotless + a formatter, so formatting stops being a matter of habit.
- [ ] Dependency and CI hygiene: Dependabot, and a `verify` job that also builds the
      dist app-image on each platform to catch packaging breakage before a tag.
