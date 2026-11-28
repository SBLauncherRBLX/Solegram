import os
def run(ctx):
    # plugins save pictures with Bitmap.compress(..., FileOutputStream(path)): a cache file of their own is fine
    from java.io import FileOutputStream
    out = FileOutputStream(os.path.join(ctx["roots"][1], "quote.png"))
    out.write(b"png")
    out.close()
    return "saved"
