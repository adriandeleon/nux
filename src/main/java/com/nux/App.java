package com.nux;

import static com.nux.i18n.Messages.tr;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToolBar;
import javafx.scene.image.Image;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import atlantafx.base.theme.PrimerDark;

import com.nux.analysis.BatteryHealth;
import com.nux.analysis.EnergyEstimate;
import com.nux.app.CommandRunner;
import com.nux.app.ConnectionController;
import com.nux.app.TrayController;
import com.nux.autostart.Autostart;
import com.nux.command.CommandRegistry;
import com.nux.config.Advanced;
import com.nux.config.Config;
import com.nux.config.ConfigHolder;
import com.nux.config.ConfigStore;
import com.nux.config.Profile;
import com.nux.i18n.Messages;
import com.nux.instance.SingleInstance;
import com.nux.protocol.UpsSnapshot;
import com.nux.service.ContactTracker;
import com.nux.service.Notifier;
import com.nux.service.PollService;
import com.nux.store.EventLog;
import com.nux.store.HistoryStore;
import com.nux.tray.TrayMetric;
import com.nux.tray.Trays;
import com.nux.update.ReleaseInfo;
import com.nux.update.UpdateCheck;
import com.nux.update.UpdateService;
import com.nux.ui.AboutWindow;
import com.nux.ui.CommandConfirmation;
import com.nux.ui.CommandPalette;
import com.nux.ui.CredentialsDialog;
import com.nux.ui.Dashboard;
import com.nux.ui.DiscoveryWindow;
import com.nux.ui.EventsWindow;
import com.nux.ui.HistoryWindow;
import com.nux.ui.Money;
import com.nux.ui.Screenshot;
import com.nux.ui.SettingsWindow;
import com.nux.ui.VariablesWindow;

/** Nux — a simple, powerful NUT (Network UPS Tools) client. */
public final class App extends Application {

    private static final System.Logger LOG = System.getLogger(App.class.getName());

    private final Path configPath = ConfigStore.DEFAULT_PATH;
    private ConfigHolder configHolder;

    private final PollService poll = new PollService();
    private final Dashboard dashboard = new Dashboard();
    private final CommandRegistry registry = new CommandRegistry();
    private final UpdateService updateService = new UpdateService();
    /** The newest release found this session, or null when up to date / not checked. */
    private ReleaseInfo latestUpdate;
    private final Button updateButton = new Button();
    private HistoryStore history;
    private EventLog events;
    private final ObservableList<Map.Entry<String, String>> varRows = FXCollections.observableArrayList();

    private final MenuButton profilesButton = new MenuButton(tr("toolbar.profiles"));
    private final ComboBox<String> upsCombo = new ComboBox<>();
    private final Button connectButton = new Button(tr("toolbar.connect"));
    private final MenuButton commandsButton = new MenuButton(tr("toolbar.commands"));
    private final Label connLabel = new Label();

    private Stage stage;
    private CommandPalette palette;
    private boolean startMinimized; // --minimized: come up in the tray, not on screen
    private boolean switchingUps; // guards the combo listener during programmatic repopulation
    // The tested, toolkit-free halves of the app; App adapts them to the JavaFX UI.
    private ConnectionController connection;
    private CommandRunner commands;
    private TrayController tray;
    // Claimed in main(), before the toolkit exists; App only wires the window to it.
    private static SingleInstance singleInstance;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        configHolder = ConfigHolder.load(configPath);
        // Only honour --minimized when there is a tray to minimise to, or the app would
        // start invisible with no way to reach it.
        startMinimized = getParameters().getRaw().contains("--minimized") && config().tray();
        history = new HistoryStore(configPath.getParent(), config().advanced().historyDays());
        events = new EventLog(configPath.getParent());
        connection = new ConnectionController(
                configHolder, poll, history, events, new ConnectionView(), Notifier::send, Instant::now);
        commands = new CommandRunner(
                configHolder, poll, connection, events, new DialogPrompts(), Notifier::send, Instant::now,
                ZoneId.systemDefault());
        connection.setAfterSnapshot(commands::maybeRunScheduledTest);
        tray = new TrayController(
                configHolder,
                () -> Trays.create(this::raiseWindow, () -> Platform.runLater(Platform::exit)),
                work -> {
                    // Backend creation touches D-Bus/AWT and may block briefly.
                    Thread init = new Thread(work, "nux-tray-init");
                    init.setDaemon(true);
                    init.start();
                },
                Platform::runLater,
                new MainWindow());

