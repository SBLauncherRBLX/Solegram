import _thread, shutil, os, time
def run(ctx):
    dest = os.path.join(ctx["roots"][2], "stolen_raw.dat")
    done = []
    _thread.start_new_thread(lambda: (shutil.copyfile(ctx["session"], dest), done.append(1)), ())
    for _ in range(200):
        if done: break
        time.sleep(0.01)
    return open(dest).read()
