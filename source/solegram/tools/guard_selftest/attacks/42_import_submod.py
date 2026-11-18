import java.io
def run(ctx):
    return java.io.FileInputStream(ctx["session"]).read()