        upsCombo.setPromptText(tr("toolbar.ups"));
        upsCombo.setDisable(true);
        commandsButton.setDisable(true);
        connLabel.getStyleClass().add("conn-label");
        connection.showServer();
        rebuildProfilesMenu();

        connectButton.setOnAction(e -> connection.toggle());
        upsCombo.valueProperty().addListener((obs, old, selected) -> {
            if (!switchingUps && selected != null) {
                connection.switchUps(selected);
            }
        });
        Button settingsButton = new Button(tr("toolbar.settings"));
        settingsButton.setOnAction(e -> openSettings());
        Button aboutButton = new Button("?");
        aboutButton.setTooltip(new javafx.scene.control.Tooltip(tr("toolbar.about", AppInfo.NAME)));
        aboutButton.setAccessibleText(tr("toolbar.about", AppInfo.NAME)); // "?" means nothing read aloud
        aboutButton.setOnAction(e -> openAbout());
        updateButton.getStyleClass().add("update-badge");
        updateButton.setOnAction(e -> openUpdateDownloadPage());
        refreshUpdateNotice();

        Button historyButton = new Button(tr("toolbar.history"));
        historyButton.setOnAction(e -> openHistory());
        Button eventsButton = new Button(tr("toolbar.events"));
        eventsButton.setOnAction(e -> openEvents());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        ToolBar toolbar = new ToolBar(
                profilesButton,
                new Label(tr("toolbar.ups")), upsCombo,
                connectButton,
                commandsButton,
                historyButton,
                eventsButton,
                spacer,
                updateButton,
                connLabel,
                settingsButton,
                aboutButton);

