import importlib
def run(ctx):
    g = importlib.import_module("solegram_guard")
    return "reimported"
