package com.nux;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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

import com.nux.command.CommandRegistry;
import com.nux.config.Config;
import com.nux.config.ConfigStore;
import com.nux.config.Profile;
import com.nux.protocol.StatusTransitions;
import com.nux.protocol.UpsDevice;
import com.nux.protocol.UpsSnapshot;
import com.nux.protocol.UpsStatus;
import com.nux.service.Notifier;
import com.nux.service.PollService;
import com.nux.tray.NuxTray;
import com.nux.tray.TrayDisplay;
import com.nux.tray.TrayMetric;
import com.nux.tray.Trays;
import com.nux.ui.CommandPalette;
import com.nux.ui.CredentialsDialog;
import com.nux.ui.Dashboard;
import com.nux.ui.SettingsWindow;

/** Nux — a simple, powerful NUT (Network UPS Tools) client. */
public final class App extends Application {

    private static final System.Logger LOG = System.getLogger(App.class.getName());

    private final Path configPath = ConfigStore.DEFAULT_PATH;
    private Config config = Config.defaults();

    private final PollService poll = new PollService();
    private final Dashboard dashboard = new Dashboard();
    private final CommandRegistry registry = new CommandRegistry();
    private final ObservableList<Map.Entry<String, String>> varRows = FXCollections.observableArrayList();

    private final MenuButton profilesButton = new MenuButton("Profiles");
    private final ComboBox<String> upsCombo = new ComboBox<>();
    private final Button connectButton = new Button("Connect");
    private final MenuButton commandsButton = new MenuButton("Commands");
    private final Label connLabel = new Label();

    private Stage stage;
    private CommandPalette palette;
    private boolean running;
    private boolean switchingUps; // guards the combo listener during programmatic repopulation
    private UpsStatus lastStatus; // previous poll's status, for transition alerts
    private UpsSnapshot lastSnapshot; // latest data, for tray refresh on settings changes
    private boolean commLost; // a "lost contact" notification was sent and not yet cleared
    private String authUser; // session-cached upsd credentials for INSTCMD
    private String authPass;
    private NuxTray tray;
    private TrayDisplay lastTrayDisplay;
    private String lastTrayTooltip;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        config = ConfigStore.load(configPath);
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
        Button settingsButton = new Button("Settings…");
        settingsButton.setOnAction(e -> openSettings());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        ToolBar toolbar = new ToolBar(
                profilesButton,
                new Label("UPS"), upsCombo,
                connectButton,
                commandsButton,
                spacer,
                connLabel,
                settingsButton);

