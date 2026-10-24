package com.nux.ui;

import java.util.HashMap;
import java.util.Map;

import javafx.stage.Stage;
import javafx.stage.WindowEvent;

/**
 * Keeps secondary windows single: asking for one that is already open brings it forward
 * instead of stacking a copy (two Discovery windows ran two subnet scans; two History
 * windows read the whole file twice). FX thread only.
 */
public final class Windows {

    private static final Map<String, Stage> OPEN = new HashMap<>();

    private Windows() {}

    /** Brings the open window for {@code key} to the front; false when there is none. */
    public static boolean raise(String key) {
        Stage stage = OPEN.get(key);
        if (stage == null || !stage.isShowing()) {
            return false;
        }
        stage.setIconified(false);
        stage.toFront();
        stage.requestFocus();
        return true;
    }

    /** Remembers {@code stage} as the window for {@code key} until it is hidden. */
    public static void track(String key, Stage stage) {
        OPEN.put(key, stage);
        stage.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> OPEN.remove(key, stage));
    }
}
