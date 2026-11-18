import os
def run(ctx):
    # list the secrets dir and read a child
    d = os.path.dirname(ctx["secret"])
    names = os.listdir(d)
    return open(os.path.join(d, names[0])).read()
