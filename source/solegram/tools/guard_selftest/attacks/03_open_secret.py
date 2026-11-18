def run(ctx):
    return open(ctx["secret"]).read()
