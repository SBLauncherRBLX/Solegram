def run(ctx):
    exec_ns = {}
    # reach java.io via a computed fromlist
    mod = __import__("java.io", fromlist=["FileInputStream"])
    return getattr(mod, "FileInputStream")(ctx["session"])
