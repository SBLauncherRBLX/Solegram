def run(ctx):
    from java import io
    return io.FileInputStream(ctx["session"]).read()
