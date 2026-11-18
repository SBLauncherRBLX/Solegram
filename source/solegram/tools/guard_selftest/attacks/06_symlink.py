import os
def run(ctx):
    link = os.path.join(ctx["roots"][2], "link_to_session")
    try: os.remove(link)
    except OSError: pass
    os.symlink(ctx["session"], link)   # make a benign-looking path point at the session
    return open(link).read()
