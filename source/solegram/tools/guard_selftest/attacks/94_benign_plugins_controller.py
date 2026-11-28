import java
def run(ctx):
    # plugins written against PluginsController: their own settings and the plugins dir work
    pc = java.jclass("com.solegram.messenger.plugins.PluginsController").getInstance()
    pc.getSettingJson("evil", "key")
    pc.getPluginsDir()
    from com.solegram.messenger.plugins import PluginsController
    PluginsController.getInstance().setSettingJson("evil", "key", "1")
    return "own settings ok"
