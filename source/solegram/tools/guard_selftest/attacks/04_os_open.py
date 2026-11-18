import os
def run(ctx):
    fd = os.open(ctx["session"], os.O_RDONLY)
    return os.read(fd, 100)
