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
- [x] ~~**macOS installer.**~~ Verified on real hardware: the DMG installs and Nux
      runs. That also clears the hand-written `.icns` container, the `0.x` → `1.x`
      `CFBundleVersion` bump jpackage requires, and the AWT tray path.
- [ ] **Windows installer (unverified beyond building).** The MSI builds cleanly in
      CI; nobody has installed and launched it.
- [ ] **Windows autostart, Start-menu entry and MSI upgrades (unverified).** Autostart
      now writes the Run value through `reg import` of a generated `.reg` file; the MSI
      gains a Start-menu entry, a desktop shortcut and a fixed UpgradeCode meant to equal
      the one jpackage derived for 0.5.0. Check that autostart survives
      `C:\Program Files`, the shortcut appears, and installing over 0.5.0 upgrades in
      place rather than side by side.
- [ ] **CI on Windows and macOS (unverified).** The test job now runs on all three
      OSes with headless JavaFX; the first run will show whether the FX tier holds
      there.
- [ ] **Windows notifications (unverified).** They now route through
      `TrayIcon.displayMessage`, which is the documented native path, but no one has
      seen one appear on a real Windows desktop.
- [x] ~~Verify the `.desktop`/`StartupWMClass` icon fix from an actually-installed
      `.deb`.~~ Done: the installed entry carries `StartupWMClass=com.nux.App` and
      `/opt/nux/lib/Nux.png` is byte-identical to `branding/nux.png`.

## Known gaps

- [ ] The app theme is hardcoded to AtlantaFX PrimerDark; no light/dark switch.

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

- [x] Headless-FX harness (`FxTestSupport`, JavaFX 27's built-in Headless platform, no
      TestFX dependency) covering the dashboard, the command palette, and a smoke test
      that every secondary window opens.
- [x] ~~`App` coverage~~ — its state machines now live in `com.nux.app`
      (`ConnectionController`, `CommandRunner`, `TrayController`), tested against
      `FakeNutServer` and a fake tray; `App` is UI construction and adapters (~760
      lines).
- [x] ~~A settings round-trip test~~ — `SettingsWindowFxTest` drives the dialog's own
      controls and checks the edit Save hands back.
- [ ] The tray backends (`SniTray`, `AwtTray`, `Trays`) are untested — they need a
      D-Bus session or an AWT tray. `SniMenu.Impl`'s GetLayout/GetGroupProperties/Event
      logic is plain Java, though, and could be tested without a bus.
- [ ] Spotless + a formatter, so formatting stops being a matter of habit.
- [x] ~~Dependabot~~ (maven + github-actions, weekly) and CI tests on all three OSes.
- [ ] A CI job that also builds the dist app-image on each platform, to catch
      packaging breakage before a tag; pin third-party actions by SHA.
