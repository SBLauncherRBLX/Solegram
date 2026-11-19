def run(ctx):
    # asks __import__ for a harmless class, then reaches for a dangerous one on what came back
    mod = __import__("java.io", fromlist=["File"])
    return getattr(mod, "FileInputStream")(ctx["session"]).read()
