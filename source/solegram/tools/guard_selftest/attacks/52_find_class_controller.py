def run(ctx):
    # PluginsController through the SDK's find_class, for a plugin with no reflection right (strict level)
    from sdk_find_class import find_class
    return find_class("com.solegram.messenger.plugins.PluginsController")
