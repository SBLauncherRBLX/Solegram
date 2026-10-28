/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger;

import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Small rules every Solegram HTTP call follows: answers are read up to a cap (a broken or hostile server
 * cannot fill the memory), and a key or message text goes out over https only, except to the user's own
 * machine on the local network (a self-hosted AI service).
 */
public final class SolegramNet {

    private SolegramNet() {
    }

    /** Thrown when an answer is larger than the caller allows. */
    public static final class TooLarge extends IOException {
        public TooLarge() {
            super("response too large");
        }
    }

    /** The whole stream as UTF-8, at most {@code max} bytes; more is an error, not a cut. */
    public static String readString(InputStream in, int max) throws IOException {
        return new String(readBytes(in, max), StandardCharsets.UTF_8);
    }

    public static byte[] readBytes(InputStream in, int max) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 16 * 1024));
        final byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            if (out.size() + read > max) {
                throw new TooLarge();
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * The user's own service (AI summary, transcription): https, or plain http only to this phone or a
     * machine on the local network. Anything else would send the key and the text readable to every hop.
     */
    public static void checkPrivateEndpoint(String url) throws IOException {
        final URL u;
        try {
            u = new URL(url);
        } catch (Exception e) {
            throw new IOException("bad address");
        }
        final String scheme = u.getProtocol() == null ? "" : u.getProtocol().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) {
            return;
        }
        if ("http".equals(scheme) && isLocalHost(u.getHost())) {
            return;
        }
        throw new IOException("https required");
    }

    /** Loopback, link-local, private (RFC 1918 / ULA) addresses and .local names. No DNS lookup is made. */
    public static boolean isLocalHost(String host) {
        if (TextUtils.isEmpty(host)) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if ("localhost".equals(h) || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) {
            return true;
        }
        // only literal addresses are judged: a name could resolve anywhere
        if (!h.matches("\\d{1,3}(\\.\\d{1,3}){3}") && !(h.indexOf(':') >= 0 && h.matches("[0-9a-f:.]+"))) {
            return false;
        }
        try {
            final InetAddress address = InetAddress.getByName(h); // a literal: parsed, not looked up
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address.getAddress().length == 16 && (address.getAddress()[0] & 0xFE) == 0xFC;
        } catch (Exception e) {
            return false;
        }
    }
}
