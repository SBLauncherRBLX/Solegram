import sys
def run(ctx):
    return list(sys._current_frames().keys())
