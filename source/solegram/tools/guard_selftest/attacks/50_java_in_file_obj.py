def run(ctx):
    # the session through a java.io.File object
    from java.io import FileInputStream, File
    return FileInputStream(File(ctx["session"])).read()
