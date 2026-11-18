import mmap, os
def run(ctx):
    fd = os.open(ctx["session"], os.O_RDONLY)
    return bytes(mmap.mmap(fd, 0, prot=mmap.PROT_READ)[:50])
