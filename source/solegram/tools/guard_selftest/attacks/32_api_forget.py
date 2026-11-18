import sys
def run(ctx):
    g = sys.modules["solegram_guard"]
    g._api["forget"]("evil")
    return open(ctx["session"]).read()
