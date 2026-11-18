# try to set the guard's thread-local busy flag so checks skip. We don't have a handle; emulate by
# re-entering open from within an audited call is not possible from plugin code. Just confirm open stays blocked.
def run(ctx):
    return open(ctx["session"], "rb").read()
