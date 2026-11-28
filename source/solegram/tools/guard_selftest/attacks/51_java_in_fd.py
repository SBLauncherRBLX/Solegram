import java
def run(ctx):
    # a file descriptor: the guard can't see the path, so it's refused
    FIS = java.jclass("java.io.FileInputStream")
    return FIS(3)
