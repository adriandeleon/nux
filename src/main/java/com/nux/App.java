package com.nux;

import static com.nux.i18n.Messages.tr;

import java.io.IOException;
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
import javafx.scene.control.ButtonType;
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
import com.nux.analysis.TestSchedule;
import com.nux.autostart.Autostart;
import com.nux.command.CommandRegistry;
import com.nux.config.Advanced;
import com.nux.config.Config;
import com.nux.config.ConfigStore;
import com.nux.config.Profile;
import com.nux.i18n.Messages;
import com.nux.instance.SingleInstance;
import com.nux.protocol.StatusTransitions;
import com.nux.protocol.UpsDevice;
import com.nux.protocol.UpsSnapshot;
import com.nux.protocol.UpsStatus;
import com.nux.service.Notifier;
import com.nux.service.PollService;
import com.nux.store.EventLog;
import com.nux.store.HistorySample;
import com.nux.store.HistoryStore;
import com.nux.store.UpsEvent;
import com.nux.tray.NuxTray;
import com.nux.tray.TrayDisplay;
import com.nux.tray.TrayMetric;
import com.nux.tray.Trays;
import com.nux.update.ReleaseInfo;
import com.nux.update.UpdateCheck;
import com.nux.update.UpdateService;
import com.nux.ui.AboutWindow;
import com.nux.ui.CommandPalette;
import com.nux.ui.CredentialsDialog;
import com.nux.ui.Dashboard;
import com.nux.ui.DiscoveryWindow;
import com.nux.ui.EventsWindow;
import com.nux.ui.HistoryWindow;
import com.nux.ui.Screenshot;
import com.nux.ui.SettingsWindow;
import com.nux.ui.VariablesWindow;

/** Nux — a simple, powerful NUT (Network UPS Tools) client. */
public final class App extends Application {

    private static final System.Logger LOG = System.getLogger(App.class.getName());

    private final Path configPath = ConfigStore.DEFAULT_PATH;
    private Config config = Config.defaults();

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
    private boolean running;
    private boolean switchingUps; // guards the combo listener during programmatic repopulation
    private UpsStatus lastStatus; // previous poll's status, for transition alerts
    private UpsSnapshot lastSnapshot; // latest data, for tray refresh on settings changes
    private boolean commLost; // a "lost contact" notification was sent and not yet cleared
    private String authUser; // session-cached upsd credentials for INSTCMD
    private String authPass;
    private NuxTray tray;
    private int trayGeneration; // FX thread; a tray init whose generation is stale disposes its icon
    // Claimed in main(), before the toolkit exists; App only wires the window to it.
    private static SingleInstance singleInstance;
    private TrayDisplay lastTrayDisplay;
    private String lastTrayTooltip;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        config = ConfigStore.load(configPath);
        // Only honour --minimized when there is a tray to minimise to, or the app would
        // start invisible with no way to reach it.
        startMinimized = getParameters().getRaw().contains("--minimized") && config.tray();
        history = new HistoryStore(configPath.getParent(), config.advanced().historyDays());
        events = new EventLog(configPath.getParent());
        upsCombo.setPromptText("UPS");
        upsCombo.setDisable(true);
        commandsButton.setDisable(true);
        connLabel.getStyleClass().add("conn-label");
        refreshConnLabel();
        rebuildProfilesMenu();

        connectButton.setOnAction(e -> toggleConnect());
        upsCombo.valueProperty().addListener((obs, old, selected) -> {
            if (!switchingUps && running && selected != null) {
                startFresh(selected);
                saveConfig(config.withLast(config.lastHost(), config.lastPort(), selected, config.lastTls()));
            }
        });
        Button settingsButton = new Button(tr("toolbar.settings"));
        settingsButton.setOnAction(e -> openSettings());
        Button aboutButton = new Button("?");
        aboutButton.setTooltip(new javafx.scene.control.Tooltip(tr("toolbar.about", AppInfo.NAME)));
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

        wirePollService();
        poll.setOnOverview(all -> dashboard.setOverview(all, upsCombo.getValue(), upsCombo::setValue));
        palette = new CommandPalette(root, registry::all);
        registerCommands();