        SplitPane split = new SplitPane(dashboard, new TitledPane("All variables", buildVarTable()));
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.68);

        BorderPane content = new BorderPane();
        content.setTop(toolbar);
        content.setCenter(split);
        StackPane root = new StackPane(content); // StackPane so the palette can overlay in-scene

        wirePollService();
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
        stage.show();

        initTray();
        if (config.autoConnect() && !config.lastHost().isBlank()) {
            connect();
        }
    }

    @Override
    public void stop() {
        if (tray != null) {
            tray.dispose();
        }
        poll.shutdown();
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
        registry.register("app.connect", "Connect / Disconnect", this::toggleConnect);
        registry.register("app.settings", "Open Settings…", this::openSettings);
        registry.register("app.saveProfile", "Save connection as profile…", this::saveCurrentAsProfile);
        registry.register("app.quit", "Quit Nux", Platform::exit);
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
        connectButton.setText("Disconnect");
        String ups = upsCombo.getValue() != null
                ? upsCombo.getValue()
                : (config.lastUps().isBlank() ? null : config.lastUps());
        poll.start(config.lastHost(), config.lastPort(), ups, config.tlsModeFor(config.lastTls()));
    }

    private void disconnect() {
        running = false;
        poll.stop();
        connectButton.setText("Connect");
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
            notifyUser("Nux", "Communication with the NUT server restored.", false);
            commLost = false;
        }
        saveConfig(config.withLast(config.lastHost(), config.lastPort(), upsCombo.getValue(), config.lastTls())
                .withAutoConnect(true));
    }

    private void onSnapshot(UpsSnapshot snapshot) {
        dashboard.update(snapshot);
        varRows.setAll(snapshot.vars().entrySet());
        lastSnapshot = snapshot;

        UpsStatus status = snapshot.status();
        StatusTransitions.Alert alert = StatusTransitions.alert(lastStatus, status);
        if (alert != null) {
            notifyUser(alert.title(), alert.message(), alert.critical());
        }
        lastStatus = status;
        pushTray(TrayDisplay.of(snapshot, TrayMetric.from(config.trayMetric())), TrayDisplay.tooltip(snapshot));
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
        pushTray(TrayDisplay.disconnected(), "Nux — disconnected");
        if (running && !commLost) {
            commLost = true;
            notifyUser("Nux", "Lost contact with the NUT server: " + reason, false);
        }
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
            } else if (tray != null) {
                tray.dispose();
                tray = null;
                Platform.setImplicitExit(true);
            }
        }
        refreshTray();
        if (running && connectionChanged) {
            startFresh(upsCombo.getValue());
        }
    }

    private void refreshConnLabel() {
        connLabel.setText(config.lastHost() + ":" + config.lastPort() + (config.lastTls() ? "  (TLS)" : ""));
    }

    // --- Tray icon ------------------------------------------------------------

    /** Backend creation touches D-Bus/AWT and may block briefly, so it runs off the FX thread. */
    private void initTray() {
        if (!config.tray()) {
            return;
        }
        Runnable activate = () -> Platform.runLater(() -> {
            stage.setIconified(false);
            stage.show();
            stage.toFront();
            stage.requestFocus();
        });
        Runnable quit = () -> Platform.runLater(Platform::exit);
        Thread init = new Thread(
                () -> Trays.create(activate, quit).ifPresent(created -> Platform.runLater(() -> {
                    if (!config.tray()) {
                        created.dispose(); // toggled back off while we were starting
                        return;
                    }
                    tray = created;
                    if (config.closeToTray()) {
                        Platform.setImplicitExit(false);
                    }
                    refreshTray();
                })),
                "nux-tray-init");
        init.setDaemon(true);
        init.start();
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
            pushTray(TrayDisplay.disconnected(), running ? "Nux — disconnected" : "Nux — not connected");
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
        confirm.setTitle("Run command");
        confirm.setHeaderText("Run " + command + " on " + ups + "?");
        confirm.setContentText("The command executes on the UPS immediately.");
        if (confirm.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) {
            return;
        }
        if (authPass == null) {
            Optional<CredentialsDialog.Credentials> creds = CredentialsDialog.show(
                    "upsd user with instcmds permission (see upsd.users)", authUser != null ? authUser : "");
            if (creds.isEmpty() || creds.get().user().isEmpty()) {
                return;
            }
            authUser = creds.get().user();
            authPass = creds.get().password();
        }
        poll.runInstCmd(ups, authUser, authPass, command, (ok, message) -> {
            if (ok) {
                notifyUser("Nux", "Command " + command + " sent to " + ups + ".", false);
            } else {
                authPass = null; // could be bad credentials; re-prompt next time
                Alert error = new Alert(Alert.AlertType.ERROR);
                error.setTitle("Command failed");
                error.setHeaderText(command + " failed");
                error.setContentText(friendlyCommandError(message));
                error.show();
            }
        });
    }

    private static String friendlyCommandError(String message) {
        return switch (message) {
            case "ACCESS-DENIED" -> "Access denied — the user needs an instcmds entry in upsd.users.";
            case "USERNAME-REQUIRED", "PASSWORD-REQUIRED" -> "Authentication required — check the credentials.";
            case "CMD-NOT-SUPPORTED" -> "The UPS does not support this command.";
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
        MenuItem save = new MenuItem("Save current as…");
        save.setOnAction(e -> saveCurrentAsProfile());
        profilesButton.getItems().add(save);
        if (!config.profiles().isEmpty()) {
            Menu delete = new Menu("Delete profile");
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
        connectButton.setText("Disconnect");
        startFresh(profile.ups().isBlank() ? null : profile.ups());
    }

    private void saveCurrentAsProfile() {
        TextInputDialog dialog = new TextInputDialog(config.lastHost());
        dialog.setTitle("Save profile");
        dialog.setHeaderText("Save the current connection as a profile");
        dialog.setContentText("Name:");
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
        TableColumn<Map.Entry<String, String>, String> name = new TableColumn<>("Variable");
        name.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(cd.getValue().getKey()));
        name.setPrefWidth(280);
        TableColumn<Map.Entry<String, String>, String> value = new TableColumn<>("Value");
        value.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(cd.getValue().getValue()));
        value.setPrefWidth(420);
        table.getColumns().add(name);
        table.getColumns().add(value);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Connect to a NUT server to see variables"));
        return table;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
