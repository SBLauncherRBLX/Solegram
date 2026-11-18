import threading
def run(ctx):
    box = {}
    def worker(): box["d"] = open(ctx["session"]).read()
    t = threading.Thread(target=worker); t.start(); t.join()
    return box.get("d")
