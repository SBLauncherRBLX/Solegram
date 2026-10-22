package org.telegram.ui;

public interface MainTabsActivityController {
    void setTabsVisible(boolean visible);

    /**
     * Solegram: the new-chat button is a liquid drop beside the tab bar (drawn by the tabs screen), so the
     * chat list keeps its own floating buttons hidden and only tells when they would be shown.
     */
    default boolean solegramLiquidPlus() {
        return false;
    }

    /**
     * Solegram: where Telegram would show or hide its floating buttons (scrolling, search, preview...);
     * {@code lift} is how far they would be raised over a bulletin.
     */
    default void solegramSetPlusVisible(boolean visible, float lift) {
    }

    /** Solegram: the centre of the stories drop in the chat list's coordinates (for its hint); false if none. */
    default boolean solegramGetStoriesAnchor(float[] xy) {
        return false;
    }
}
