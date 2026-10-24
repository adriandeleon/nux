package com.nux.app;

import static com.nux.i18n.Messages.tr;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.nux.config.Config;
import com.nux.config.ConfigHolder;
import com.nux.protocol.UpsSnapshot;
import com.nux.tray.NuxTray;
import com.nux.tray.TrayDisplay;
import com.nux.tray.TrayMetric;

/**
 * The tray icon's lifecycle and the close-to-tray policy, out of {@code App} so the
 * races (a tray toggled off and on while its backend is still starting, a backend that
 * fails) can be tested with a fake backend.
 *
 * <p>Toolkit-free: the backend factory runs on {@code background} (it touches D-Bus or
 * AWT and may block), its outcome comes back through {@code ui}, and the JavaFX
 * implicit-exit flag and the window are reached through {@link Window}. Every other
 * call happens on the FX thread.
 */
public final class TrayController {

    /** The bits of the main window and the toolkit the tray policy needs. */
    public interface Window {
        boolean isShowing();

        /** Brings the window up when there is no tray left to come back from. */
        void show();

        /** {@code Platform.setImplicitExit}: whether closing the last window quits. */
        void setImplicitExit(boolean exitOnLastClose);
    }

    private static final System.Logger LOG = System.getLogger(TrayController.class.getName());

    private final ConfigHolder config;
    private final Supplier<Optional<NuxTray>> factory;
    private final Executor background;
    private final Consumer<Runnable> ui;
    private final Window window;

    private NuxTray tray;
    private int generation; // a backend init whose generation is stale disposes its icon
    private TrayDisplay lastDisplay; // the last pushed state, to skip redundant repaints
    private String lastTooltip;
    // What the icon should show, kept so a metric or tray change can re-render it:
    private UpsSnapshot snapshot; // live data, or null
    private boolean connectionWanted; // null snapshot: "disconnected" (retrying) vs "not connected"

    public TrayController(
            ConfigHolder config,
            Supplier<Optional<NuxTray>> factory,
            Executor background,
            Consumer<Runnable> ui,
            Window window) {
        this.config = config;
        this.factory = factory;
        this.background = background;
        this.ui = ui;
        this.window = window;
    }

    /**
     * Creates the backend when the tray is enabled. It always posts an outcome, even when
     * the backend throws something unexpected: a {@code --minimized} start waits on it to
     * decide whether the window must come up after all.
     */
    public void start() {
        if (!config.get().tray()) {
            updateImplicitExit();
            return;
        }
        int mine = ++generation;
        background.execute(() -> {
            Optional<NuxTray> created;
            try {
                created = factory.get();
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING, "Tray initialisation failed", t);
                created = Optional.empty();
            }
            Optional<NuxTray> outcome = created;
            ui.accept(() -> onCreated(mine, outcome));
        });
    }

    /** Settings were saved over {@code before}: toggle the tray, re-apply the close policy, repaint. */
    public void settingsSaved(Config before) {
        boolean wanted = config.get().tray();
        if (wanted != before.tray()) {
            if (wanted) {
                start();
            } else {
                generation++; // an init still in flight must not install its icon
                if (tray != null) {
                    tray.dispose();
                    tray = null;
                }
                if (!window.isShowing()) {
                    window.show(); // nothing left to bring it back from
                }
            }
        }
        updateImplicitExit(); // closeToTray can change without the tray toggling
        refresh();
    }

    /** Whether closing the window should hide it to the tray rather than quit. */
    public boolean closeHidesWindow() {
        return tray != null && config.get().closeToTray();
    }

    public boolean hasTray() {
        return tray != null;
    }

    /** Live data arrived. */
    public void show(UpsSnapshot latest) {
        snapshot = latest;
        connectionWanted = true;
        push(TrayDisplay.of(latest, metric()), TrayDisplay.tooltip(latest));
    }

    /** No live data: {@code retrying} while meant to be connected, else plain "not connected". */
    public void showIdle(boolean retrying) {
        snapshot = null;
        connectionWanted = retrying;
        push(TrayDisplay.disconnected(), retrying ? tr("tray.disconnected") : tr("tray.notConnected"));
    }

    /** Recomputes the icon from the current state, bypassing the dedupe (metric/settings changes). */
    public void refresh() {
        lastDisplay = null;
        lastTooltip = null;
        if (snapshot != null) {
            show(snapshot);
        } else {
            showIdle(connectionWanted);
        }
    }

    public void dispose() {
        generation++;
        if (tray != null) {
            tray.dispose();
            tray = null;
        }
    }

    private void onCreated(int mine, Optional<NuxTray> created) {
        if (mine != generation || !config.get().tray()) {
            // Toggled off (or off and on again) while starting: a newer init, if any, owns
            // the tray now. Without this two inits could both assign it.
            created.ifPresent(NuxTray::dispose);
            return;
        }
        if (created.isEmpty()) {
            // No tray after all: a window started minimised would be unreachable, so bring
            // it up rather than hiding forever.
            if (!window.isShowing()) {
                window.show();
            }
            updateImplicitExit();
            return;
        }
        if (tray != null) {
            tray.dispose();
        }
        tray = created.get();
        updateImplicitExit();
        refresh();
    }

    /**
     * The single place the close policy lives: closing the last window hides it (and the
     * app keeps running) only when there is a tray to come back from AND the user asked
     * for close-to-tray. Anything else either quits on close when it shouldn't or leaves
     * an invisible process behind.
     */
    private void updateImplicitExit() {
        window.setImplicitExit(!closeHidesWindow());
    }

    private void push(TrayDisplay display, String tooltip) {
        if (tray == null || (display.equals(lastDisplay) && tooltip.equals(lastTooltip))) {
            return;
        }
        lastDisplay = display;
        lastTooltip = tooltip;
        tray.update(display, tooltip);
    }

    private TrayMetric metric() {
        return TrayMetric.from(config.get().trayMetric());
    }
}
