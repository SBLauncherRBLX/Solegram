import gc
def run(ctx):
    return len(gc.get_objects())
