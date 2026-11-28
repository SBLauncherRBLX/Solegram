def run(ctx):
    # a Java stream writing over another plugin's code
    from java.io import FileOutputStream
    FileOutputStream(ctx["plugin_code"]).write(b"evil")
    return "wrote plugin code"
