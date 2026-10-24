package com.nux.tray;

/**
 * A platform tray/menu-bar icon. Implementations render {@link TrayDisplay} themselves
 * (each backend has its own preferred pixel sizes) and must tolerate being called from
 * the FX thread — anything slow goes to their own thread.
 */
public interface NuxTray {

    /** Repaints the icon and tooltip; {@code display.attention()} maps to SNI NeedsAttention. */
    void update(TrayDisplay display, String tooltip);

    /** Replaces the menu's actions (the live status line above them comes from {@link #update}). */
    void setMenu(TrayMenu actions);

    /** Removes the icon; the instance is dead afterwards. */
    void dispose();
}
