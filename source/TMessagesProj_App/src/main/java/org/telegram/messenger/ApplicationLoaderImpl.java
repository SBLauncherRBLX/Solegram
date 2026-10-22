package org.telegram.messenger;

import com.solegram.messenger.plugins.PluginsController;
import com.solegram.messenger.plugins.python.PythonEngine;

import org.telegram.messenger.regular.BuildConfig;

public class ApplicationLoaderImpl extends ApplicationLoader {

    @Override
    public void onCreate() {
        // Solegram: the Python (Chaquopy) plugin engine lives in the app module.
        PluginsController.setEngine(new PythonEngine());
        com.solegram.messenger.Solegram.setSystemHooks(new com.solegram.messenger.SystemHooksImpl());
        super.onCreate();
    }

    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }
}
