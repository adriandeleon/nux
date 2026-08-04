module com.nux {
    requires javafx.controls;
    requires atlantafx.base;

    // Tray icon: AWT backend (Windows/macOS) ...
    requires java.desktop;
    // Update check: an HTTPS GET of the GitHub releases API.
    requires java.net.http;
    // ... and the Linux StatusNotifierItem backend over D-Bus. The transport and the
    // slf4j->JUL bridge are service providers, required explicitly so jlink includes them.
    requires org.freedesktop.dbus;
    requires org.freedesktop.dbus.transport.jre;
    requires org.slf4j.jul;

    // javafx.graphics reflectively instantiates the Application subclass.
    exports com.nux to javafx.graphics;
    // dbus-java reflects over the exported StatusNotifierItem object and its structs.
    exports com.nux.tray to org.freedesktop.dbus;
    opens com.nux.tray to org.freedesktop.dbus;
}
