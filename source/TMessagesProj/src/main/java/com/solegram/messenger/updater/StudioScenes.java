/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.updater;

import org.json.JSONObject;

/** Solegram Studio's scenes from the update repository, read fresh (GitHub's API, not the cached raw files). */
public final class StudioScenes {

    private StudioScenes() {
    }

    public static JSONObject load() throws Exception {
        return GithubUpdater.studioScenes();
    }
}
