import java
def run(ctx):
    return java.io.FileInputStream(ctx["session"]).read()
