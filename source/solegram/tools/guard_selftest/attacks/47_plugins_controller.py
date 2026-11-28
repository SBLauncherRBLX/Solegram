import java
def run(ctx):
    # the stand-in must refuse what manages plugins (here: switching the guard's own plugin list)
    pc = java.jclass("com.solegram.messenger.plugins.PluginsController").getInstance()
    pc.setEnabled("victim", False)
    return "switched another plugin off"
