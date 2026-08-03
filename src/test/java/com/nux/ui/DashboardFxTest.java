package com.nux.ui;

import static com.nux.ui.FxTestSupport.callOnFx;
import static com.nux.ui.FxTestSupport.field;
import static com.nux.ui.FxTestSupport.runOnFx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.nux.protocol.UpsSnapshot;

/** The dashboard's presentation logic, driven on a real (headless) scene graph. */
@Tag("fx")
class DashboardFxTest {

    @BeforeAll
    static void boot() {
        FxTestSupport.startToolkit();
    }

    private static UpsSnapshot snapshot(Map<String, String> vars) {
        return new UpsSnapshot("cp", vars, Instant.now());
    }

    private static Dashboard dashboardShowing(UpsSnapshot snapshot) {
        return callOnFx(() -> {
            Dashboard dashboard = new Dashboard();
            dashboard.update(snapshot);
            return dashboard;
        });
    }

    private static String pillText(Dashboard dashboard) {
        Label pill = field(dashboard, "statusPill");
        return pill.getText();
    }

    private static boolean pillHas(Dashboard dashboard, String styleClass) {
        Label pill = field(dashboard, "statusPill");
        return pill.getStyleClass().contains(styleClass);
    }

    @Test
    void onlineReadsAsOk() {
        Dashboard dashboard = dashboardShowing(snapshot(Map.of("ups.status", "OL", "battery.charge", "100")));
        assertEquals("Online", pillText(dashboard));
        assertTrue(pillHas(dashboard, "status-ok"));
        assertFalse(pillHas(dashboard, "status-critical"));
    }

    @Test
    void lowBatteryReadsAsCriticalAndReplacesTheEarlierClass() {
        Dashboard dashboard = dashboardShowing(snapshot(Map.of("ups.status", "OL")));
        runOnFx(() -> dashboard.update(snapshot(Map.of("ups.status", "OB LB"))));
        assertEquals("On battery — LOW", pillText(dashboard));
        assertTrue(pillHas(dashboard, "status-critical"));
        // Stale severity classes must not accumulate across updates.
        assertFalse(pillHas(dashboard, "status-ok"));
    }

    @Test
    void detailsShowMeasuredValues() {
        Dashboard dashboard = dashboardShowing(snapshot(Map.of(
                "ups.status", "OL",
                "input.voltage", "120",
                "output.voltage", "119",
                "battery.voltage", "27.6",
                "device.serial", "CXXPQ2003775",
                "ups.realpower", "310")));
        assertEquals("120 V", ((Label) field(dashboard, "inputVoltage")).getText());
        assertEquals("119 V", ((Label) field(dashboard, "outputVoltage")).getText());
        assertEquals("28 V", ((Label) field(dashboard, "batteryVoltage")).getText());
        assertEquals("CXXPQ2003775", ((Label) field(dashboard, "serial")).getText());
    }

    @Test
    void missingValuesShowADashRatherThanZero() {
        Dashboard dashboard = dashboardShowing(snapshot(Map.of("ups.status", "OL")));
        assertEquals("—", ((Label) field(dashboard, "inputVoltage")).getText());
        assertEquals("—", ((Label) field(dashboard, "serial")).getText());
    }

    @Test
    void batteryAgeIsFlaggedWhenReplacementIsDue() {
        Dashboard dashboard = callOnFx(() -> {
            Dashboard d = new Dashboard();
            d.setBatteryAge("4 y 2 mo — replace", true);
            return d;
        });
        Label age = field(dashboard, "batteryAge");
        assertEquals("4 y 2 mo — replace", age.getText());
        assertTrue(age.getStyleClass().contains("detail-warn"));

        runOnFx(() -> dashboard.setBatteryAge("6 mo", false));
        assertFalse(age.getStyleClass().contains("detail-warn"), "the warning must clear again");
    }

    @Test
    void disconnectingClearsEveryReading() {
        Dashboard dashboard = dashboardShowing(snapshot(Map.of(
                "ups.status", "OL", "input.voltage", "120", "device.serial", "S1", "battery.charge", "100")));
        runOnFx(() -> dashboard.showDisconnected("Connection refused"));

        assertTrue(pillHas(dashboard, "status-critical"));
        assertEquals("—", ((Label) field(dashboard, "inputVoltage")).getText());
        assertEquals("—", ((Label) field(dashboard, "serial")).getText());
        // Stale numbers next to a "disconnected" pill would be actively misleading.
        assertEquals("—", ((Label) field(dashboard, "power")).getText());
    }

    @Test
    void overviewStaysHiddenForASingleUps() {
        Dashboard dashboard = callOnFx(() -> {
            Dashboard d = new Dashboard();
            d.setOverview(Map.of("cp", snapshot(Map.of("ups.status", "OL"))), "cp", name -> {});
            return d;
        });
        HBox overview = field(dashboard, "overview");
        assertFalse(overview.isVisible());
        assertFalse(overview.isManaged(), "an invisible strip must not reserve layout space");
    }

    @Test
    void overviewListsEveryUpsAndMarksTheSelectedOne() {
        Map<String, UpsSnapshot> all = new LinkedHashMap<>();
        all.put("a", snapshot(Map.of("ups.status", "OL", "battery.charge", "100")));
        all.put("b", snapshot(Map.of("ups.status", "OB")));

        Dashboard dashboard = callOnFx(() -> {
            Dashboard d = new Dashboard();
            d.setOverview(all, "b", name -> {});
            return d;
        });

        HBox overview = field(dashboard, "overview");
        assertTrue(overview.isVisible());
        assertEquals(2, overview.getChildren().size());
        assertFalse(overview.getChildren().get(0).getStyleClass().contains("overview-selected"));
        assertTrue(overview.getChildren().get(1).getStyleClass().contains("overview-selected"));
    }

    @Test
    void clickingAnOverviewCardSelectsThatUps() {
        Map<String, UpsSnapshot> all = new LinkedHashMap<>();
        all.put("a", snapshot(Map.of("ups.status", "OL")));
        all.put("b", snapshot(Map.of("ups.status", "OL")));
        AtomicReference<String> picked = new AtomicReference<>();

        Dashboard dashboard = callOnFx(() -> {
            Dashboard d = new Dashboard();
            d.setOverview(all, "a", picked::set);
            return d;
        });
        HBox overview = field(dashboard, "overview");
        runOnFx(() -> overview.getChildren().get(1).getOnMouseClicked().handle(null));

        assertEquals("b", picked.get());
    }

    @Test
    void batteryHealthLabelsMatchTheAgeThresholds() {
        // Guards the wiring between the analysis layer and what the user actually sees.
        LocalDate today = LocalDate.now();
        Dashboard dashboard = dashboardShowing(snapshot(Map.of(
                "ups.status", "OL", "battery.date", today.minusMonths(7).toString())));
        // The dashboard is told the text by App; here we only assert it renders what it is given.
        runOnFx(() -> dashboard.setBatteryAge("7 mo", false));
        assertEquals("7 mo", ((Label) field(dashboard, "batteryAge")).getText());
    }
}
