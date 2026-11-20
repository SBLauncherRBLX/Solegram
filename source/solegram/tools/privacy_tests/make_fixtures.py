# Writes test photos and videos full of metadata (GPS, phone, dates) into the current directory.
import struct, subprocess
from PIL import Image, PngImagePlugin
img = Image.new("RGB", (64, 40))
for x in range(64):
    for y in range(40):
        img.putpixel((x, y), (x * 4, y * 6, 128))
exif = Image.Exif()
exif[0x0112] = 6; exif[0x010F] = "SamsungPhone"; exif[0x0110] = "SM-SECRET"; exif[0x0132] = "2026:11:01 10:11:12"
exif[0x8825] = {1: 'N', 2: (55.0, 45.0, 21.0), 3: 'E', 4: (37.0, 37.0, 4.0)}
xmp = b'<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF><rdf:Description tiff:Model="XMPSECRET"/></rdf:RDF></x:xmpmeta>'
img.save("photo.jpg", exif=exif.tobytes(), xmp=xmp, icc_profile=b"\0" * 128, comment=b"COMMENTSECRET")
img.save("prog.jpg", exif=exif.tobytes(), progressive=True)
info = PngImagePlugin.PngInfo(); info.add_text("Comment", "PNGTEXTSECRET")
img.save("pic.png", pnginfo=info, exif=exif.tobytes())
img.save("pic.webp", exif=exif.tobytes(), xmp=xmp)
def box(t, p): return struct.pack(">I", 8 + len(p)) + t + p
def fbox(t, v, f, p): return box(t, bytes([v]) + f.to_bytes(3, 'big') + p)
exifdata = b"\0\0\0\0Exif\0\0" + b"MM\0*GPSSECRETHEIC" * 3
ftyp = box(b"ftyp", b"heic\0\0\0\0mif1heic")
hdlr = fbox(b"hdlr", 0, 0, b"\0\0\0\0pict" + b"\0" * 13)
iinf = fbox(b"iinf", 0, 0, struct.pack(">H", 2) + fbox(b"infe", 2, 0, struct.pack(">HH", 1, 0) + b"hvc1\0") + fbox(b"infe", 2, 0, struct.pack(">HH", 2, 0) + b"Exif\0"))
def meta(off):
    iloc = fbox(b"iloc", 0, 0, bytes([0x44, 0]) + struct.pack(">H", 2) + struct.pack(">HHHII", 1, 0, 1, off + len(exifdata), 16) + struct.pack(">HHHII", 2, 0, 1, off, len(exifdata)))
    return fbox(b"meta", 0, 0, hdlr + iinf + iloc)
m = meta(len(ftyp) + len(meta(0)) + 8)
open("pic.heic", "wb").write(ftyp + m + box(b"mdat", exifdata + b"IMAGEDATA1234567"))
common = ["-metadata", "location=+55.7558+037.6173/", "-metadata", "creation_time=2026-11-01T10:00:00Z"]
subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=128x96:rate=10", "-t", "1", "-c:v", "libx264", *common,
                "-metadata", "make=SECRETMAKE", "-movflags", "use_metadata_tags", "video.mp4", "-y"], check=True)
subprocess.run(["ffmpeg", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=128x96:rate=10", "-t", "1", "-c:v", "libx264", *common,
                "-metadata", "com.apple.quicktime.make=SECRETAPPLE", "-movflags", "use_metadata_tags+faststart", "video.mov", "-y"], check=True)