        Scene scene = new Scene(root, 960, 760);
        scene.getStylesheets().add(App.class.getResource("/com/nux/nux.css").toExternalForm());
        scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+Shift+P"), palette::toggle);
        scene.getAccelerators().put(KeyCombination.keyCombination("Shortcut+Comma"), this::openSettings);
        stage.setScene(scene);
        stage.setTitle("Nux");
        for (int size : new int[] {16, 32, 64, 128, 256}) {
            var icon = App.class.getResource("/com/nux/icons/nux-" + size + ".png");
            if (icon != null) {
                stage.getIcons().add(new Image(icon.toExternalForm()));
            }
        }
        // With a tray, closing hides; without one (or with closeToTray off), close quits.
        stage.setOnCloseRequest(e -> {
            if (tray != null && config.closeToTray()) {
                e.consume();
                stage.hide();
            }
        });
        if (!startMinimized) {
            stage.show();
        }

        initTray();
        claimSingleInstance();
        if (config.autoConnect() && !config.lastHost().isBlank()) {
            connect();
        }
        pruneHistory();
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

    /** Applies the retention window once per launch, off the FX thread. */
    private void pruneHistory() {
        String ups = config.lastUps();
        if (ups == null || ups.isBlank()) {
            return;
        }
        Thread pruner = new Thread(() -> history.prune(ups), "nux-history-prune");
        pruner.setDaemon(true);
        pruner.start();
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
        // Tray backends leave non-daemon threads behind (AWT EDT, D-Bus workers); with
        // implicit exit disabled, make process death unconditional once FX shuts down.
        System.exit(0);
    }

    private void wirePollService() {
        poll.setOnConnected(this::onConnected);
        poll.setOnSnapshot(this::onSnapshot);
        poll.setOnCommands(this::onCommands);
        poll.setOnDisconnected(this::onDisconnected);
    }

    // --- Commands (palette) ---------------------------------------------------

    private void registerCommands() {
        registry.register("app.connect", tr("command.connect"), this::toggleConnect);
        registry.register("app.settings", tr("command.settings"), this::openSettings);
        registry.register("app.saveProfile", tr("command.saveProfile"), this::saveCurrentAsProfile);
        registry.register("app.history", tr("command.history"), this::openHistory);
        registry.register("app.events", tr("command.events"), this::openEvents);
        registry.register("app.variables", tr("command.variables"), this::openVariables);
        registry.register("app.selfTest", tr("command.selfTest"), this::runSelfTestNow);
        registry.register("app.discover", tr("command.discover"), this::openDiscovery);
        registry.register("app.checkUpdates", tr("command.checkUpdates"), this::checkForUpdatesNow);
        if (Autostart.isSupported()) {
            registry.register("app.autostart", "Toggle start at login", () -> {
                boolean now = !Autostart.isEnabled();
                Autostart.setEnabled(now, config.tray());
            });
        }
        registry.register("app.about", tr("command.about"), this::openAbout);
        registry.register("app.quit", tr("command.quit"), Platform::exit);
        for (TrayMetric metric : TrayMetric.values()) {
            registry.register(
                    "tray.metric." + metric.key(), "Tray: show " + metric.label(), () -> setTrayMetric(metric));
        }
        refreshProfileCommands();
    }

    private void refreshProfileCommands() {
        registry.removeByPrefix("profile.connect.");
        for (Profile profile : config.profiles()) {
            registry.register(
                    "profile.connect." + profile.name(),
                    "Connect to profile: " + profile.name(),
                    () -> connectProfile(profile));
        }
    }

    private void setTrayMetric(TrayMetric metric) {
        saveConfig(config.withTrayMetric(metric.key()));
        refreshTray();
    }

    // --- Connection lifecycle -------------------------------------------------

    private void toggleConnect() {
        if (running) {
            disconnect();
        } else {
            connect();
        }
    }

    private void connect() {
        running = true;
        connectButton.setText(tr("toolbar.disconnect"));
        String ups = upsCombo.getValue() != null
                ? upsCombo.getValue()
                : (config.lastUps().isBlank() ? null : config.lastUps());
        poll.start(config.lastHost(), config.lastPort(), ups, config.tlsModeFor(config.lastTls()));
    }

