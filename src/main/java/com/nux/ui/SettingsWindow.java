package com.nux.ui;

import static com.nux.i18n.Messages.tr;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import com.nux.analysis.TestSchedule;
import com.nux.autostart.Autostart;
import com.nux.config.Advanced;
import com.nux.config.Config;
import com.nux.i18n.Messages;
import com.nux.tray.TrayMetric;

/**
 * The settings dialog: general, connection, tray, power/energy, and automation.
 *
 * <p>Save hands the caller an <em>edit</em> — a function applied to the configuration
 * as it is at that moment — rather than a whole Config rebuilt from the copy taken when
 * the dialog opened. The dialog stays open while polling goes on, and a rebuilt copy
 * silently rolled back anything written meanwhile (the UPS picked on reconnect, a
 * self-test's last-run stamp, the update-check throttle).
 */
public final class SettingsWindow {

    /** The "follow the system" language choice, persisted as an empty preference. */
    private static final String SYSTEM_LANGUAGE = "";

    private SettingsWindow() {}

    public static void show(Window owner, Config current, Consumer<UnaryOperator<Config>> onSave) {
        Advanced adv = current.advanced();

        // --- General
        ComboBox<String> language = new ComboBox<>();
        Map<String, String> languages = new java.util.LinkedHashMap<>();
        languages.put(SYSTEM_LANGUAGE, tr("settings.languageSystem"));
        languages.putAll(Messages.languageChoices());
        language.getItems().setAll(languages.keySet());
        language.setConverter(converter(languages::get, name -> SYSTEM_LANGUAGE));
        language.setValue(languages.containsKey(adv.uiLanguage()) ? adv.uiLanguage() : SYSTEM_LANGUAGE);
        CheckBox notifications = new CheckBox(tr("settings.notifications"));
        notifications.setSelected(current.notifications());
        CheckBox singleInstance = new CheckBox(tr("settings.singleInstance"));
        singleInstance.setSelected(adv.singleInstance());
        CheckBox updateCheck = new CheckBox(tr("settings.updateCheck"));
        updateCheck.setSelected(adv.updateCheck());
        CheckBox autostart = new CheckBox(tr("settings.autostart"));

        GridPane general = grid();
        general.addRow(0, new Label(tr("settings.language")), language);
        general.add(note(tr("settings.languageNote")), 0, 1, 2, 1);
        general.add(notifications, 0, 2, 2, 1);
        general.add(autostart, 0, 3, 2, 1);
        general.add(note(tr("settings.autostartNote")), 0, 4, 2, 1);
        general.add(singleInstance, 0, 5, 2, 1);
        general.add(note(tr("settings.singleInstanceNote")), 0, 6, 2, 1);
        general.add(updateCheck, 0, 7, 2, 1);
        general.add(note(tr("settings.updateCheckNote")), 0, 8, 2, 1);

        // --- Connection
        TextField host = new TextField(current.lastHost());
        host.setId("settings-host");
        TextField port = new TextField(Integer.toString(current.lastPort()));
        port.setId("settings-port");
        port.setPrefColumnCount(5);
        CheckBox tls = new CheckBox(tr("settings.tls"));
        tls.setSelected(current.lastTls());
        CheckBox autoConnect = new CheckBox(tr("settings.autoConnect"));
        autoConnect.setSelected(current.autoConnect());
        Button discover = new Button(tr("settings.discover"));
        GridPane connection = grid();
        connection.addRow(0, new Label(tr("settings.host")), host);
        connection.addRow(1, new Label(tr("settings.port")), port);
        connection.add(discover, 1, 2);
        connection.add(tls, 0, 3, 2, 1);
        connection.add(autoConnect, 0, 4, 2, 1);

        // --- Tray
        CheckBox tray = new CheckBox(tr("settings.tray"));
        tray.setSelected(current.tray());
        CheckBox closeToTray = new CheckBox(tr("settings.closeToTray"));
        closeToTray.setSelected(current.closeToTray());
        ComboBox<TrayMetric> metric = new ComboBox<>();
        metric.getItems().setAll(TrayMetric.values());
        metric.setValue(TrayMetric.from(current.trayMetric()));
        metric.setConverter(converter(TrayMetric::label, TrayMetric::from));
        closeToTray.disableProperty().bind(tray.selectedProperty().not());
        metric.disableProperty().bind(tray.selectedProperty().not());
        GridPane trayGrid = grid();
        trayGrid.add(tray, 0, 0, 2, 1);
        trayGrid.add(closeToTray, 0, 1, 2, 1);
        trayGrid.addRow(2, new Label(tr("settings.trayShows")), metric);

        // Start-at-login is OS state, not app config: read it back from the OS so the two
        // can never disagree, and apply it immediately rather than on Save. Both directions
        // can spawn reg.exe/launchctl, so they run off the FX thread.
        if (Autostart.isSupported()) {
            autostart.setDisable(true);
            boolean[] applying = {true}; // suppress the listener while we set the OS truth
            autostart.selectedProperty().addListener((o, was, now) -> {
                if (applying[0]) {
                    return;
                }
                autostart.setDisable(true);
                boolean minimized = tray.isSelected();
                background(() -> {
                    boolean ok = Autostart.setEnabled(now, minimized);
                    boolean truth = ok ? now : Autostart.isEnabled();
                    Platform.runLater(() -> {
                        applying[0] = true;
                        autostart.setSelected(truth); // revert to the truth on failure
                        applying[0] = false;
                        autostart.setDisable(false);
                    });
                });
            });
            background(() -> {
                boolean enabled = Autostart.isEnabled();
                Platform.runLater(() -> {
                    autostart.setSelected(enabled);
                    applying[0] = false;
                    autostart.setDisable(false);
                });
            });
        } else {
            autostart.setDisable(true);
            autostart.setTooltip(new Tooltip(Autostart.unavailableReason()));
        }

        // --- Power & history
        TextField watts = new TextField(adv.nominalWatts() > 0 ? trim(adv.nominalWatts()) : "");
        watts.setPromptText(tr("settings.wattsPrompt"));
        watts.setPrefColumnCount(8);
        TextField tariff = new TextField(adv.tariffPerKwh() > 0 ? trim(adv.tariffPerKwh()) : "");
        tariff.setPromptText(tr("settings.tariffPrompt"));
        tariff.setPrefColumnCount(8);
        TextField currency = new TextField(adv.currency());
        currency.setPrefColumnCount(3);
        Spinner<Integer> historyDays = new Spinner<>(1, 365, Math.clamp(adv.historyDays(), 1, 365));
        historyDays.setPrefWidth(90);
        GridPane power = grid();
        power.addRow(0, new Label(tr("settings.nominalWatts")), watts);
        power.addRow(1, new Label(tr("settings.tariff")), tariff);
        power.addRow(2, new Label(tr("settings.currency")), currency);
        power.addRow(3, new Label(tr("settings.historyDays")), historyDays);
        power.add(note(tr("settings.powerNote")), 0, 4, 2, 1);

        // --- Automation
        CheckBox selfTest = new CheckBox(tr("settings.selfTest"));
        selfTest.setSelected(adv.selfTestEnabled());
        Spinner<Integer> interval = new Spinner<>(1, 365, Math.clamp(adv.selfTestIntervalDays(), 1, 365));
        interval.setPrefWidth(90);
        TextField time = new TextField(adv.selfTestTime());
        time.setId("settings-time");
        time.setPrefColumnCount(6);
        TextField command = new TextField(adv.selfTestCommand());
        interval.disableProperty().bind(selfTest.selectedProperty().not());
        time.disableProperty().bind(selfTest.selectedProperty().not());
        command.disableProperty().bind(selfTest.selectedProperty().not());
        GridPane automation = grid();
        automation.add(selfTest, 0, 0, 2, 1);
        automation.addRow(1, new Label(tr("settings.selfTestInterval")), interval);
        automation.addRow(2, new Label(tr("settings.selfTestTime")), time);
        automation.addRow(3, new Label(tr("settings.selfTestCommand")), command);
        automation.add(note(tr("settings.selfTestNote")), 0, 4, 2, 1);
        automation.add(new Label(tr(
                        "settings.lastRun",
                        adv.selfTestLastRun() > 0
                                ? TestSchedule.describeNext(Optional.of(Instant.ofEpochSecond(adv.selfTestLastRun())))
                                : tr("settings.never"))),
                0, 5, 2, 1);
        if (adv.selfTestEnabled()) {
            automation.add(new Label(tr(
                            "settings.nextRun",
                            TestSchedule.describeNext(TestSchedule.nextRun(
                                    adv.selfTestLastRun(),
                                    adv.selfTestArmed(),
                                    adv.selfTestIntervalDays(),
                                    TestSchedule.parseTime(adv.selfTestTime()),
                                    ZoneId.systemDefault())))),
                    0, 6, 2, 1);
        }

        TabPane tabs = new TabPane(
                tab(tr("settings.tab.general"), general),
                tab(tr("settings.tab.connection"), connection),
                tab(tr("settings.tab.tray"), trayGrid),
                tab(tr("settings.tab.power"), power),
                tab(tr("settings.tab.automation"), automation));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        // --- Validation: Save stays disabled, and says why, until every field is usable.
        Label problem = new Label();
        problem.getStyleClass().add("settings-problem");
        problem.setWrapText(true);
        BooleanBinding hostBad = Bindings.createBooleanBinding(() -> host.getText().isBlank(), host.textProperty());
        BooleanBinding portBad = Bindings.createBooleanBinding(() -> port(port.getText()).isEmpty(), port.textProperty());
        BooleanBinding timeBad = Bindings.createBooleanBinding(
                () -> selfTest.isSelected() && !validTime(time.getText()), time.textProperty(), selfTest.selectedProperty());
        problem.textProperty().bind(Bindings.when(hostBad).then(tr("settings.invalidHost"))
                .otherwise(Bindings.when(portBad).then(tr("settings.invalidPort"))
                        .otherwise(Bindings.when(timeBad).then(tr("settings.invalidTime")).otherwise(""))));
        markInvalid(host, hostBad);
        markInvalid(port, portBad);
        markInvalid(time, timeBad);

        Stage stage = new Stage();
        discover.setOnAction(e -> DiscoveryWindow.show(stage, (foundHost, foundPort) -> {
            host.setText(foundHost);
            port.setText(Integer.toString(foundPort));
        }));
        Button cancel = new Button(tr("settings.cancel"));
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.close());
        Button save = new Button(tr("settings.save"));
        save.setId("settings-save");
        save.setDefaultButton(true);
        save.disableProperty().bind(hostBad.or(portBad).or(timeBad));
        save.setOnAction(e -> {
            String newHost = host.getText().trim();
            int newPort = port(port.getText()).orElseThrow();
            boolean newTls = tls.isSelected();
            boolean newAuto = autoConnect.isSelected();
            boolean newTray = tray.isSelected();
            boolean newClose = closeToTray.isSelected();
            String newMetric = metric.getValue().key();
            boolean newNotify = notifications.isSelected();
            int days = historyDays.getValue();
            double newWatts = parseDouble(watts.getText());
            double newTariff = parseDouble(tariff.getText());
            String newCurrency = currency.getText().isBlank() ? "$" : currency.getText().trim();
            boolean testOn = selfTest.isSelected();
            int testEvery = interval.getValue();
            String testAt = time.getText().trim();
            String testCommand = command.getText().isBlank() ? Advanced.DEFAULT_TEST_COMMAND : command.getText().trim();
            boolean single = singleInstance.isSelected();
            String lang = language.getValue();
            boolean checkUpdates = updateCheck.isSelected();
            // Only what this dialog edits; everything else comes from the live config.
            onSave.accept(live -> {
                Advanced a = live.advanced();
                return new Config(
                        live.profiles(),
                        newHost,
                        newPort,
                        live.lastUps(),
                        newTls,
                        live.tlsTrustAll(),
                        newAuto,
                        newTray,
                        newClose,
                        newMetric,
                        newNotify,
                        new Advanced(
                                days, newWatts, newTariff, newCurrency, testOn, testEvery, testAt,
                                a.selfTestLastRun(), a.selfTestArmed(), testCommand, single, lang,
                                checkUpdates, a.lastUpdateCheckMs(), a.dismissedUpdateVersion()));
            });
            stage.close();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(10, problem, spacer, cancel, save);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        VBox root = new VBox(12, tabs, buttons);
        root.setPadding(new Insets(16));
        VBox.setVgrow(tabs, Priority.ALWAYS);

        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle(tr("settings.title"));
        Scene scene = new Scene(root, 560, 470);
        scene.getStylesheets().add(SettingsWindow.class.getResource("/com/nux/nux.css").toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(480);
        stage.setMinHeight(420);
        stage.show();
    }

    /** A TCP port in range, or empty — never a silent fallback the user didn't type. */
    static Optional<Integer> port(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value >= 1 && value <= 65535 ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static boolean validTime(String text) {
        try {
            LocalTime.parse(text.trim());
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static void markInvalid(javafx.scene.Node field, BooleanBinding bad) {
        bad.addListener((o, was, now) -> field.pseudoClassStateChanged(INVALID, now));
        field.pseudoClassStateChanged(INVALID, bad.get());
    }

    private static final javafx.css.PseudoClass INVALID = javafx.css.PseudoClass.getPseudoClass("danger");

    private static void background(Runnable work) {
        Thread t = new Thread(work, "nux-autostart");
        t.setDaemon(true);
        t.start();
    }

    private static Tab tab(String title, Region content) {
        content.setPadding(new Insets(14));
        return new Tab(title, content);
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(9);
        return grid;
    }

    private static Label note(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("detail-name");
        label.setWrapText(true);
        label.setMaxWidth(460);
        return label;
    }

    private static <T> StringConverter<T> converter(
            java.util.function.Function<T, String> toString, java.util.function.Function<String, T> fromString) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : toString.apply(value);
            }

            @Override
            public T fromString(String s) {
                return fromString.apply(s);
            }
        };
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.valueOf(value);
    }

    private static double parseDouble(String text) {
        try {
            return Math.max(0, Double.parseDouble(text.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
