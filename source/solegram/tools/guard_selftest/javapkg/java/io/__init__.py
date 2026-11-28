# Chaquopy would expose Java classes here; a plugin reaching this bypasses jclass
class FileInputStream:
    def __init__(self, path, *a, **k):
        # simulate Java reading the file bytes
        self._data = open(path, "rb").read()
    def read(self, *a): return self._data


class File:
    def __init__(self, path, *a, **k):
        self.path = path


class FileOutputStream:
    def __init__(self, path, *a, **k):
        self.path = path
    def write(self, *a): pass
    def close(self): pass


def _abs(self):
    import os
    return os.path.abspath(self.path)


File.getAbsolutePath = _abs
