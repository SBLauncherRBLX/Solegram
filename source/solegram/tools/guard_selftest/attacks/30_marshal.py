import marshal
def run(ctx):
    data = marshal.dumps(compile("1+1","x","eval"))
    return marshal.loads(data)
