import java
def run(ctx):
    FIS = java.jclass("java.io.FileInputStream")
    return FIS(ctx["session"])
