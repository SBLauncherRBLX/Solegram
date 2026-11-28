import java
def run(ctx):
    # another plugin's settings through the stand-in
    pc = java.jclass("com.solegram.messenger.plugins.PluginsController")
    return pc.setSettingJson("victim", "k", "1")
