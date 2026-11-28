def __getattr__(name):
    import importlib
    return importlib.import_module(__name__ + "." + name)
