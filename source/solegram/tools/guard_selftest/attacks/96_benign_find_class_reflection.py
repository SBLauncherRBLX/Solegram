import os
def run(ctx):
    # run with GRANT=reflection (soft / medium level): find_class gives PluginsController to reflect on
    if "reflection" not in (os.environ.get("GRANT") or ""):
        return "skipped: needs GRANT=reflection"
    from sdk_find_class import find_class
    return find_class("com.solegram.messenger.plugins.PluginsController")