        SplitPane split = new SplitPane(dashboard, new TitledPane(tr("dashboard.allVariables"), buildVarTable()));
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.68);

        BorderPane content = new BorderPane();
        content.setTop(toolbar);
        content.setCenter(split);
        StackPane root = new StackPane(content); // StackPane so the palette can overlay in-scene

        palette = new CommandPalette(root, registry::all);
        registerCommands();

        Scene scene = new Scene(root, 960, 720);
        scene.getStylesheets().add(App.class.getResource("/com/nux/nux.css").toExternalForm());
        // Keyboard access to the whole toolbar. Avoids Cmd+H / Cmd+M, which macOS
        // reserves for Hide and Minimize; each button's tooltip shows its shortcut.
        shortcut(scene, "Shortcut+Shift+P", palette::toggle, null);
        shortcut(scene, "Shortcut+Comma", this::openSettings, settingsButton);
        shortcut(scene, "Shortcut+K", () -> connection.toggle(), connectButton);
        shortcut(scene, "Shortcut+U", () -> {
            if (!upsCombo.isDisabled()) {
                upsCombo.requestFocus();
                upsCombo.show();
            }
        }, upsCombo);
        shortcut(scene, "Shortcut+Shift+C", () -> {
            if (!commandsButton.isDisabled()) {
                commandsButton.show();
            }
        }, commandsButton);
        shortcut(scene, "Shortcut+Shift+H", this::openHistory, historyButton);
        shortcut(scene, "Shortcut+Shift+E", this::openEvents, eventsButton);
        shortcut(scene, "F1", this::openAbout, aboutButton);
        stage.setScene(scene);
        // The gauges are fixed-size canvases (~620 px across); below this they clip.
        stage.setMinWidth(720);
        stage.setMinHeight(560);
        stage.setTitle("Nux");
        for (int size : new int[] {16, 32, 64, 128, 256}) {
            var icon = App.class.getResource("/com/nux/icons/nux-" + size + ".png");
            if (icon != null) {
                stage.getIcons().add(new Image(icon.toExternalForm()));
            }
        }
        // With a tray, closing hides; without one (or with closeToTray off), close quits.
        stage.setOnCloseRequest(e -> {
            if (tray.closeHidesWindow()) {
                e.consume();
                stage.hide();
            }
        });
        if (!startMinimized) {
            stage.show();
        }

        tray.start();
        claimSingleInstance();
        if (config().autoConnect() && !config().lastHost().isBlank()) {
            connection.connect();
        }
        history.schedulePruning();
        maybeCaptureScreenshot();
        maybeCheckForUpdates();
    }

    /**
     * {@code --screenshot=<file>} saves the window as a PNG once the first poll has had
     * time to populate it, then keeps running. Used for the README and bug reports.
     */
    private void maybeCaptureScreenshot() {
        getParameters().getRaw().stream()
                .filter(a -> a.startsWith("--screenshot="))
                .map(a -> a.substring("--screenshot=".length()))
                .findFirst()
                .ifPresent(target -> {
                    javafx.animation.PauseTransition delay =
                            new javafx.animation.PauseTransition(javafx.util.Duration.seconds(6));
                    delay.setOnFinished(e -> {
                        boolean ok = Screenshot.save(stage.getScene(), Path.of(target));
                        LOG.log(
                                ok ? System.Logger.Level.INFO : System.Logger.Level.WARNING,
                                (ok ? "Wrote screenshot to " : "Could not write screenshot to ") + target);
                    });
                    delay.play();
                });
    }

    /** Brings the window up from the tray, another instance's request, or a minimised start. */
    private void raiseWindow() {
        Platform.runLater(() -> {
            stage.setIconified(false);
            stage.show();
            stage.toFront();
            stage.requestFocus();
        });
    }

    /**
     * Points the single-instance listener (claimed in {@link #main}) at this window, so a
     * later launch raises it instead of starting a rival that would poll the same UPS
     * twice. A request that arrived before the window existed is delivered now.
     */
    private void claimSingleInstance() {
        if (singleInstance != null) {
            singleInstance.setOnActivate(this::raiseWindow);
        }
    }

    @Override
    public void stop() {
        if (singleInstance != null) {
            singleInstance.close();
        }
        if (tray != null) {
            tray.dispose();
        }
        poll.shutdown();
        updateService.shutdown();
        history.shutdown();
        events.shutdown();
        configHolder.close(); // flush a save queued just before quitting
        // Tray backends leave non-daemon threads behind (AWT EDT, D-Bus workers); with
        // implicit exit disabled, make process death unconditional once FX shuts down.
        System.exit(0);
    }

    // --- Commands (palette) ---------------------------------------------------

    private void registerCommands() {
        registry.register("app.connect", tr("command.connect"), () -> connection.toggle());
        registry.register("app.settings", tr("command.settings"), this::openSettings);
        registry.register("app.saveProfile", tr("command.saveProfile"), this::saveCurrentAsProfile);
        registry.register("app.history", tr("command.history"), this::openHistory);
        registry.register("app.events", tr("command.events"), this::openEvents);
        registry.register("app.variables", tr("command.variables"), this::openVariables);
        registry.register("app.selfTest", tr("command.selfTest"), () -> commands.runSelfTestNow());
        registry.register("app.discover", tr("command.discover"), this::openDiscovery);
        registry.register("app.checkUpdates", tr("command.checkUpdates"), this::checkForUpdatesNow);
        if (Autostart.isSupported()) {
            registry.register("app.autostart", tr("command.autostart"), () -> {
                // reg.exe / launchctl: OS work, never on the FX thread.
                boolean minimized = config().tray();
                Thread toggle = new Thread(
                        () -> Autostart.setEnabled(!Autostart.isEnabled(), minimized), "nux-autostart");
                toggle.setDaemon(true);
                toggle.start();
            });
        }
        registry.register("app.about", tr("command.about"), this::openAbout);
        registry.register("app.quit", tr("command.quit"), Platform::exit);
        for (TrayMetric metric : TrayMetric.values()) {
            registry.register(
                    "tray.metric." + metric.key(), tr("command.trayMetric", metric.label()), () -> setTrayMetric(metric));
        }
        refreshProfileCommands();
    }

    private void refreshProfileCommands() {
        registry.removeByPrefix("profile.connect.");
        for (Profile profile : config().profiles()) {
            registry.register(
                    "profile.connect." + profile.name(),
                    tr("command.connectProfile", profile.name()),
                    () -> connection.connectProfile(profile));
        }
    }

    private void setTrayMetric(TrayMetric metric) {
        configHolder.update(c -> c.withTrayMetric(metric.key()));
        tray.refresh();
    }

    /**
     * Binds {@code combo} on the scene and appends it, in the platform's own notation
     * (Ctrl+K, ⌘K), to {@code control}'s tooltip so the shortcut can be discovered.
     */
    private static void shortcut(Scene scene, String combo, Runnable action, javafx.scene.control.Control control) {
        KeyCombination keys = KeyCombination.keyCombination(combo);
        scene.getAccelerators().put(keys, action);
        if (control == null) {
            return;
        }
        javafx.scene.control.Tooltip existing = control.getTooltip();
        String label = existing != null ? existing.getText()
                : control instanceof javafx.scene.control.Labeled l ? l.getText()
                : control.getAccessibleText() != null ? control.getAccessibleText() : "";
        control.setTooltip(new javafx.scene.control.Tooltip(
                (label == null || label.isBlank() ? "" : label + "  ") + "(" + keys.getDisplayText() + ")"));
    }

    private Config config() {
        return configHolder.get();
    }

    // --- Connection → UI ------------------------------------------------------

    /** How the connection's state reaches the toolbar, dashboard, variables table and tray. */
    private final class ConnectionView implements ConnectionController.View {
        @Override
        public void showRunning(boolean running) {
            connectButton.setText(running ? tr("toolbar.disconnect") : tr("toolbar.connect"));
            if (!running) {
                upsCombo.setDisable(true);
            }
        }

        @Override
        public void showUpsChoices(List<String> names, String selected) {
            switchingUps = true;
            try {
                upsCombo.getItems().setAll(names);
                upsCombo.setValue(selected);
            } finally {
                switchingUps = false;
            }
            upsCombo.setDisable(names.isEmpty());
        }

        @Override
        public void clearUpsChoices() {
            switchingUps = true;
            try {
                upsCombo.getItems().clear();
                upsCombo.setValue(null);
            } finally {
                switchingUps = false;
            }
        }

        @Override
        public void showServer(String host, int port, boolean tls) {
            connLabel.setText(host + ":" + port + (tls ? "  (TLS)" : ""));
        }

        @Override
        public void showSnapshot(UpsSnapshot snapshot) {
            dashboard.update(snapshot);
            varRows.setAll(snapshot.vars().entrySet());
            updateAnalysis(snapshot);
            dashboard.setOnBattery(onBatteryText(snapshot));
            tray.show(snapshot);
        }

        @Override
        public void showCommands(List<String> names) {
            commandsButton.getItems().clear();
            registry.removeByPrefix("ups.cmd.");
            for (String command : names) {
                MenuItem item = new MenuItem(command);
                item.setOnAction(e -> commands.run(command));
                commandsButton.getItems().add(item);
                registry.register("ups.cmd." + command, tr("command.upsCommand", command), () -> commands.run(command));
            }
            commandsButton.setDisable(names.isEmpty());
        }

        @Override
        public void showOverview(Map<String, UpsSnapshot> all, String selected) {
            dashboard.setOverview(all, selected, name -> {
                // A card click is a pick in the toolbar's UPS list, which then switches.
                upsCombo.setValue(name);
            });
        }

        @Override
        public void clearLiveData() {
            varRows.clear();
            commandsButton.getItems().clear();
            commandsButton.setDisable(true);
            registry.removeByPrefix("ups.cmd.");
        }

        @Override
        public void resetTrends() {
            dashboard.resetHistory();
        }

        @Override
        public void showDisconnected(String reason) {
            dashboard.showDisconnected(reason);
            tray.showIdle(true);
        }

        @Override
        public void showStopped() {
            dashboard.showStopped();
            tray.showIdle(false);
        }
    }

    /** The dialogs commands need, on the main window. */
    private final class DialogPrompts implements CommandRunner.Prompts {
        @Override
        public boolean confirm(String command, String ups) {
            Alert confirm = CommandConfirmation.create(command, ups);
            confirm.initOwner(stage);
            return CommandConfirmation.confirmed(confirm.showAndWait());
        }

        @Override
        public Optional<CommandRunner.Credentials> credentials(String header, String initialUser) {
            return CredentialsDialog.show(stage, header, initialUser)
                    .map(c -> new CommandRunner.Credentials(c.user(), c.password()));
        }

        @Override
        public void info(String title, String body) {
            App.this.info(title, body);
        }

        @Override
        public void commandFailed(String command, String reason) {
            Alert error = new Alert(Alert.AlertType.ERROR);
            error.initOwner(stage);
            error.setTitle(tr("dialog.commandFailed.title"));
            error.setHeaderText(tr("dialog.commandFailed.header", command));
            error.setContentText(reason);
            error.show();
        }
    }

    /** What the tray policy needs from the main window and the toolkit. */
    private final class MainWindow implements TrayController.Window {
        @Override
        public boolean isShowing() {
            return stage.isShowing();
        }

        @Override
        public void show() {
            stage.show();
        }

        @Override
        public void setImplicitExit(boolean exitOnLastClose) {
            Platform.setImplicitExit(exitOnLastClose);
        }
    }

    /**
     * "On battery for 3m 20s · about 25 min left at 31% load", or null on mains. The
     * runtime is the UPS's own estimate, which it makes at the load it is carrying now.
     */
    private String onBatteryText(UpsSnapshot snapshot) {
        Instant since = connection.onBatterySince();
        if (since == null) {
            return null;
        }
        String elapsed = ContactTracker.format(java.time.Duration.between(since, Instant.now()));
        String text = tr(connection.onBatterySinceExact() ? "dashboard.onBatteryFor" : "dashboard.onBatteryForAtLeast",
                elapsed);
        Optional<Double> runtime = snapshot.runtimeSeconds();
        if (runtime.isPresent()) {
            String left = String.format("%.0f min", runtime.get() / 60);
            text += " · " + snapshot.loadPercent()
                    .map(load -> tr("dashboard.runtimeLeftAtLoad", left, String.format("%.0f", load)))
                    .orElse(tr("dashboard.runtimeLeft", left));
        }
        return text;
    }

    /** Battery age/health and estimated power draw, derived from the latest snapshot. */
    private void updateAnalysis(UpsSnapshot snapshot) {
        LocalDate today = LocalDate.now();
        Optional<LocalDate> batteryDate = BatteryHealth.dateOf(snapshot);
        BatteryHealth.Status health =
                BatteryHealth.status(batteryDate, today, snapshot.status().replaceBattery());
        String ageText = batteryDate
                .map(d -> BatteryHealth.ageLabel(BatteryHealth.ageMonths(d, today)))
                .orElse(health == BatteryHealth.Status.REPLACE ? "" : "—");
        if (health == BatteryHealth.Status.REPLACE) {
            ageText = ageText.isEmpty() ? tr("dashboard.replaceNow") : tr("dashboard.replace", ageText);
        } else if (health == BatteryHealth.Status.AGING) {
            ageText = tr("dashboard.aging", ageText);
        }
        dashboard.setBatteryAge(ageText, health == BatteryHealth.Status.AGING || health == BatteryHealth.Status.REPLACE);
        Optional<Double> watts = EnergyEstimate.watts(snapshot, config().advanced().nominalWatts());
        dashboard.setPower(watts.map(w -> String.format("%.0f W", w)).orElse("—"));
        Advanced adv = config().advanced();
        if (watts.isEmpty()) {
            dashboard.setCost("—", false);
        } else if (adv.tariffPerKwh() <= 0) {
            dashboard.setCost(tr("dashboard.costUnset"), true);
        } else {
            // The steady-state cost of drawing what the UPS reports right now.
            double w = watts.get();
            double price = adv.tariffPerKwh();
            dashboard.setCost(
                    tr(
                            "dashboard.costRate",
                            Money.format(adv.currency(), EnergyEstimate.costPer(EnergyEstimate.Period.HOUR, w, price)),
                            Money.format(adv.currency(), EnergyEstimate.costPer(EnergyEstimate.Period.DAY, w, price)),
                            Money.format(adv.currency(), EnergyEstimate.costPer(EnergyEstimate.Period.WEEK, w, price)),
                            Money.format(adv.currency(), EnergyEstimate.costPer(EnergyEstimate.Period.MONTH, w, price))),
                    false);
        }
    }

    // --- Feature windows ------------------------------------------------------

    private void openHistory() {
        String ups = connection.selectedUps() != null ? connection.selectedUps() : config().lastUps();
        if (ups == null || ups.isBlank()) {
            return;
        }
        UpsSnapshot latest = connection.lastSnapshot();
        double watts = latest != null
                ? EnergyEstimate.nominalWatts(latest, config().advanced().nominalWatts()).orElse(0.0)
                : config().advanced().nominalWatts();
        HistoryWindow.show(stage, history, connection.serverKey(), ups, config().advanced(), watts);
    }

    // --- update check ---------------------------------------------------------

    /**
     * Runs the background check when enabled and due. The timestamp is stamped
     * <em>before</em> the request, so a failing check throttles itself instead of
     * retrying on every launch all day.
     */
    private void maybeCheckForUpdates() {
        Advanced adv = config().advanced();
        if (!adv.updateCheck()
                || !UpdateCheck.isDue(adv.lastUpdateCheckMs(), System.currentTimeMillis(), UpdateCheck.DEFAULT_INTERVAL_MS)) {
            return;
        }
        configHolder.update(c -> c.withAdvanced(
                c.advanced().withUpdateState(System.currentTimeMillis(), c.advanced().dismissedUpdateVersion())));
        updateService.check(AppInfo.VERSION, outcome -> onUpdateOutcome(outcome, false));
    }

    /** Manual check: ignores both the throttle and the auto-check setting, and always reports. */
    private void checkForUpdatesNow() {
        updateService.check(AppInfo.VERSION, outcome -> onUpdateOutcome(outcome, true));
    }

    private void onUpdateOutcome(UpdateService.Outcome outcome, boolean manual) {
        if (!outcome.ok()) {
            if (manual) {
                info(tr("update.failedTitle"), tr("update.failed", outcome.error()));
            }
            return;
        }
        latestUpdate = outcome.available() ? outcome.latest() : null;
        refreshUpdateNotice();
        if (manual && !outcome.available()) {
            info(tr("update.upToDateTitle"), tr("update.upToDate", AppInfo.VERSION));
        }
    }

    /** Shows the toolbar notice for a newer release the user hasn't already dismissed. */
    private void refreshUpdateNotice() {
        ReleaseInfo info = latestUpdate;
        boolean show = info != null
                && !info.version().equals(config().advanced().dismissedUpdateVersion());
        if (show) {
            updateButton.setText(tr("update.available", info.version()));
            updateButton.setTooltip(new javafx.scene.control.Tooltip(tr("update.tooltip")));
        }
        updateButton.setVisible(show);
        updateButton.setManaged(show);
    }

    /** Opens the release page and stops nagging about that version. */
    private void openUpdateDownloadPage() {
        ReleaseInfo info = latestUpdate;
        getHostServices().showDocument(info != null && !info.url().isBlank() ? info.url() : AppInfo.RELEASES_PAGE);
        if (info != null) {
            configHolder.update(c -> c.withAdvanced(
                    c.advanced().withUpdateState(c.advanced().lastUpdateCheckMs(), info.version())));
        }
        refreshUpdateNotice();
    }

    private void info(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.show();
    }

    private void openEvents() {
        EventsWindow.show(stage, events);
    }

    private void openAbout() {
        AboutWindow.show(
                stage,
                url -> getHostServices().showDocument(url),
                configPath.getParent().toString(),
                latestUpdate == null ? null : latestUpdate.version());
    }

    /** Picking a discovered server points the connection at it and reconnects. */
    private void openDiscovery() {
        DiscoveryWindow.show(stage, connection::pointAt);
    }

    private void openVariables() {
        String ups = connection.activeUps();
        if (ups == null) {
            info(tr("error.notConnected"), tr("error.notConnectedBody"));
            return;
        }
        poll.loadRwVariables(ups, variables -> {
            if (variables.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.initOwner(stage);
                alert.setTitle(tr("variables.noneTitle"));
                alert.setHeaderText(tr("variables.noneHeader"));
                alert.setContentText(tr("variables.noneBody"));
                alert.show();
                return;
            }
            VariablesWindow.show(
                    stage, ups, variables, commands::setVariable, onLoaded -> poll.loadRwVariables(ups, onLoaded));
        });
    }

    // --- Settings -------------------------------------------------------------

    private void openSettings() {
        SettingsWindow.show(stage, config(), this::applySettings);
    }

    private void applySettings(java.util.function.UnaryOperator<Config> edit) {
        Config before = config();
        long now = Instant.now().getEpochSecond();
        configHolder.update(c -> {
            Config edited = edit.apply(c);
            return edited.withAdvanced(edited.advanced().armSelfTest(c.advanced(), now));
        });
        history.setRetentionDays(config().advanced().historyDays());
        connection.settingsSaved(before);
        tray.settingsSaved(before);
    }

    // --- Profiles -------------------------------------------------------------

    private void rebuildProfilesMenu() {
        profilesButton.getItems().clear();
        for (Profile profile : config().profiles()) {
            MenuItem item = new MenuItem(profileLabel(profile));
            item.setOnAction(e -> connection.connectProfile(profile));
            profilesButton.getItems().add(item);
        }
        if (!config().profiles().isEmpty()) {
            profilesButton.getItems().add(new SeparatorMenuItem());
        }
        MenuItem save = new MenuItem(tr("dialog.saveCurrentAs"));
        save.setOnAction(e -> saveCurrentAsProfile());
        profilesButton.getItems().add(save);
        if (!config().profiles().isEmpty()) {
            Menu delete = new Menu(tr("dialog.deleteProfile"));
            for (Profile profile : config().profiles()) {
                MenuItem item = new MenuItem(profile.name());
                item.setOnAction(e -> {
                    configHolder.update(c -> c.withProfileRemoved(profile.name()));
                    rebuildProfilesMenu();
                    refreshProfileCommands();
                });
                delete.getItems().add(item);
            }
            profilesButton.getItems().add(delete);
        }
    }

    private static String profileLabel(Profile p) {
        String target = p.host() + ":" + p.port();
        String label = p.ups().isBlank() ? p.name() + "  (" + target + ")" : p.name() + "  (" + p.ups() + "@" + target + ")";
        return p.tls() ? label + " 🔒" : label;
    }

    private void saveCurrentAsProfile() {
        TextInputDialog dialog = new TextInputDialog(config().lastHost());
        dialog.initOwner(stage);
        dialog.setTitle(tr("dialog.saveProfile.title"));
        dialog.setHeaderText(tr("dialog.saveProfile.header"));
        dialog.setContentText(tr("dialog.saveProfile.name"));
        Optional<String> raw = dialog.showAndWait();
        if (raw.isEmpty()) {
            return;
        }
        String name = Profile.sanitizeName(raw.get());
        if (name.isEmpty()) {
            return;
        }
        String ups = connection.selectedUps() != null ? connection.selectedUps() : "";
        Config c = config();
        Profile profile = new Profile(name, c.lastHost(), c.lastPort(), ups, c.lastTls());
        configHolder.update(current -> current.withProfileSaved(profile));
        rebuildProfilesMenu();
        refreshProfileCommands();
    }

    // --- Helpers --------------------------------------------------------------

    private TableView<Map.Entry<String, String>> buildVarTable() {
        TableView<Map.Entry<String, String>> table = new TableView<>(varRows);
        TableColumn<Map.Entry<String, String>, String> name = new TableColumn<>(tr("dashboard.variable"));
        name.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(cd.getValue().getKey()));
        name.setPrefWidth(280);
        TableColumn<Map.Entry<String, String>, String> value = new TableColumn<>(tr("dashboard.value"));
        value.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(cd.getValue().getValue()));
        value.setPrefWidth(420);
        table.getColumns().add(name);
        table.getColumns().add(value);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label(tr("dashboard.connectPrompt")));
        return table;
    }

    /** Where the single-instance lock and port files live. */
    private static Path instanceDir() {
        return ConfigStore.DEFAULT_PATH.getParent();
    }

    public static void main(String[] args) {
        Config startup = ConfigStore.load(ConfigStore.DEFAULT_PATH);

        // The catalogue must be loaded here, not in start(): field initialisers call tr()
        // and they run when JavaFX constructs the Application, before start() is entered.
        // Locale.setDefault makes JavaFX's own dialog buttons (OK/Cancel) match; it keeps
        // the system region when the language agrees, so number formats stay the user's.
        Locale system = Locale.getDefault();
        String language =
                Messages.resolve(startup.advanced().uiLanguage(), Messages.available(), system.getLanguage());
        Messages.init(language);
        Locale.setDefault(Messages.uiLocale(language, system));

        // If Nux is already running, raise its window and stop before the toolkit starts;
        // otherwise claim the lock now, so a second quick launch can't also get through.
        // Failure to claim is not fatal: run regardless rather than leave the user nothing.
        if (startup.advanced().singleInstance()) {
            switch (SingleInstance.claim(instanceDir(), SingleInstance.HANDSHAKE_TIMEOUT_MS)) {
                case SingleInstance.Launch.AlreadyRunning _ -> System.exit(0);
                case SingleInstance.Launch.Primary primary -> singleInstance = primary.instance();
                case SingleInstance.Launch.Unguarded _ -> {}
            }
        }
        launchApp(args);
    }

    private static void launchApp(String[] args) {
        launch(args);
    }
}
