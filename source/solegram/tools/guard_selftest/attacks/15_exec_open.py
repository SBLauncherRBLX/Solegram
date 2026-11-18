def run(ctx):
    g = {"P": ctx["session"]}
    exec("DATA = open(P).read()", g)
    return g["DATA"]
