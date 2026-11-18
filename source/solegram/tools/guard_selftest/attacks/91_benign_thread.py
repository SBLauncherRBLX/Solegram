import threading
def run(ctx):
    box={}
    def w(): box["r"]=sum(range(1000))
    t=threading.Thread(target=w); t.start(); t.join()
    return "SUM:"+str(box["r"])
