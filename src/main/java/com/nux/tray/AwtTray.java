package com.nux.tray;

import static com.nux.i18n.Messages.tr;

import java.awt.EventQueue;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;

import com.nux.service.Notifier;

/**
 * The AWT tray backend for Windows and macOS (and legacy X11 trays). All AWT
 * mutations are marshaled onto the EDT; the icon is a multi-resolution image so
 * HiDPI trays pick a crisp variant. The menu mirrors the SNI one: a disabled live
 * status line, Open, Quit.
 */
public final class AwtTray implements NuxTray {

    private static final int[] ICON_SIZES = {16, 24, 32, 48};

    private final SystemTray tray;
    private final TrayIcon trayIcon;
    private final MenuItem statusItem;

    public AwtTray(Runnable onActivate, Runnable onQuit) throws Exception {
        if (!SystemTray.isSupported()) {
            throw new UnsupportedOperationException("AWT SystemTray unsupported on this desktop");
        }
        tray = SystemTray.getSystemTray();

        statusItem = new MenuItem(tr("dashboard.notConnected"));
        statusItem.setEnabled(false);
        MenuItem open = new MenuItem(tr("tray.open"));
        open.addActionListener(e -> onActivate.run());
        MenuItem quit = new MenuItem(tr("tray.quit"));
        quit.addActionListener(e -> onQuit.run());
        PopupMenu menu = new PopupMenu();
        menu.add(statusItem);
        menu.addSeparator();
        menu.add(open);
        menu.add(quit);

        trayIcon = new TrayIcon(multiRes(TrayDisplay.disconnected()), "Nux", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> onActivate.run());
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 1) {
                    onActivate.run();
                }
            }
        });
        // add() on the EDT, but fail construction synchronously if the desktop refuses.
        Exception[] failure = new Exception[1];
        EventQueue.invokeAndWait(() -> {
            try {
                tray.add(trayIcon);
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        // Windows has no CLI equivalent of notify-send/osascript; the tray raises the
        // native notification instead, so hand the Notifier a channel it can use.
        Notifier.setTraySink(this::notify);
    }

    /** Raises a native notification through the tray icon. */
    public void notify(String title, String message) {
        EventQueue.invokeLater(() -> trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO));
    }

    @Override
    public void update(TrayDisplay display, String tooltip) {
        BaseMultiResolutionImage image = multiRes(display);
        EventQueue.invokeLater(() -> {
            trayIcon.setImage(image);
            trayIcon.setToolTip(tooltip);
            statusItem.setLabel(tooltip);
        });
    }

    @Override
    public void dispose() {
        Notifier.setTraySink(null);
        EventQueue.invokeLater(() -> tray.remove(trayIcon));
    }

    private static BaseMultiResolutionImage multiRes(TrayDisplay display) {
        BufferedImage[] variants = new BufferedImage[ICON_SIZES.length];
        for (int i = 0; i < ICON_SIZES.length; i++) {
            variants[i] = TrayIconRenderer.render(display, ICON_SIZES[i]);
        }
        return new BaseMultiResolutionImage(variants);
    }
}
