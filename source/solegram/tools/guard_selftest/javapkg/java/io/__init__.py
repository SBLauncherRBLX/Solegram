# Chaquopy would expose Java classes here; a plugin reaching this bypasses jclass
class FileInputStream:
    def __init__(self, path, *a, **k):
        # simulate Java reading the file bytes
        self._data = open(path, "rb").read()
    def read(self, *a): return self._data
