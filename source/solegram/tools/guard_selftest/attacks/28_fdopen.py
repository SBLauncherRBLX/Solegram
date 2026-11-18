import os
def run(ctx):
    fd = os.open(ctx["session"], os.O_RDONLY)
    with os.fdopen(fd, "rb") as f:
        return f.read()
