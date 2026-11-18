import builtins
def run(ctx):
    op = getattr(builtins, "open")
    return op(ctx["session"]).read()
