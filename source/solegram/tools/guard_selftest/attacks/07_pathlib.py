import pathlib
def run(ctx):
    return pathlib.Path(ctx["session"]).read_bytes()
