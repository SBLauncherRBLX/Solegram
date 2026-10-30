/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.text.TextUtils;

import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;

import java.util.ArrayList;
import java.util.List;

/**
 * The user's favourite reactions, as many as they like, kept on this device: the reaction bar over a message shows
 * them first (those the chat allows), in the order they were chosen.
 */
public final class SolegramFavoriteReactions {

    private static final String KEY = "favoriteReactions";
    private static final String SEPARATOR = "\u0001";
    private static ArrayList<String> cached;

    private SolegramFavoriteReactions() {
    }

    public static synchronized ArrayList<String> get() {
        if (cached == null) {
            cached = new ArrayList<>();
            final String saved = SolegramConfig.prefs().getString(KEY, "");
            if (!TextUtils.isEmpty(saved)) {
                for (String part : saved.split(SEPARATOR)) {
                    if (!part.isEmpty()) {
                        cached.add(part);
                    }
                }
            }
        }
        return new ArrayList<>(cached);
    }

    public static synchronized void set(List<String> emojis) {
        cached = new ArrayList<>(emojis);
        SolegramConfig.prefs().edit().putString(KEY, TextUtils.join(SEPARATOR, cached)).apply();
    }

    /** The reactions with the favourites moved to the front; the list itself is left as it is. */
    public static List<ReactionsLayoutInBubble.VisibleReaction> order(List<ReactionsLayoutInBubble.VisibleReaction> reactions) {
        final ArrayList<String> favorites = get();
        if (favorites.isEmpty() || reactions == null || reactions.isEmpty()) {
            return reactions;
        }
        final ArrayList<ReactionsLayoutInBubble.VisibleReaction> result = new ArrayList<>(reactions.size());
        for (String emoji : favorites) {
            for (int i = 0; i < reactions.size(); i++) {
                final ReactionsLayoutInBubble.VisibleReaction r = reactions.get(i);
                if (r != null && r.documentId == 0 && emoji.equals(r.emojicon) && !result.contains(r)) {
                    result.add(r);
                    break;
                }
            }
        }
        if (result.isEmpty()) {
            return reactions;
        }
        for (int i = 0; i < reactions.size(); i++) {
            final ReactionsLayoutInBubble.VisibleReaction r = reactions.get(i);
            if (!result.contains(r)) {
                result.add(r);
            }
        }
        return result;
    }
}
