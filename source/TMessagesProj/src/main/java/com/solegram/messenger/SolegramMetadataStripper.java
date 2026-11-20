/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Solegram: a copy of a photo or a video without what it tells about the person who took it: where (GPS), with what
 * (phone make, model, serial numbers, software) and when (capture dates), for photos and videos sent as files, which
 * Telegram uploads byte for byte (a compressed photo is re-encoded by Telegram and loses all of it anyway).
 * <ul>
 * <li>JPEG: the EXIF block is replaced by one that only keeps the picture's orientation (so it is not turned), XMP,
 * IPTC/Photoshop blocks and comments go, the colour profile stays; the extra pictures some phones put after the
 * picture (depth maps, previews with their own EXIF) go too.</li>
 * <li>PNG: eXIf, text and time chunks go. WebP: EXIF and XMP chunks go.</li>
 * <li>MP4 / MOV / 3GP: the user data and metadata boxes (location, make, model, dates of Android and iPhone) become
 * "free" boxes that every player skips, the creation and modification times are zeroed; nothing moves, so the
 * video plays as before.</li>
 * <li>HEIC / AVIF: the EXIF and XMP items are blanked (the picture's rotation lives elsewhere and stays).</li>
 * </ul>
 * Plain Java on purpose: it is tested off the phone (solegram/tools/privacy_tests).
 */
public final class SolegramMetadataStripper {

    private SolegramMetadataStripper() {
    }

    public static final int NONE = 0, JPEG = 1, PNG = 2, WEBP = 3, MP4 = 4, HEIF = 5;

    /** Pictures read into memory up to this size (larger ones are sent as they are). */
    private static final long MAX_IN_MEMORY = 96L * 1024 * 1024;
    private static final int MAX_MOOV = 64 * 1024 * 1024;

    /** The kind of file by its first bytes (not its name). */
    public static int kindOf(File file) {
        final byte[] h = new byte[16];
        try (InputStream in = new FileInputStream(file)) {
            int n = 0;
            while (n < h.length) {
                final int r = in.read(h, n, h.length - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
            if (n < 12) {
                return NONE;
            }
        } catch (IOException e) {
            return NONE;
        }
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return JPEG;
        }
        if ((h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
            return PNG;
        }
        if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') {
            return WEBP;
        }
        if (h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') {
            final String brand = new String(h, 8, 4, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
            if (brand.startsWith("hei") || brand.startsWith("hev") || brand.startsWith("mif") || brand.startsWith("msf") || brand.startsWith("avi") || brand.startsWith("avs")) {
                return HEIF;
            }
            return MP4;
        }
        // old QuickTime files start with moov/mdat/wide/free
        final String type = new String(h, 4, 4, StandardCharsets.ISO_8859_1);
        if (type.equals("moov") || type.equals("mdat") || type.equals("wide") || type.equals("free")) {
            return MP4;
        }
        return NONE;
    }

    /**
     * Writes a stripped copy of {@code src} to {@code dst}. Returns false (and leaves no {@code dst}) when the file is
     * not a kind it knows, is damaged, or something failed: the caller then sends the original.
     */
    public static boolean strip(File src, File dst) {
        final int kind = kindOf(src);
        if (kind == NONE) {
            return false;
        }
        boolean ok = false;
        try {
            switch (kind) {
                case JPEG:
                case PNG:
                case WEBP: {
                    if (src.length() > MAX_IN_MEMORY) {
                        return false;
                    }
                    final byte[] data = readAll(src);
                    final byte[] out = kind == JPEG ? stripJpeg(data) : kind == PNG ? stripPng(data) : stripWebp(data);
                    if (out == null) {
                        return false;
                    }
                    try (OutputStream os = new FileOutputStream(dst)) {
                        os.write(out);
                    }
                    ok = true;
                    break;
                }
                case MP4:
                case HEIF: {
                    copy(src, dst);
                    try (RandomAccessFile raf = new RandomAccessFile(dst, "rw")) {
                        ok = kind == MP4 ? stripMp4(raf) : stripHeif(raf);
                    }
                    break;
                }
            }
        } catch (Throwable e) {
            ok = false;
        }
        if (!ok) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
        }
        return ok;
    }

    // ---------------------------------------------------------------- JPEG

    /** The JPEG without its metadata, or null when it does not look like a JPEG it can read. */
    public static byte[] stripJpeg(byte[] d) {
        if (d.length < 4 || (d[0] & 0xFF) != 0xFF || (d[1] & 0xFF) != 0xD8) {
            return null;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        out.write(0xFF);
        out.write(0xD8);
        final int orientation = findOrientation(d);
        boolean wroteExif = orientation <= 1;
        int p = 2;
        while (true) {
            // markers may be padded with any number of 0xFF
            if (p >= d.length || (d[p] & 0xFF) != 0xFF) {
                return null;
            }
            while (p < d.length && (d[p] & 0xFF) == 0xFF) {
                p++;
            }
            if (p >= d.length) {
                return null;
            }
            final int marker = d[p] & 0xFF;
            p++;
            if (marker == 0xD9) { // end of the picture: anything after it (extra pictures) is dropped
                out.write(0xFF);
                out.write(0xD9);
                return out.toByteArray();
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { // markers without a length
                out.write(0xFF);
                out.write(marker);
                continue;
            }
            if (p + 2 > d.length) {
                return null;
            }
            final int len = ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
            if (len < 2 || p + len > d.length) {
                return null;
            }
            final int start = p + 2, end = p + len;
            boolean keep = true;
            if (marker == 0xE1) {
                keep = false; // EXIF, XMP and extended XMP (the place, the phone, the dates)
            } else if (marker == 0xED || marker == 0xFE) { // IPTC/Photoshop, comments
                keep = false;
            } else if (marker == 0xE2 && !startsWith(d, start, end, "ICC_PROFILE\0")) {
                keep = false; // MPF (the extra pictures are dropped) and FlashPix; the colour profile stays
            } else if (marker >= 0xE3 && marker <= 0xEF && marker != 0xEE) {
                keep = false; // other makers' blocks (APP14 "Adobe" stays: it says how the colours are stored)
            }
            if (!wroteExif && marker != 0xE0) {
                // the orientation goes first (after JFIF's APP0, which must stay first)
                writeOrientationExif(out, orientation);
                wroteExif = true;
            }
            if (keep) {
                out.write(0xFF);
                out.write(marker);
                out.write(d, p, len);
            }
            p = end;
            if (marker == 0xDA) {
                // entropy-coded data: up to the next marker that is not a stuffed byte or a restart
                int q = p;
                while (q + 1 < d.length) {
                    if ((d[q] & 0xFF) == 0xFF) {
                        final int n = d[q + 1] & 0xFF;
                        if (n != 0x00 && n != 0xFF && !(n >= 0xD0 && n <= 0xD7)) {
                            break;
                        }
                    }
                    q++;
                }
                if (q + 1 >= d.length) {
                    // no end marker: keep the rest as it is
                    out.write(d, p, d.length - p);
                    return out.toByteArray();
                }
                out.write(d, p, q - p);
                p = q;
            }
        }
    }

    /** The orientation of the first EXIF block before the picture data, 0 when there is none. */
    private static int findOrientation(byte[] d) {
        int p = 2;
        while (p + 4 <= d.length && (d[p] & 0xFF) == 0xFF) {
            while (p < d.length && (d[p] & 0xFF) == 0xFF) {
                p++;
            }
            if (p + 3 > d.length) {
                return 0;
            }
            final int marker = d[p] & 0xFF;
            if (marker == 0xDA || marker == 0xD9) {
                return 0;
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                p++;
                continue;
            }
            final int len = ((d[p + 1] & 0xFF) << 8) | (d[p + 2] & 0xFF);
            final int start = p + 3, end = p + 1 + len;
            if (len < 2 || end > d.length) {
                return 0;
            }
            if (marker == 0xE1 && startsWith(d, start, end, "Exif\0\0")) {
                return jpegOrientation(d, start + 6, end);
            }
            p = end;
        }
        return 0;
    }

    private static boolean startsWith(byte[] d, int start, int end, String s) {
        if (end - start < s.length()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (d[start + i] != (byte) s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Orientation (1..8) from a TIFF block, 0 when there is none. */
    static int jpegOrientation(byte[] d, int tiff, int end) {
        try {
            if (tiff + 8 > end) {
                return 0;
            }
            final boolean le;
            if (d[tiff] == 'I' && d[tiff + 1] == 'I') {
                le = true;
            } else if (d[tiff] == 'M' && d[tiff + 1] == 'M') {
                le = false;
            } else {
                return 0;
            }
            final long ifd = u32(d, tiff + 4, le);
            final int ifdPos = (int) (tiff + ifd);
            if (ifd < 8 || ifdPos + 2 > end) {
                return 0;
            }
            final int count = u16(d, ifdPos, le);
            for (int i = 0; i < count; i++) {
                final int e = ifdPos + 2 + i * 12;
                if (e + 12 > end) {
                    return 0;
                }
                if (u16(d, e, le) == 0x0112) {
                    final int type = u16(d, e + 2, le);
                    final int v = type == 3 ? u16(d, e + 8, le) : (int) u32(d, e + 8, le);
                    return v >= 1 && v <= 8 ? v : 0;
                }
            }
        } catch (RuntimeException ignore) {
        }
        return 0;
    }

    /** APP1 with a minimal EXIF: IFD0 with the orientation only. */
    private static void writeOrientationExif(ByteArrayOutputStream out, int orientation) {
        final byte[] b = {
            (byte) 0xFF, (byte) 0xE1, 0, 34,
            'E', 'x', 'i', 'f', 0, 0,
            'M', 'M', 0, 42, 0, 0, 0, 8, // TIFF header, IFD0 at 8
            0, 1, // one entry
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, // Orientation SHORT 1
            0, 0, 0, 0, // no next IFD
        };
        out.write(b, 0, b.length);
    }

    // ---------------------------------------------------------------- PNG

    public static byte[] stripPng(byte[] d) {
        if (d.length < 8 || (d[0] & 0xFF) != 0x89 || d[1] != 'P' || d[2] != 'N' || d[3] != 'G') {
            return null;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
        out.write(d, 0, 8);
        int p = 8;
        while (p + 12 <= d.length) {
            final long len = u32(d, p, false);
            if (len > d.length - p - 12) {
                return null;
            }
            final String type = new String(d, p + 4, 4, StandardCharsets.ISO_8859_1);
            final int total = (int) len + 12;
            final boolean drop = type.equals("eXIf") || type.equals("tEXt") || type.equals("zTXt") || type.equals("iTXt") || type.equals("tIME");
            if (!drop) {
                out.write(d, p, total);
            }
            p += total;
            if (type.equals("IEND")) {
                return out.toByteArray();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- WebP

    public static byte[] stripWebp(byte[] d) {
        if (d.length < 12 || d[0] != 'R' || d[8] != 'W') {
            return null;
        }
        final ByteArrayOutputStream body = new ByteArrayOutputStream(d.length);
        body.write(d, 8, 4); // "WEBP"
        int p = 12;
        int vp8x = -1;
        final long riffEnd = Math.min(d.length, 8 + u32(d, 4, true));
        while (p + 8 <= riffEnd) {
            final String type = new String(d, p, 4, StandardCharsets.ISO_8859_1);
            final long len = u32(d, p + 4, true);
            final long padded = len + (len & 1);
            if (p + 8 + len > d.length) {
                return null;
            }
            final int total = (int) Math.min(8 + padded, d.length - p);
            if (!type.equals("EXIF") && !type.equals("XMP ")) {
                if (type.equals("VP8X")) {
                    vp8x = body.size();
                }
                body.write(d, p, total);
            }
            p += total;
        }
        final byte[] b = body.toByteArray();
        if (vp8x >= 0 && vp8x + 8 < b.length) {
            b[vp8x + 8] &= ~(0x08 | 0x04); // the "has EXIF" and "has XMP" flags
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(b.length + 8);
        out.write('R');
        out.write('I');
        out.write('F');
        out.write('F');
        final int size = b.length;
        out.write(size & 0xFF);
        out.write((size >> 8) & 0xFF);
        out.write((size >> 16) & 0xFF);
        out.write((size >>> 24) & 0xFF);
        out.write(b, 0, b.length);
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- MP4 / MOV

    /** In place: boxes renamed to "free" and dates zeroed; sizes and offsets stay the same. */
    static boolean stripMp4(RandomAccessFile raf) throws IOException {
        final long length = raf.length();
        long pos = 0;
        boolean sawMoov = false;
        while (pos + 8 <= length) {
            raf.seek(pos);
            long size = raf.readInt() & 0xFFFFFFFFL;
            final byte[] t = new byte[4];
            raf.readFully(t);
            final String type = new String(t, StandardCharsets.ISO_8859_1);
            int header = 8;
            if (size == 1) {
                size = raf.readLong();
                header = 16;
            } else if (size == 0) {
                size = length - pos;
            }
            if (size < header || pos + size > length) {
                return sawMoov; // a damaged tail: what was cleaned stays cleaned
            }
            if (type.equals("moov")) {
                if (size > MAX_MOOV) {
                    return false;
                }
                final byte[] moov = new byte[(int) size];
                raf.seek(pos);
                raf.readFully(moov);
                cleanContainer(moov, header, moov.length, 0);
                raf.seek(pos);
                raf.write(moov);
                sawMoov = true;
            } else if (type.equals("uuid") || type.equals("meta") || type.equals("udta")) {
                // XMP and tags at the top level (some cameras and editors)
                if (size > MAX_MOOV) {
                    return false;
                }
                raf.seek(pos + 4);
                raf.write(FREE);
                raf.seek(pos + header);
                raf.write(new byte[(int) (size - header)]);
            }
            pos += size;
        }
        return sawMoov;
    }

    private static final byte[] FREE = {'f', 'r', 'e', 'e'};

    private static void cleanContainer(byte[] b, int from, int to, int depth) {
        int p = from;
        while (p + 8 <= to && depth < 8) {
            long size = u32(b, p, false);
            int header = 8;
            if (size == 1) {
                if (p + 16 > to) {
                    return;
                }
                size = (u32(b, p + 8, false) << 32) | u32(b, p + 12, false);
                header = 16;
            } else if (size == 0) {
                size = to - p;
            }
            if (size < header || p + size > to) {
                return;
            }
            final String type = new String(b, p + 4, 4, StandardCharsets.ISO_8859_1);
            final int end = (int) (p + size);
            switch (type) {
                case "trak":
                case "mdia":
                    cleanContainer(b, p + header, end, depth + 1);
                    break;
                case "udta": // Android's location (©xyz), make, model; iTunes-style tags
                case "meta": // iPhone's location, make, model, software, capture date
                case "uuid": // XMP
                    b[p + 4] = 'f';
                    b[p + 5] = 'r';
                    b[p + 6] = 'e';
                    b[p + 7] = 'e';
                    for (int i = p + header; i < end; i++) {
                        b[i] = 0; // what it held must not stay readable in the file
                    }
                    break;
                case "mvhd":
                case "tkhd":
                case "mdhd": {
                    final int v = p + header;
                    if (v + 4 > end) {
                        break;
                    }
                    final int version = b[v] & 0xFF;
                    final int n = version == 1 ? 16 : 8; // creation + modification time
                    if (v + 4 + n <= end) {
                        for (int i = 0; i < n; i++) {
                            b[v + 4 + i] = 0;
                        }
                    }
                    break;
                }
            }
            p = end;
        }
    }

    // ---------------------------------------------------------------- HEIC / AVIF

    /** In place: the bytes of the EXIF and XMP items are zeroed. True when the file could be read. */
    static boolean stripHeif(RandomAccessFile raf) throws IOException {
        final long length = raf.length();
        long pos = 0;
        while (pos + 8 <= length) {
            raf.seek(pos);
            long size = raf.readInt() & 0xFFFFFFFFL;
            final byte[] t = new byte[4];
            raf.readFully(t);
            int header = 8;
            if (size == 1) {
                size = raf.readLong();
                header = 16;
            } else if (size == 0) {
                size = length - pos;
            }
            if (size < header || pos + size > length) {
                return false;
            }
            if (new String(t, StandardCharsets.ISO_8859_1).equals("meta")) {
                if (size > MAX_MOOV) {
                    return false;
                }
                final byte[] meta = new byte[(int) size];
                raf.seek(pos);
                raf.readFully(meta);
                final ArrayList<long[]> extents = heifMetadataExtents(meta, header + 4, meta.length, pos);
                if (extents == null) {
                    return false;
                }
                // in-meta data (idat) is written back with the box, file data directly
                boolean metaChanged = false;
                for (long[] e : extents) {
                    final long off = e[0], len = e[1];
                    if (len <= 0 || off < 0 || off + len > length) {
                        return false;
                    }
                    if (off >= pos && off + len <= pos + size) {
                        for (long i = off - pos; i < off - pos + len; i++) {
                            meta[(int) i] = 0;
                        }
                        metaChanged = true;
                    } else {
                        raf.seek(off);
                        final byte[] zeros = new byte[(int) Math.min(len, 1 << 20)];
                        long left = len;
                        while (left > 0) {
                            final int n = (int) Math.min(left, zeros.length);
                            raf.write(zeros, 0, n);
                            left -= n;
                        }
                    }
                }
                if (metaChanged) {
                    raf.seek(pos);
                    raf.write(meta);
                }
                return true;
            }
            pos += size;
        }
        return false;
    }

    /** File offsets and lengths ({offset, length}) of the EXIF/XMP items of a HEIF meta box, null when unreadable. */
    private static ArrayList<long[]> heifMetadataExtents(byte[] b, int from, int to, long metaPos) {
        final ArrayList<Long> ids = new ArrayList<>();
        int iloc = -1, ilocEnd = -1, idat = -1;
        int p = from;
        while (p + 8 <= to) {
            final long size = u32(b, p, false);
            if (size < 8 || p + size > to) {
                return null;
            }
            final String type = new String(b, p + 4, 4, StandardCharsets.ISO_8859_1);
            final int end = (int) (p + size);
            if (type.equals("iinf")) {
                final int version = b[p + 8] & 0xFF;
                int q = p + 12 + (version == 0 ? 2 : 4);
                while (q + 8 <= end) {
                    final long s = u32(b, q, false);
                    if (s < 8 || q + s > end) {
                        return null;
                    }
                    if (new String(b, q + 4, 4, StandardCharsets.ISO_8859_1).equals("infe")) {
                        final int v = b[q + 8] & 0xFF;
                        if (v >= 2) {
                            int r = q + 12;
                            final long id = v == 2 ? u16(b, r, false) : u32(b, r, false);
                            r += v == 2 ? 2 : 4;
                            r += 2; // protection index
                            final String itemType = new String(b, r, 4, StandardCharsets.ISO_8859_1);
                            r += 4;
                            boolean metadata = itemType.equals("Exif");
                            if (itemType.equals("mime")) {
                                // item name, then content type
                                while (r < q + s && b[r] != 0) {
                                    r++;
                                }
                                r++;
                                final int ct = r;
                                while (r < q + s && b[r] != 0) {
                                    r++;
                                }
                                final String contentType = new String(b, ct, Math.max(0, r - ct), StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
                                metadata = contentType.contains("rdf+xml") || contentType.contains("xmp");
                            }
                            if (metadata) {
                                ids.add(id);
                            }
                        }
                    }
                    q += (int) s;
                }
            } else if (type.equals("iloc")) {
                iloc = p;
                ilocEnd = end;
            } else if (type.equals("idat")) {
                idat = p + 8;
            }
            p = end;
        }
        final ArrayList<long[]> extents = new ArrayList<>();
        if (ids.isEmpty()) {
            return extents;
        }
        if (iloc < 0) {
            return null;
        }
        final int version = b[iloc + 8] & 0xFF;
        int q = iloc + 12;
        final int offsetSize = (b[q] >> 4) & 0xF, lengthSize = b[q] & 0xF;
        final int baseOffsetSize = (b[q + 1] >> 4) & 0xF, indexSize = version >= 1 ? b[q + 1] & 0xF : 0;
        q += 2;
        final long count;
        if (version < 2) {
            count = u16(b, q, false);
            q += 2;
        } else {
            count = u32(b, q, false);
            q += 4;
        }
        for (long i = 0; i < count; i++) {
            if (q >= ilocEnd) {
                return null;
            }
            final long id;
            if (version < 2) {
                id = u16(b, q, false);
                q += 2;
            } else {
                id = u32(b, q, false);
                q += 4;
            }
            int method = 0;
            if (version >= 1) {
                method = u16(b, q, false) & 0xF;
                q += 2;
            }
            q += 2; // data reference index
            final long base = uN(b, q, baseOffsetSize);
            q += baseOffsetSize;
            final int extentCount = u16(b, q, false);
            q += 2;
            for (int e = 0; e < extentCount; e++) {
                q += indexSize;
                final long off = uN(b, q, offsetSize);
                q += offsetSize;
                final long len = uN(b, q, lengthSize);
                q += lengthSize;
                if (ids.contains(id)) {
                    if (method == 0) {
                        extents.add(new long[]{base + off, len});
                    } else if (method == 1 && idat >= 0) {
                        extents.add(new long[]{metaPos + idat + base + off, len});
                    }
                }
            }
        }
        return extents;
    }

    // ---------------------------------------------------------------- bytes

    private static int u16(byte[] d, int p, boolean le) {
        return le ? (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8) : ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
    }

    private static long u32(byte[] d, int p, boolean le) {
        if (le) {
            return (d[p] & 0xFFL) | ((d[p + 1] & 0xFFL) << 8) | ((d[p + 2] & 0xFFL) << 16) | ((d[p + 3] & 0xFFL) << 24);
        }
        return ((d[p] & 0xFFL) << 24) | ((d[p + 1] & 0xFFL) << 16) | ((d[p + 2] & 0xFFL) << 8) | (d[p + 3] & 0xFFL);
    }

    private static long uN(byte[] d, int p, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) {
            v = (v << 8) | (d[p + i] & 0xFFL);
        }
        return v;
    }

    private static byte[] readAll(File f) throws IOException {
        final byte[] data = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) {
            int n = 0;
            while (n < data.length) {
                final int r = in.read(data, n, data.length - n);
                if (r < 0) {
                    throw new IOException("short read");
                }
                n += r;
            }
        }
        return data;
    }

    private static void copy(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            final byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
        }
    }
}
