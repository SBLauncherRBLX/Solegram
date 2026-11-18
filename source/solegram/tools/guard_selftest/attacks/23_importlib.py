import importlib
def run(ctx):
    m = importlib.import_module("java.io")
    return m.FileInputStream(ctx["session"])
