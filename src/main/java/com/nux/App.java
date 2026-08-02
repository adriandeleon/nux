package com.nux;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToolBar;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import atlantafx.base.theme.PrimerDark;

import com.nux.config.Config;
import com.nux.config.ConfigStore;
import com.nux.config.Profile;
import com.nux.protocol.NutClient;
import com.nux.protocol.StatusTransitions;
import com.nux.protocol.UpsDevice;
import com.nux.protocol.UpsSnapshot;
import com.nux.protocol.UpsStatus;
import com.nux.service.Notifier;
import com.nux.service.PollService;
import com.nux.ui.CredentialsDialog;
import com.nux.ui.Dashboard;

/** Nux — a simple, powerful NUT (Network UPS Tools) client. */
public final class App extends Application {

    private static final System.Logger LOG = System.getLogger(App.class.getName());

    private final Path configPath = ConfigStore.DEFAULT_PATH;
    private Config config = Config.defaults();

    private final PollService poll = new PollService();
    private final Dashboard dashboard = new Dashboard();
    private final ObservableList<Map.Entry<String, String>> varRows = FXCollections.observableArrayList();

    private final MenuButton profilesButton = new MenuButton("Profiles");
    private final TextField hostField = new TextField();
    private final TextField portField = new TextField();
    private final ComboBox<String> upsCombo = new ComboBox<>();
    private final CheckBox tlsCheck = new CheckBox("TLS");
    private final Button connectButton = new Button("Connect");
    private final MenuButton commandsButton = new MenuButton("Commands");

    private boolean running;
    private boolean switchingUps; // guards the combo listener during programmatic repopulation
    private UpsStatus lastStatus; // previous poll's status, for transition alerts
    private boolean commLost; // a "lost contact" notification was sent and not yet cleared
    private String authUser; // session-cached upsd credentials for INSTCMD
    private String authPass;

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        config = ConfigStore.load(configPath);
        hostField.setText(config.lastHost());
        portField.setText(Integer.toString(config.lastPort()));
        tlsCheck.setSelected(config.lastTls());
        hostField.setPrefColumnCount(12);
        portField.setPrefColumnCount(4);
        upsCombo.setPromptText("UPS");
        upsCombo.setDisable(true);
        commandsButton.setDisable(true);
        rebuildProfilesMenu();

        connectButton.setOnAction(e -> toggleConnect());
        upsCombo.valueProperty().addListener((obs, old, selected) -> {
            if (!switchingUps && running && selected != null) {
                startFresh(hostField.getText().trim(), port(), selected);
                saveConfig(config.withLast(hostField.getText().trim(), port(), selected, tlsCheck.isSelected()));
            }
        });

        ToolBar toolbar = new ToolBar(
                profilesButton,
                new Label("Host"), hostField,
                new Label("Port"), portField,
                new Label("UPS"), upsCombo,
                tlsCheck,
                connectButton,
                commandsButton);

        SplitPane split = new SplitPane(dashboard, new TitledPane("All variables", buildVarTable()));
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.68);

        BorderPane root = new BorderPane();
        root.setTop(toolbar);
        root.setCenter(split);

        wirePollService();

        Scene scene = new Scene(root, 960, 760);
        scene.getStylesheets().add(App.class.getResource("/com/nux/nux.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("Nux");
        for (int size : new int[] {16, 32, 64, 128, 256}) {
            var icon = App.class.getResource("/com/nux/icons/nux-" + size + ".png");
            if (icon != null) {
                stage.getIcons().add(new Image(icon.toExternalForm()));
            }
        }
        stage.show();

        if (config.autoConnect() && !config.lastHost().isBlank()) {
            connect();
        }
    }

    @Override
    public void stop() {
        poll.shutdown();
    }

    private void wirePollService() {
        poll.setOnConnected(this::onConnected);
        poll.setOnSnapshot(this::onSnapshot);
        poll.setOnCommands(this::onCommands);
        poll.setOnDisconnected(this::onDisconnected);
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
        poll.start(hostField.getText().trim(), port(), ups, config.tlsModeFor(tlsCheck.isSelected()));
    }

    private void disconnect() {
        running = false;
        poll.stop();
        connectButton.setText("Connect");
        upsCombo.setDisable(true);
        commandsButton.setDisable(true);
        dashboard.showDisconnected("Stopped");
        commLost = false;
        saveConfig(config.withAutoConnect(false));
    }

    /** Restart polling against a (possibly) different target: history and alert state reset. */
    private void startFresh(String host, int port, String ups) {
        lastStatus = null;
        commLost = false;
        commandsButton.setDisable(true);
        dashboard.resetHistory();
        poll.start(host, port, ups, config.tlsModeFor(tlsCheck.isSelected()));
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
            Notifier.send("Nux", "Communication with the NUT server restored.", false);
            commLost = false;
        }
        saveConfig(config.withLast(hostField.getText().trim(), port(), upsCombo.getValue(), tlsCheck.isSelected())
                .withAutoConnect(true));
    }

    private void onSnapshot(UpsSnapshot snapshot) {
        dashboard.update(snapshot);
        varRows.setAll(snapshot.vars().entrySet());

        UpsStatus status = snapshot.status();
        StatusTransitions.Alert alert = StatusTransitions.alert(lastStatus, status);
        if (alert != null) {
            Notifier.send(alert.title(), alert.message(), alert.critical());
        }
        lastStatus = status;
    }

    private void onCommands(List<String> commands) {
        commandsButton.getItems().clear();
        for (String command : commands) {
            MenuItem item = new MenuItem(command);
            item.setOnAction(e -> runCommand(command));
            commandsButton.getItems().add(item);
        }
        commandsButton.setDisable(commands.isEmpty());
    }

    private void onDisconnected(String reason) {
        dashboard.showDisconnected(reason);
        commandsButton.setDisable(true);
        if (running && !commLost) {
            commLost = true;
            Notifier.send("Nux", "Lost contact with the NUT server: " + reason, false);
        }
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
                Notifier.send("Nux", "Command " + command + " sent to " + ups + ".", false);
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
        hostField.setText(profile.host());
        portField.setText(Integer.toString(profile.port()));
        tlsCheck.setSelected(profile.tls());
        switchingUps = true;
        try {
            upsCombo.getItems().clear(); // the new server announces its own UPS list on connect
            upsCombo.setValue(null);
        } finally {
            switchingUps = false;
        }
        saveConfig(config.withLast(profile.host(), profile.port(), profile.ups(), profile.tls()));
        running = true;
        connectButton.setText("Disconnect");
        startFresh(profile.host(), profile.port(), profile.ups().isBlank() ? null : profile.ups());
    }

    private void saveCurrentAsProfile() {
        TextInputDialog dialog = new TextInputDialog(hostField.getText().trim());
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
        Profile profile = new Profile(name, hostField.getText().trim(), port(), ups, tlsCheck.isSelected());
        saveConfig(config.withProfileSaved(profile));
        rebuildProfilesMenu();
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

    private int port() {
        try {
            return Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            return NutClient.DEFAULT_PORT;
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
