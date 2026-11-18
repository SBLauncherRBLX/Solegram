import io
def run(ctx):
    return io.FileIO(ctx["session"]).read()