    private void disconnect() {
        running = false;
        poll.stop();
        connectButton.setText(tr("toolbar.connect"));
        upsCombo.setDisable(true);
        commandsButton.setDisable(true);
        dashboard.showDisconnected("Stopped");
        lastSnapshot = null;
        commLost = false;
        refreshTray();
        saveConfig(config.withAutoConnect(false));
    }

    /** Restart polling against a (possibly) different target: history and alert state reset. */
    private void startFresh(String ups) {
        lastStatus = null;
        lastSnapshot = null;
        commLost = false;
        commandsButton.setDisable(true);
        dashboard.resetHistory();
        poll.start(config.lastHost(), config.lastPort(), ups, config.tlsModeFor(config.lastTls()));
    }

    private void onConnected(List<UpsDevice> devices) {
        switchingUps = true;
        try {
            String preferred = upsCombo.getValue() != null ? upsCombo.getValue() : config.lastUps();
            upsCombo.getItems().setAll(devices.stream().map(UpsDevice::name).toList());
            if (preferred != null && upsCombo.getItems().contains(preferred)) {
                upsCombo.setValue(preferred);
            } else if (!upsCombo.getItems().isEmpty()) {
                upsCombo.setValue(upsCombo.getItems().getFirst());
            }
        } finally {
            switchingUps = false;
        }
        upsCombo.setDisable(upsCombo.getItems().isEmpty());
        if (commLost) {
            notifyUser("Nux", tr("alert.contactRestored"), false);
            commLost = false;
        }
        saveConfig(config.withLast(config.lastHost(), config.lastPort(), upsCombo.getValue(), config.lastTls())
                .withAutoConnect(true));
    }

