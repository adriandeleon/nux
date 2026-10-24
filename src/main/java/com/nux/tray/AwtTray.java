package com.nux.tray;

import static com.nux.i18n.Messages.tr;

import java.awt.CheckboxMenuItem;
import java.awt.EventQueue;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import com.nux.service.Notifier;

/**
 * The AWT tray backend for Windows and macOS (and legacy X11 trays). All AWT
 * mutations are marshaled onto the EDT; the icon is a multi-resolution image so
 * HiDPI trays pick a crisp variant. The menu mirrors the SNI one: a disabled live
 * status line, then the app's {@link TrayMenu} quick actions.
 */
public final class AwtTray implements NuxTray {

    private static final System.Logger LOG = System.getLogger(AwtTray.class.getName());
    private static final int[] ICON_SIZES = {16, 24, 32, 48};
    /** Bounds the wait in dispose(), so a wedged EDT can't hang shutdown. */
    private static final long DISPOSE_TIMEOUT_MS = 2000;

    private final SystemTray tray;
    private final TrayIcon trayIcon;
    private final MenuItem statusItem;
    private final PopupMenu menu = new PopupMenu();
    private final Runnable onActivate;
    private final Runnable onQuit;
    private final Consumer<String> onAction;

    public AwtTray(Runnable onActivate, Runnable onQuit, Consumer<String> onAction) throws Exception {
        this.onActivate = onActivate;
        this.onQuit = onQuit;
        this.onAction = onAction;
        if (!SystemTray.isSupported()) {
            throw new UnsupportedOperationException("AWT SystemTray unsupported on this desktop");
        }
        tray = SystemTray.getSystemTray();

        statusItem = new MenuItem(tr("dashboard.notConnected"));
        statusItem.setEnabled(false);
        rebuild(TrayMenu.minimal()); // not yet shown, so building it off the EDT is safe

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
    public void setMenu(TrayMenu actions) {
        EventQueue.invokeLater(() -> rebuild(actions));
    }

    private void rebuild(TrayMenu actions) {
        menu.removeAll();
        menu.add(statusItem);
        menu.addSeparator();
        addAll(menu, actions.items());
    }

    private void addAll(Menu parent, List<TrayMenu.Item> items) {
        for (TrayMenu.Item item : items) {
            switch (item.kind()) {
                case SEPARATOR -> parent.addSeparator();
                case ACTION -> {
                    MenuItem m = new MenuItem(item.label());
                    m.setEnabled(item.enabled());
                    m.addActionListener(e -> dispatch(item.id()));
                    parent.add(m);
                }
                case RADIO -> {
                    CheckboxMenuItem m = new CheckboxMenuItem(item.label(), item.checked());
                    m.addItemListener(e -> dispatch(item.id()));
                    parent.add(m);
                }
                case SUBMENU -> {
                    Menu sub = new Menu(item.label());
                    sub.setEnabled(item.enabled());
                    addAll(sub, item.children());
                    parent.add(sub);
                }
            }
        }
    }

    private void dispatch(String id) {
        switch (id) {
            case TrayMenu.OPEN -> onActivate.run();
            case TrayMenu.QUIT -> onQuit.run();
            default -> onAction.accept(id);
        }
    }

    /**
     * Removes the icon and waits for it, because App.stop() calls System.exit right
     * after: a queued removal would never run and Windows leaves a ghost icon behind
     * until the mouse passes over it. Runs inline on the EDT (waiting on itself would
     * deadlock) and otherwise waits a bounded time rather than invokeAndWait's forever.
     */
    @Override
    public void dispose() {
        Notifier.setTraySink(null);
        FutureTask<Void> removal = new FutureTask<>(() -> tray.remove(trayIcon), null);
        if (EventQueue.isDispatchThread()) {
            removal.run();
            return;
        }
        EventQueue.invokeLater(removal);
        try {
            removal.get(DISPOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.log(System.Logger.Level.WARNING, "Tray icon removal did not complete: " + e);
        }
    }

    private static BaseMultiResolutionImage multiRes(TrayDisplay display) {
        BufferedImage[] variants = new BufferedImage[ICON_SIZES.length];
        for (int i = 0; i < ICON_SIZES.length; i++) {
            variants[i] = TrayIconRenderer.render(display, ICON_SIZES[i]);
        }
        return new BaseMultiResolutionImage(variants);
    }
}
