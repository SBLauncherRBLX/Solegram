import os
def run(ctx):
    own = os.path.join(ctx["roots"][2], "mynote.txt")
    open(own, "w").write("hello from plugin")
    return "WROTE:" + open(own).read()