    private void onSnapshot(UpsSnapshot snapshot) {
        dashboard.update(snapshot);
        varRows.setAll(snapshot.vars().entrySet());
        lastSnapshot = snapshot;
        history.record(snapshot.ups(), HistorySample.of(snapshot));
        updateAnalysis(snapshot);

        UpsStatus status = snapshot.status();
        StatusTransitions.Alert alert = StatusTransitions.alert(lastStatus, status);
        if (alert != null) {
            // The alert carries catalogue keys; resolve them once, here at the edge.
            String title = tr(alert.titleKey());
            String body = tr(alert.bodyKey());
            notifyUser(title, body, alert.critical());
            events.append(UpsEvent.now(snapshot.ups(), alert.critical(), title, body));
        }
        lastStatus = status;
        pushTray(TrayDisplay.of(snapshot, TrayMetric.from(config.trayMetric())), TrayDisplay.tooltip(snapshot));
        maybeRunScheduledTest(snapshot.ups());
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
            ageText = ageText.isEmpty() ? "replace" : ageText + " — replace";
        } else if (health == BatteryHealth.Status.AGING) {
            ageText = ageText + " — aging";
        }
        dashboard.setBatteryAge(ageText, health == BatteryHealth.Status.AGING || health == BatteryHealth.Status.REPLACE);
        dashboard.setPower(EnergyEstimate.watts(snapshot, config.advanced().nominalWatts())
                .map(w -> String.format("%.0f W", w))
                .orElse("—"));
    }

    /**
     * Fires the scheduled self-test when it comes due. Credentials are session-only by
     * design, so a schedule can only run once the user has authenticated at least once;
     * lastRun is stamped *before* the command so a failing test can't retry every tick.
     */
    private void maybeRunScheduledTest(String ups) {
        Advanced adv = config.advanced();
        if (!adv.selfTestEnabled() || authPass == null || authUser == null) {
            return;
        }
        if (!TestSchedule.isDue(
                adv.selfTestLastRun(),
                adv.selfTestIntervalDays(),
                TestSchedule.parseTime(adv.selfTestTime()),
                ZoneId.systemDefault(),
                Instant.now())) {
            return;
        }
        saveConfig(config.withAdvanced(adv.withSelfTestLastRun(Instant.now().getEpochSecond())));
        poll.runInstCmd(ups, authUser, authPass, adv.selfTestCommand(), (ok, message) -> events.append(
                UpsEvent.now(
                        ups,
                        !ok,
                        ok ? tr("alert.selfTestStarted") : tr("alert.selfTestFailed"),
                        ok ? adv.selfTestCommand() : friendlyCommandError(message))));
    }

    private void runSelfTestNow() {
        String ups = upsCombo.getValue();
        if (ups != null) {
            runCommand(config.advanced().selfTestCommand());
        }
    }

    private void onCommands(List<String> commands) {
        commandsButton.getItems().clear();
        registry.removeByPrefix("ups.cmd.");
        for (String command : commands) {
            MenuItem item = new MenuItem(command);
            item.setOnAction(e -> runCommand(command));
            commandsButton.getItems().add(item);
            registry.register("ups.cmd." + command, "UPS command: " + command, () -> runCommand(command));
        }
        commandsButton.setDisable(commands.isEmpty());
    }

    private void onDisconnected(String reason) {
        dashboard.showDisconnected(reason);
        commandsButton.setDisable(true);
        lastSnapshot = null;
        pushTray(TrayDisplay.disconnected(), tr("tray.disconnected"));
        if (running && !commLost) {
            commLost = true;
            notifyUser("Nux", tr("alert.contactLost", reason), false);
            events.append(UpsEvent.now(upsLabel(), false, "Lost server contact", reason));
        }
    }

    private String upsLabel() {
        return upsCombo.getValue() != null ? upsCombo.getValue() : config.lastHost();
    }

    // --- Feature windows ------------------------------------------------------

    private void openHistory() {
        String ups = upsCombo.getValue() != null ? upsCombo.getValue() : config.lastUps();
        if (ups == null || ups.isBlank()) {
            return;
        }
        double watts = lastSnapshot != null
                ? EnergyEstimate.nominalWatts(lastSnapshot, config.advanced().nominalWatts()).orElse(0.0)
                : config.advanced().nominalWatts();
        HistoryWindow.show(stage, history, ups, config.advanced(), watts);
    }

    // --- update check ---------------------------------------------------------

    /**
     * Runs the background check when enabled and due. The timestamp is stamped
     * <em>before</em> the request, so a failing check throttles itself instead of
     * retrying on every launch all day.
     */
    private void maybeCheckForUpdates() {
        Advanced adv = config.advanced();
        if (!adv.updateCheck()
                || !UpdateCheck.isDue(adv.lastUpdateCheckMs(), System.currentTimeMillis(), UpdateCheck.DEFAULT_INTERVAL_MS)) {
            return;
        }
        saveConfig(config.withAdvanced(
                adv.withUpdateState(System.currentTimeMillis(), adv.dismissedUpdateVersion())));
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
                && !info.version().equals(config.advanced().dismissedUpdateVersion());
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
            saveConfig(config.withAdvanced(
                    config.advanced().withUpdateState(config.advanced().lastUpdateCheckMs(), info.version())));
        }
        refreshUpdateNotice();
    }

    private void info(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
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

    /** Picking a discovered server points the connection at it and reconnects if running. */
    private void openDiscovery() {
        DiscoveryWindow.show(stage, (host, port) -> {
            saveConfig(config.withLast(host, port, "", config.lastTls()));
            refreshConnLabel();
            switchingUps = true;
            try {
                upsCombo.getItems().clear();
                upsCombo.setValue(null);
            } finally {
                switchingUps = false;
            }
            if (running) {
                startFresh(null);
            } else {
                connect();
            }
        });
    }

    private void openVariables() {
        String ups = upsCombo.getValue();
        if (ups == null) {
            return;
        }
        poll.loadRwVariables(ups, variables -> {
            if (variables.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle(tr("variables.noneTitle"));
                alert.setHeaderText(tr("variables.noneHeader"));
                alert.setContentText(tr("variables.noneBody"));
                alert.show();
                return;
            }
            VariablesWindow.show(
                    stage, ups, variables, this::setVariable, onLoaded -> poll.loadRwVariables(ups, onLoaded));
        });
    }

    /** Applies a writable variable, prompting for credentials the same way commands do. */
    private void setVariable(String name, String value, java.util.function.BiConsumer<Boolean, String> onResult) {
        String ups = upsCombo.getValue();
        if (ups == null) {
            onResult.accept(false, tr("error.notConnected"));
            return;
        }
        if (authPass == null) {
            Optional<CredentialsDialog.Credentials> creds = CredentialsDialog.show(
                    tr("dialog.credentials.setvar"), authUser != null ? authUser : "");
            if (creds.isEmpty() || creds.get().user().isEmpty()) {
                onResult.accept(false, tr("error.cancelled"));
                return;
            }
            authUser = creds.get().user();
            authPass = creds.get().password();
        }
        poll.setVariable(ups, authUser, authPass, name, value, (ok, message) -> {
            if (!ok) {
                authPass = null; // could be bad credentials; re-prompt next time
            } else {
                events.append(UpsEvent.now(ups, false, tr("alert.variableChanged"), name + " = " + value));
            }
            onResult.accept(ok, ok ? message : friendlyCommandError(message));
        });
    }

    private void notifyUser(String title, String message, boolean critical) {
        if (config.notifications()) {
            Notifier.send(title, message, critical);
        }
    }

    // --- Settings -------------------------------------------------------------

    private void openSettings() {
        SettingsWindow.show(stage, config, this::applySettings);
    }

    private void applySettings(Config updated) {
        boolean connectionChanged = !updated.lastHost().equals(config.lastHost())
                || updated.lastPort() != config.lastPort()
                || updated.lastTls() != config.lastTls();
        boolean trayToggled = updated.tray() != config.tray();
        saveConfig(updated);
        refreshConnLabel();
        if (trayToggled) {
            if (config.tray()) {
                initTray();
            } else {
                trayGeneration++; // an init still in flight must not install its icon
                if (tray != null) {
                    tray.dispose();
                    tray = null;
                }
                if (!stage.isShowing()) {
                    stage.show(); // nothing left to bring it back from
                }
            }
        }
        updateImplicitExit(); // closeToTray can change without the tray toggling
        refreshTray();
        if (running && connectionChanged) {
            startFresh(upsCombo.getValue());
        }
    }

    private void refreshConnLabel() {
        connLabel.setText(config.lastHost() + ":" + config.lastPort() + (config.lastTls() ? "  (TLS)" : ""));
    }

    // --- Tray icon ------------------------------------------------------------

    /**
     * Backend creation touches D-Bus/AWT and may block briefly, so it runs off the FX
     * thread. It always posts an outcome, even when the backend throws something
     * unexpected: a {@code --minimized} start waits on it to decide whether to show.
     */
    private void initTray() {
        if (!config.tray()) {
            return;
        }
        int generation = ++trayGeneration;
        Runnable activate = this::raiseWindow;
        Runnable quit = () -> Platform.runLater(Platform::exit);
        Thread init = new Thread(
                () -> {
                    Optional<NuxTray> created;
                    try {
                        created = Trays.create(activate, quit);
                    } catch (Throwable t) {
                        LOG.log(System.Logger.Level.WARNING, "Tray initialisation failed", t);
                        created = Optional.empty();
                    }
                    Optional<NuxTray> outcome = created;
                    Platform.runLater(() -> onTrayCreated(generation, outcome));
                },
                "nux-tray-init");
        init.setDaemon(true);
        init.start();
    }

    private void onTrayCreated(int generation, Optional<NuxTray> created) {
        if (generation != trayGeneration || !config.tray()) {
            // Toggled off (or off and on again) while starting: a newer init, if any,
            // owns the tray now. Without this two inits could both assign it.
            created.ifPresent(NuxTray::dispose);
            return;
        }
        if (created.isEmpty()) {
            // No tray after all: a window started minimised would be unreachable, so
            // bring it up rather than hiding forever.
            if (!stage.isShowing()) {
                stage.show();
            }
            updateImplicitExit();
            return;
        }
        if (tray != null) {
            tray.dispose();
        }
        tray = created.get();
        updateImplicitExit();
        refreshTray();
    }

    /**
     * The single place the close policy lives: closing the last window hides it (and the
     * app keeps running) only when there is a tray to come back from AND the user asked
     * for close-to-tray. Re-run whenever either changes; anything else either quits on
     * close when it shouldn't or leaves an invisible process behind.
     */
    private void updateImplicitExit() {
        Platform.setImplicitExit(!(tray != null && config.closeToTray()));
    }

    /** Recomputes the tray from the latest data (metric/settings changes bypass the dedupe). */
    private void refreshTray() {
        lastTrayDisplay = null;
        lastTrayTooltip = null;
        if (lastSnapshot != null) {
            pushTray(
                    TrayDisplay.of(lastSnapshot, TrayMetric.from(config.trayMetric())),
                    TrayDisplay.tooltip(lastSnapshot));
        } else {
            pushTray(TrayDisplay.disconnected(), running ? tr("tray.disconnected") : tr("tray.notConnected"));
        }
    }

    private void pushTray(TrayDisplay display, String tooltip) {
        if (tray == null || (display.equals(lastTrayDisplay) && tooltip.equals(lastTrayTooltip))) {
            return;
        }
        lastTrayDisplay = display;
        lastTrayTooltip = tooltip;
        tray.update(display, tooltip);
    }

    // --- Instant commands -----------------------------------------------------

    private void runCommand(String command) {
        String ups = upsCombo.getValue();
        if (ups == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(tr("dialog.runCommand.title"));
        confirm.setHeaderText(tr("dialog.runCommand.header", command, ups));
        confirm.setContentText(tr("dialog.runCommand.body"));
        if (confirm.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) {
            return;
        }
        if (authPass == null) {
            Optional<CredentialsDialog.Credentials> creds = CredentialsDialog.show(
                    tr("dialog.credentials.instcmd"), authUser != null ? authUser : "");
            if (creds.isEmpty() || creds.get().user().isEmpty()) {
                return;
            }
            authUser = creds.get().user();
            authPass = creds.get().password();
        }
        poll.runInstCmd(ups, authUser, authPass, command, (ok, message) -> {
            if (ok) {
                notifyUser("Nux", tr("alert.commandSent", command, ups), false);
            } else {
                authPass = null; // could be bad credentials; re-prompt next time
                Alert error = new Alert(Alert.AlertType.ERROR);
                error.setTitle(tr("dialog.commandFailed.title"));
                error.setHeaderText(tr("dialog.commandFailed.header", command));
                error.setContentText(friendlyCommandError(message));
                error.show();
            }
        });
    }

    private static String friendlyCommandError(String message) {
        return switch (message) {
            case "ACCESS-DENIED" -> tr("error.accessDenied");
            case "USERNAME-REQUIRED", "PASSWORD-REQUIRED" -> tr("error.authRequired");
            case "CMD-NOT-SUPPORTED" -> tr("error.notSupported");
            default -> message;
        };
    }

    // --- Profiles -------------------------------------------------------------

    private void rebuildProfilesMenu() {
        profilesButton.getItems().clear();
        for (Profile profile : config.profiles()) {
            MenuItem item = new MenuItem(profileLabel(profile));
            item.setOnAction(e -> connectProfile(profile));
            profilesButton.getItems().add(item);
        }
        if (!config.profiles().isEmpty()) {
            profilesButton.getItems().add(new SeparatorMenuItem());
        }
        MenuItem save = new MenuItem(tr("dialog.saveCurrentAs"));
        save.setOnAction(e -> saveCurrentAsProfile());
        profilesButton.getItems().add(save);
        if (!config.profiles().isEmpty()) {
            Menu delete = new Menu(tr("dialog.deleteProfile"));
            for (Profile profile : config.profiles()) {
                MenuItem item = new MenuItem(profile.name());
                item.setOnAction(e -> {
                    saveConfig(config.withProfileRemoved(profile.name()));
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

    private void connectProfile(Profile profile) {
        switchingUps = true;
        try {
            upsCombo.getItems().clear(); // the new server announces its own UPS list on connect
            upsCombo.setValue(null);
        } finally {
            switchingUps = false;
        }
        saveConfig(config.withLast(profile.host(), profile.port(), profile.ups(), profile.tls()));
        refreshConnLabel();
        running = true;
        connectButton.setText(tr("toolbar.disconnect"));
        startFresh(profile.ups().isBlank() ? null : profile.ups());
    }

    private void saveCurrentAsProfile() {
        TextInputDialog dialog = new TextInputDialog(config.lastHost());
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
        String ups = upsCombo.getValue() != null ? upsCombo.getValue() : "";
        Profile profile = new Profile(name, config.lastHost(), config.lastPort(), ups, config.lastTls());
        saveConfig(config.withProfileSaved(profile));
        rebuildProfilesMenu();
        refreshProfileCommands();
    }

    // --- Helpers --------------------------------------------------------------

    private void saveConfig(Config updated) {
        config = updated;
        try {
            ConfigStore.save(configPath, config);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not save config to " + configPath, e);
        }
    }

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
