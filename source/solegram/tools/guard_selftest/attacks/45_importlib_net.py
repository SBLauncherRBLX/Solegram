import importlib
def run(ctx):
    return importlib.import_module("java.net")  # no network perm -> should be blocked
