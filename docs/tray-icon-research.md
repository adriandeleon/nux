# Tray icon research (2026-08)

Goal: a tray/menu-bar icon showing UPS state (and charge %) on **Linux
(GNOME/KDE), Windows, and macOS**, without compromising Nux's two build
invariants: pure-Java (no JNI/JNA natives) and all-real-JPMS-modules (so
`-Pdist` jlinks with no moditect ceremony).

## The Linux problem, precisely

There are two unrelated "tray" protocols on Linux:

- **XEmbed** (legacy X11): what `java.awt.SystemTray` speaks. Dead on modern
  desktops — GNOME removed it in 3.26, it can't exist on Wayland, KDE only
  shows such icons through a compat proxy. On a GNOME/Wayland session AWT
  reports `SystemTray.isSupported() == false`.
- **StatusNotifierItem (SNI)**: the freedesktop D-Bus protocol every modern
  desktop uses. KDE Plasma supports it natively; GNOME needs the AppIndicator
  extension (`ubuntu-appindicators` — shipped by default on Ubuntu, one click
  on Fedora); XFCE/LXQt/sway-waybar all speak it. Being D-Bus, it works
  identically on X11 and Wayland.

Measured on the dev box (GNOME, Wayland): `org.kde.StatusNotifierWatcher` is
live on the session bus, provided by gnome-shell via the ubuntu-appindicators
extension — so SNI works *here*, today, where AWT cannot. Any tray solution
(Qt, Electron, dorkbox, ours) has the same GNOME-needs-the-extension caveat;
that's GNOME policy, not something a library can route around.

## Candidates evaluated

| Option | Win | mac | Linux GNOME/KDE | Verdict |
|---|---|---|---|---|
| `java.awt.SystemTray` (JDK) | ✅ solid | ✅ works | ❌ XEmbed-only | Half the answer |
| FXTrayIcon 4.2.0 (2024) | ✅ | ✅ | ❌ (AWT wrapper) | Same Linux gap, adds nothing we need |
| dorkbox/SystemTray 4.4 | ✅ | ✅ | ✅ via GTK/AppIndicator | Rejected — see below |
| JavaFX built-in | — | — | — | Doesn't exist (still no tray API in JavaFX 26) |
| **AWT + own SNI over dbus-java** | ✅ | ✅ | ✅ SNI | **Chosen** |

**dorkbox/SystemTray** is the only off-the-shelf full-coverage option, but:
last release **4.4, Aug 2023**; loads native GTK via JNA (breaks the
pure-Java invariant); its jars + the dorkbox util constellation are automatic
modules (breaks the clean-jlink invariant, reintroducing Editora-style
moditect ceremony); and it has known, documented JavaFX+GTK3 interaction
issues it says it cannot fix on its side. Too much machinery for one icon.

**No pure-Java SNI library exists on Maven Central** (SNI implementations are
plentiful in Rust/Haskell/C, absent in Java) — so the Linux half is a
build-it situation, which is fine: the protocol is small.

## dbus-java 5.1.1 (com.github.hypfvieh) — verified suitable

- `dbus-java-core` → **real JPMS module** `org.freedesktop.dbus`; requires
  only JDK modules + `org.slf4j` (slf4j-api 2.x, also a real module).
- `dbus-java-transport-native-unixsocket` → real module
  `org.freedesktop.dbus.transport.jre`, built on the JDK's own Unix-domain
  socket channels — **no JNI, no junixsocket**.
- Actively maintained (5.1.1, 2025-03).

So the Linux backend is: declare the `org.kde.StatusNotifierItem` D-Bus
interface, register with `org.kde.StatusNotifierWatcher`, push the icon as an
ARGB pixmap property, emit `NewIcon`/`NewToolTip` on updates. No watcher on
the bus ⇒ tray quietly unavailable (correct on stock GNOME without the
extension; the app is fully usable without it).

## Recommended architecture

One small `com.nux.tray` abstraction, two backends, chosen at runtime by OS:

```
TrayIcon (interface): show(image, tooltip) / update(...) / onActivate / dispose
├─ AwtTray      — Windows + macOS (java.awt.SystemTray; requires java.desktop)
└─ SniTray     — Linux (dbus-java; SNI over the session bus)
```

Shared: a Java2D **icon renderer** drawing the current state (bolt glyph,
color by severity, charge % text) into a `BufferedImage` — both backends
consume the same pixels (AWT directly; SNI as the ARGB pixmap).

Phasing:

1. **Phase 1 — icon + tooltip + activate**: state-colored icon with charge %,
   tooltip with status/runtime, click restores/raises the window. This is the
   80% of the value and avoids the genuinely hairy part.
2. **Phase 2 — menus**: context menu (show/hide, per-command items, quit).
   AWT menus are trivial; SNI menus mean implementing `com.canonical.dbusmenu`,
   which is the largest single chunk in this whole feature — deferred until
   phase 1 proves out.

Platform caveats to carry:

- **macOS**: AWT menu-bar icons can't be marked as template images from Java,
  so the icon must be designed to read on both light and dark menu bars
  (mid-gray/white with outline). AWT coexists fine with JavaFX here as long
  as headless mode is never set.
- **Windows**: AWT tray is reliable; nothing special.
- **Linux**: requires the SNI watcher (KDE always; GNOME with the
  AppIndicator extension). Detect by checking the bus for
  `org.kde.StatusNotifierWatcher`; absent ⇒ report unsupported, don't fake it.

## Cost

- New deps: `dbus-java-core` + `dbus-java-transport-native-unixsocket` +
  `slf4j-api` (+ a no-op or simple slf4j binding) — all real modules, jlink
  stays ceremony-free. Linux-only at runtime; harmless elsewhere.
- `module-info`: `requires java.desktop` (AWT backend) + the dbus modules.
  jlink image grows a few MB (java.desktop).
- Runtime: zero cost when disabled; when enabled, one icon repaint per poll
  (5 s) only when the rendered state actually changed.
