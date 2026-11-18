def run(ctx):
    from java.io import FileInputStream
    return FileInputStream(ctx["session"]).read()
