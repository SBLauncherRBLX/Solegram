/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.soledrop;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * The SoleDrop handshake over NFC, without anything of Android in it (a JVM harness runs it as it is).
 * <p>
 * The receiving phone emulates a card (host card emulation) under a private AID, the sending phone reads it
 * (reader mode, ISO-DEP). One tap is a handful of short APDUs, well under a second:
 * <pre>
 *   SELECT AID                         -> 'S' 'D' version flags         9000   (6985: receiving is off now)
 *   OFFER  80 10 index total [chunk]   ->                                9000   (a chunk of the sender's offer)
 *   IDENTITY 80 20 index 00 Le         -> total [chunk]                  9000   (the receiver's identity)
 *   ACK    80 30 status 00 [packet]    ->                                9000
 * </pre>
 * Nothing big goes over NFC: the offer is what is sent (a type and a short preview title) and who sends it
 * (Telegram contact token, id, name), the identity is who receives. The transfer itself then goes through
 * Telegram. Packets are binary: magic "SD", a version byte, the kind, then tag-length-value fields; unknown
 * tags are skipped, so a newer phone still talks to an older one. Every APDU stays under {@link #CHUNK} bytes
 * of data (short APDUs, safe on every controller); anything longer is chunked.
 */
public final class SoleDropProtocol {

    private SoleDropProtocol() {
    }

    /** "SOLEDROP" under the proprietary RID prefix F0: no other app answers it. Also in res/xml/soledrop_apdu_service.xml. */
    public static final byte[] AID = {(byte) 0xF0, 0x53, 0x4F, 0x4C, 0x45, 0x44, 0x52, 0x4F, 0x50};

    public static final int VERSION = 1;
    /** Data bytes per APDU: short APDUs (Lc and Le up to 255) with room to spare on every NFC controller. */
    public static final int CHUNK = 240;
    /** Chunks per packet and bytes per packet: an offer or identity never needs more, anything bigger is junk. */
    public static final int MAX_CHUNKS = 6;
    public static final int MAX_PACKET = 1024;

    // region Status words

    public static final int SW_OK = 0x9000;
    public static final int SW_WRONG_LENGTH = 0x6700;
    /** Receiving is off at the moment (the app is not open, the phone is locked, too many taps). */
    public static final int SW_NOT_ACCEPTING = 0x6985;
    public static final int SW_WRONG_DATA = 0x6A80;
    public static final int SW_NOT_FOUND = 0x6A82;
    public static final int SW_WRONG_P1P2 = 0x6B00;
    public static final int SW_INS_NOT_SUPPORTED = 0x6D00;
    public static final int SW_CLA_NOT_SUPPORTED = 0x6E00;
    public static final int SW_SEQUENCE = 0x6986;
    /**
     * The other phone may receive but asks its owner first (receiving "always" with the app closed): nothing about
     * it is given; after its owner accepts, a second tap within a minute gets the identity.
     */
    public static final int SW_NEEDS_CONFIRMATION = 0x6982;
    // endregion

    // region Commands

    public static final byte CLA = (byte) 0x80;
    public static final byte INS_OFFER = 0x10;
    public static final byte INS_IDENTITY = 0x20;
    public static final byte INS_ACK = 0x30;
    // endregion

    // region Packets

    public static final int KIND_OFFER = 1;
    public static final int KIND_IDENTITY = 2;
    public static final int KIND_ACK = 3;

    /** What the offer is. The order is fixed: it goes over the air. */
    public static final int ITEM_MESSAGES = 1;
    public static final int ITEM_MY_ACCOUNT = 2;
    public static final int ITEM_PEER = 3;
    public static final int ITEM_STARS = 4;
    public static final int ITEM_GIFT = 5;
    public static final int ITEM_PHOTO = 6;
    public static final int ITEM_LOCATION = 7;
    public static final int ITEM_LINK = 8;
    public static final int ITEM_STICKERS = 9;
    public static final int ITEM_CONTACT = 10;
    public static final int ITEM_MAX = 10;

    /** Offer flags. */
    public static final int FLAG_NAMEDROP = 1;
    /** Identity flags. */
    public static final int FLAG_ACCEPTING = 1;
    /** Ack status: the sender got the identity and goes on through Telegram. */
    public static final int ACK_OK = 0;
    public static final int ACK_SELF = 1;

    private static final int TAG_SESSION = 0x01;
    private static final int TAG_ITEM = 0x02;
    private static final int TAG_TITLE = 0x03;
    private static final int TAG_USER_ID = 0x04;
    private static final int TAG_NAME = 0x05;
    private static final int TAG_USERNAME = 0x06;
    private static final int TAG_TOKEN = 0x07;
    private static final int TAG_FLAGS = 0x08;
    private static final int TAG_STATUS = 0x09;
    private static final int TAG_DETAIL = 0x0A;

    /** Longest values, in UTF-8 bytes: a preview title is a line, not the message. */
    public static final int MAX_TITLE = 96;
    public static final int MAX_DETAIL = 48;
    public static final int MAX_NAME = 64;
    public static final int MAX_USERNAME = 32;
    public static final int MAX_TOKEN = 128;

    /** Thrown for anything that is not a well formed SoleDrop packet or exchange. */
    public static class ProtocolException extends IOException {
        public final int statusWord;

        public ProtocolException(String message) {
            this(message, 0);
        }

        public ProtocolException(String message, int statusWord) {
            super(message);
            this.statusWord = statusWord;
        }
    }

    /** One packet: an offer, an identity or an ack (which fields mean something depends on the kind). */
    public static final class Packet {
        public int kind;
        public int version = VERSION;
        public int session;
        public int item;
        public int flags;
        public int status;
        public long userId;
        /** Short preview of what is sent ("Фото", the first words of a message), and a detail ("3 messages"). */
        public String title;
        public String detail;
        public String name;
        public String username;
        /** Telegram contact token (contacts.exportContactToken), resolvable with contacts.importContactToken. */
        public String token;

        public static Packet offer(int session, int item, String title, String detail) {
            final Packet p = new Packet();
            p.kind = KIND_OFFER;
            p.session = session;
            p.item = item;
            p.title = title;
            p.detail = detail;
            return p;
        }

        public static Packet identity(int session) {
            final Packet p = new Packet();
            p.kind = KIND_IDENTITY;
            p.session = session;
            return p;
        }

        public static Packet ack(int session, int status) {
            final Packet p = new Packet();
            p.kind = KIND_ACK;
            p.session = session;
            p.status = status;
            return p;
        }

        public Packet sender(long userId, String name, String username, String token) {
            this.userId = userId;
            this.name = name;
            this.username = username;
            this.token = token;
            return this;
        }

        public boolean isNameDrop() {
            return (flags & FLAG_NAMEDROP) != 0;
        }

        public byte[] encode() {
            final Writer w = new Writer();
            w.raw('S');
            w.raw('D');
            w.raw(version);
            w.raw(kind);
            w.int32(TAG_SESSION, session);
            if (kind == KIND_OFFER) {
                w.byte1(TAG_ITEM, item);
                w.string(TAG_TITLE, title, MAX_TITLE);
                w.string(TAG_DETAIL, detail, MAX_DETAIL);
            }
            if (kind == KIND_ACK) {
                w.byte1(TAG_STATUS, status);
            } else {
                w.byte1(TAG_FLAGS, flags);
                if (userId != 0) {
                    w.int64(TAG_USER_ID, userId);
                }
                w.string(TAG_NAME, name, MAX_NAME);
                w.string(TAG_USERNAME, username, MAX_USERNAME);
                w.string(TAG_TOKEN, token, MAX_TOKEN);
            }
            return w.toByteArray();
        }

        public static Packet decode(byte[] data, int length) throws ProtocolException {
            if (data == null || length < 4 || length > MAX_PACKET || length > data.length) {
                throw new ProtocolException("bad length");
            }
            if (data[0] != 'S' || data[1] != 'D') {
                throw new ProtocolException("bad magic");
            }
            final Packet p = new Packet();
            p.version = data[2] & 0xFF;
            p.kind = data[3] & 0xFF;
            if (p.version < 1) {
                throw new ProtocolException("bad version");
            }
            if (p.kind != KIND_OFFER && p.kind != KIND_IDENTITY && p.kind != KIND_ACK) {
                throw new ProtocolException("bad kind");
            }
            boolean hasSession = false;
            int pos = 4;
            while (pos < length) {
                if (pos + 2 > length) {
                    throw new ProtocolException("truncated field");
                }
                final int tag = data[pos] & 0xFF;
                final int len = data[pos + 1] & 0xFF;
                pos += 2;
                if (pos + len > length) {
                    throw new ProtocolException("truncated value");
                }
                switch (tag) {
                    case TAG_SESSION:
                        p.session = (int) readFixed(data, pos, len, 4);
                        hasSession = true;
                        break;
                    case TAG_ITEM:
                        p.item = (int) readFixed(data, pos, len, 1);
                        break;
                    case TAG_FLAGS:
                        p.flags = (int) readFixed(data, pos, len, 1);
                        break;
                    case TAG_STATUS:
                        p.status = (int) readFixed(data, pos, len, 1);
                        break;
                    case TAG_USER_ID:
                        p.userId = readFixed(data, pos, len, 8);
                        break;
                    case TAG_TITLE:
                        p.title = readString(data, pos, len, MAX_TITLE);
                        break;
                    case TAG_DETAIL:
                        p.detail = readString(data, pos, len, MAX_DETAIL);
                        break;
                    case TAG_NAME:
                        p.name = readString(data, pos, len, MAX_NAME);
                        break;
                    case TAG_USERNAME:
                        p.username = readString(data, pos, len, MAX_USERNAME);
                        if (p.username != null && !isUsername(p.username)) {
                            throw new ProtocolException("bad username");
                        }
                        break;
                    case TAG_TOKEN:
                        p.token = readString(data, pos, len, MAX_TOKEN);
                        if (p.token != null && !isToken(p.token)) {
                            throw new ProtocolException("bad token");
                        }
                        break;
                    default:
                        // a field of a newer version: skipped
                        break;
                }
                pos += len;
            }
            if (!hasSession) {
                throw new ProtocolException("no session");
            }
            if (p.kind == KIND_OFFER && (p.item < 1 || p.item > 255)) {
                throw new ProtocolException("bad item");
            }
            if (p.userId < 0) {
                throw new ProtocolException("bad user");
            }
            return p;
        }

        public static Packet decode(byte[] data) throws ProtocolException {
            return decode(data, data == null ? 0 : data.length);
        }
    }

    private static long readFixed(byte[] data, int pos, int len, int expected) throws ProtocolException {
        if (len != expected) {
            throw new ProtocolException("bad fixed length");
        }
        long v = 0;
        for (int i = 0; i < len; i++) {
            v = (v << 8) | (data[pos + i] & 0xFF);
        }
        return v;
    }

    private static String readString(byte[] data, int pos, int len, int max) throws ProtocolException {
        if (len == 0) {
            return null;
        }
        if (len > max) {
            throw new ProtocolException("string too long");
        }
        final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            final CharBuffer chars = decoder.decode(ByteBuffer.wrap(data, pos, len));
            final String s = chars.toString();
            for (int i = 0; i < s.length(); i++) {
                final char c = s.charAt(i);
                // no control characters: the text is shown as it is
                if (c < 0x20 || c == 0x7F || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)) {
                    throw new ProtocolException("control character");
                }
            }
            return s;
        } catch (CharacterCodingException e) {
            throw new ProtocolException("bad utf-8");
        }
    }

    /** Telegram usernames: letters, digits and underscores. */
    public static boolean isUsername(String s) {
        if (s.length() < 4 || s.length() > MAX_USERNAME) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /** Contact tokens are url-safe base64; plain base64 and url escapes are accepted too, so a new format still works. */
    public static boolean isToken(String s) {
        if (s.length() < 8 || s.length() > MAX_TOKEN) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '=' || c == '+' || c == '/' || c == '.' || c == '~' || c == '%')) {
                return false;
            }
        }
        return true;
    }

    /**
     * The token of a contact link: https://t.me/contact/TOKEN (any Telegram domain, with or without the
     * scheme) or tg://contact?token=TOKEN. Null for anything else.
     */
    public static String tokenFromLink(String link) {
        if (link == null) {
            return null;
        }
        String s = link.trim();
        final int q = s.indexOf("token=");
        if (s.startsWith("tg://contact") && q > 0) {
            s = s.substring(q + 6);
            final int amp = s.indexOf('&');
            if (amp >= 0) {
                s = s.substring(0, amp);
            }
            return isToken(s) ? s : null;
        }
        s = s.replaceFirst("^(?i)https?://", "");
        final int slash = s.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        final String host = s.substring(0, slash).toLowerCase(java.util.Locale.ROOT);
        if (!host.equals("t.me") && !host.equals("telegram.me") && !host.equals("telegram.dog") && !host.endsWith(".t.me")) {
            return null;
        }
        String path = s.substring(slash + 1);
        if (!path.startsWith("contact/")) {
            return null;
        }
        path = path.substring("contact/".length());
        final int end = indexOfAny(path, "/?#");
        if (end >= 0) {
            path = path.substring(0, end);
        }
        return isToken(path) ? path : null;
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /** Field writer: strings are cut at a character boundary to their limit. */
    private static final class Writer {
        private byte[] buf = new byte[256];
        private int len;

        void raw(int b) {
            ensure(1);
            buf[len++] = (byte) b;
        }

        void byte1(int tag, int v) {
            raw(tag);
            raw(1);
            raw(v);
        }

        void int32(int tag, int v) {
            raw(tag);
            raw(4);
            for (int i = 3; i >= 0; i--) {
                raw(v >>> (i * 8));
            }
        }

        void int64(int tag, long v) {
            raw(tag);
            raw(8);
            for (int i = 7; i >= 0; i--) {
                raw((int) (v >>> (i * 8)));
            }
        }

        void string(int tag, String s, int max) {
            if (s == null || s.isEmpty()) {
                return;
            }
            final byte[] bytes = utf8Prefix(clean(s), max);
            if (bytes.length == 0) {
                return;
            }
            raw(tag);
            raw(bytes.length);
            ensure(bytes.length);
            System.arraycopy(bytes, 0, buf, len, bytes.length);
            len += bytes.length;
        }

        private void ensure(int n) {
            if (len + n > buf.length) {
                final byte[] bigger = new byte[Math.max(buf.length * 2, len + n)];
                System.arraycopy(buf, 0, bigger, 0, len);
                buf = bigger;
            }
        }

        byte[] toByteArray() {
            final byte[] out = new byte[len];
            System.arraycopy(buf, 0, out, 0, len);
            return out;
        }
    }

    /** Line breaks and control characters become spaces: a preview is one line. */
    static String clean(String s) {
        final StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F || (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069)) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                    sb.append(' ');
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /** The longest prefix of {@code s} that fits {@code max} UTF-8 bytes without cutting a character. */
    static byte[] utf8Prefix(String s, int max) {
        final byte[] all = s.getBytes(StandardCharsets.UTF_8);
        if (all.length <= max) {
            return all;
        }
        int end = max;
        // step back over continuation bytes (10xxxxxx) to the start of the cut character
        while (end > 0 && (all[end] & 0xC0) == 0x80) {
            end--;
        }
        // and keep surrogate pairs whole (a 4-byte character is either in or out, as decided above)
        final byte[] out = new byte[end];
        System.arraycopy(all, 0, out, 0, end);
        return out;
    }
    // endregion

    // region APDUs

    public static byte[] selectApdu() {
        final byte[] apdu = new byte[6 + AID.length];
        apdu[0] = 0x00;
        apdu[1] = (byte) 0xA4;
        apdu[2] = 0x04;
        apdu[3] = 0x00;
        apdu[4] = (byte) AID.length;
        System.arraycopy(AID, 0, apdu, 5, AID.length);
        apdu[apdu.length - 1] = 0x00;
        return apdu;
    }

    public static boolean isSelect(byte[] apdu) {
        if (apdu == null || apdu.length < 5 + AID.length || apdu[0] != 0x00 || apdu[1] != (byte) 0xA4 || apdu[2] != 0x04) {
            return false;
        }
        if ((apdu[4] & 0xFF) != AID.length) {
            return false;
        }
        for (int i = 0; i < AID.length; i++) {
            if (apdu[5 + i] != AID[i]) {
                return false;
            }
        }
        return true;
    }

    /** Number of chunks a packet of this length is split into. */
    public static int chunkCount(int length) {
        return Math.max(1, (length + CHUNK - 1) / CHUNK);
    }

    /** Command with data: CLA INS P1 P2 Lc data (no Le: the answer is only a status word). */
    public static byte[] dataApdu(byte ins, int p1, int p2, byte[] data, int offset, int length) {
        final byte[] apdu = new byte[5 + length];
        apdu[0] = CLA;
        apdu[1] = ins;
        apdu[2] = (byte) p1;
        apdu[3] = (byte) p2;
        apdu[4] = (byte) length;
        System.arraycopy(data, offset, apdu, 5, length);
        return apdu;
    }

    public static byte[] offerApdu(byte[] packet, int index) {
        final int total = chunkCount(packet.length);
        final int from = index * CHUNK;
        return dataApdu(INS_OFFER, index, total, packet, from, Math.min(CHUNK, packet.length - from));
    }

    public static byte[] identityApdu(int index) {
        return new byte[]{CLA, INS_IDENTITY, (byte) index, 0x00, 0x00};
    }

    public static byte[] ackApdu(byte[] packet, int status) {
        return dataApdu(INS_ACK, status, 0, packet, 0, packet.length);
    }

    public static byte[] status(int sw) {
        return new byte[]{(byte) (sw >> 8), (byte) sw};
    }

    public static byte[] withStatus(byte[] data, int offset, int length, int sw) {
        final byte[] out = new byte[length + 2];
        System.arraycopy(data, offset, out, 0, length);
        out[length] = (byte) (sw >> 8);
        out[length + 1] = (byte) sw;
        return out;
    }

    public static int statusOf(byte[] response) {
        if (response == null || response.length < 2) {
            return 0;
        }
        return ((response[response.length - 2] & 0xFF) << 8) | (response[response.length - 1] & 0xFF);
    }
    // endregion

    // region The card (the receiving phone)

    /** What the card's host decides about a whole, valid offer: answer it, ask the owner first, or refuse. */
    public static final int GATE_ALLOW = 0, GATE_CONFIRM = 1, GATE_DENY = 2;

    /** What the card asks of the app that runs it (receive settings, identity, what to show). */
    public interface CardHost {
        /** Receiving is allowed right now (receive mode, the app is open or the phone unlocked, not too many taps). */
        boolean acceptsNow();

        /**
         * Whether this offer gets the identity ({@link #GATE_ALLOW}), its owner is asked first and the reader learns
         * nothing ({@link #GATE_CONFIRM}), or it is refused ({@link #GATE_DENY}). Only ever asked after a whole,
         * valid offer: a reader that does not offer anything never gets near the identity.
         */
        int gate(Packet offer);

        /** The owner is to be asked about this offer (after {@link #GATE_CONFIRM}). */
        void onConfirmationNeeded(Packet offer);

        /** This phone's identity for the session (encoded {@link Packet} of {@link #KIND_IDENTITY}), null when it has none. */
        byte[] identity(Packet offer);

        /** A whole, valid offer came, the identity was read and the reader confirmed it (or the tap ended after the identity). */
        void onExchanged(Packet offer, boolean acked);
    }

    /**
     * The state of one tap on the receiving side: it takes command APDUs and gives response APDUs. Malformed
     * or out of order commands are refused with a status word and the exchange starts over; nothing reaches the
     * app before a whole, valid offer and the reading of the identity.
     */
    public static final class Card {
        private final CardHost host;
        private boolean selected;
        private final byte[] offerBuf = new byte[MAX_CHUNKS * CHUNK];
        private int offerLen, offerTotal, offerNext;
        private Packet offer;
        private byte[] identity;
        private boolean identityRead, delivered;

        public Card(CardHost host) {
            this.host = host;
        }

        private void reset() {
            offerLen = 0;
            offerTotal = 0;
            offerNext = 0;
            offer = null;
            identity = null;
            identityRead = false;
            delivered = false;
        }

        public byte[] process(byte[] apdu) {
            try {
                return processInternal(apdu);
            } catch (RuntimeException e) {
                reset();
                return status(SW_WRONG_DATA);
            }
        }

        private byte[] processInternal(byte[] apdu) {
            if (apdu == null || apdu.length < 4) {
                return status(SW_WRONG_LENGTH);
            }
            if (isSelect(apdu)) {
                reset();
                if (!host.acceptsNow()) {
                    selected = false;
                    return status(SW_NOT_ACCEPTING);
                }
                selected = true;
                return new byte[]{'S', 'D', (byte) VERSION, (byte) FLAG_ACCEPTING, (byte) 0x90, 0x00};
            }
            if (apdu[0] == 0x00 && apdu[1] == (byte) 0xA4) {
                return status(SW_NOT_FOUND); // another AID
            }
            if (!selected) {
                return status(SW_SEQUENCE);
            }
            if (apdu[0] != CLA) {
                return status(SW_CLA_NOT_SUPPORTED);
            }
            final int p1 = apdu[2] & 0xFF, p2 = apdu[3] & 0xFF;
            switch (apdu[1]) {
                case INS_OFFER: {
                    final int lc = apdu.length > 4 ? apdu[4] & 0xFF : 0;
                    if (lc == 0 || apdu.length != 5 + lc || lc > CHUNK) {
                        reset();
                        return status(SW_WRONG_LENGTH);
                    }
                    if (p2 < 1 || p2 > MAX_CHUNKS || p1 >= p2) {
                        reset();
                        return status(SW_WRONG_P1P2);
                    }
                    if (p1 == 0) {
                        reset(); // a new offer (the reader started over)
                        offerTotal = p2;
                    } else if (p1 != offerNext || p2 != offerTotal) {
                        reset();
                        return status(SW_SEQUENCE);
                    }
                    if (p1 < p2 - 1 && lc != CHUNK) {
                        reset(); // only the last chunk may be short
                        return status(SW_WRONG_LENGTH);
                    }
                    System.arraycopy(apdu, 5, offerBuf, offerLen, lc);
                    offerLen += lc;
                    offerNext = p1 + 1;
                    if (offerNext == offerTotal) {
                        try {
                            final Packet p = Packet.decode(offerBuf, offerLen);
                            if (p.kind != KIND_OFFER) {
                                throw new ProtocolException("not an offer");
                            }
                            offer = p;
                        } catch (ProtocolException e) {
                            reset();
                            return status(SW_WRONG_DATA);
                        }
                        final int gate = host.gate(offer);
                        if (gate == GATE_CONFIRM) {
                            final Packet asked = offer;
                            reset();
                            host.onConfirmationNeeded(asked);
                            return status(SW_NEEDS_CONFIRMATION);
                        }
                        if (gate != GATE_ALLOW) {
                            reset();
                            return status(SW_NOT_ACCEPTING);
                        }
                        identity = host.identity(offer);
                        if (identity == null) {
                            reset();
                            return status(SW_NOT_ACCEPTING);
                        }
                    }
                    return status(SW_OK);
                }
                case INS_IDENTITY: {
                    if (offer == null || identity == null) {
                        return status(SW_SEQUENCE);
                    }
                    final int total = chunkCount(identity.length);
                    if (p1 >= total || p2 != 0) {
                        return status(SW_WRONG_P1P2);
                    }
                    final int from = p1 * CHUNK;
                    final int len = Math.min(CHUNK, identity.length - from);
                    final byte[] out = new byte[1 + len + 2];
                    out[0] = (byte) total;
                    System.arraycopy(identity, from, out, 1, len);
                    out[out.length - 2] = (byte) 0x90;
                    out[out.length - 1] = 0x00;
                    if (p1 == total - 1) {
                        identityRead = true;
                    }
                    return out;
                }
                case INS_ACK: {
                    if (offer == null || !identityRead) {
                        return status(SW_SEQUENCE);
                    }
                    final int lc = apdu.length > 4 ? apdu[4] & 0xFF : 0;
                    if (apdu.length != 5 + lc || lc > CHUNK) {
                        return status(SW_WRONG_LENGTH);
                    }
                    try {
                        final Packet ack = Packet.decode(java.util.Arrays.copyOfRange(apdu, 5, 5 + lc));
                        if (ack.kind != KIND_ACK || ack.session != offer.session) {
                            return status(SW_WRONG_DATA);
                        }
                    } catch (ProtocolException e) {
                        return status(SW_WRONG_DATA);
                    }
                    if (!delivered && p1 == ACK_OK) {
                        delivered = true;
                        host.onExchanged(offer, true);
                    }
                    return status(SW_OK);
                }
                default:
                    return status(SW_INS_NOT_SUPPORTED);
            }
        }

        /** The field went away: an exchange that got as far as reading the identity still counts. */
        public void deactivated() {
            if (offer != null && identityRead && !delivered) {
                delivered = true;
                host.onExchanged(offer, false);
            }
            selected = false;
            reset();
        }
    }
    // endregion

    // region The reader (the sending phone)

    public interface Transceiver {
        byte[] transceive(byte[] command) throws IOException;
    }

    /**
     * One whole tap from the reader's side: select, the offer, the identity, the ack.
     *
     * @return the receiver's identity
     * @throws ProtocolException with {@link #SW_NOT_ACCEPTING} when the other phone does not receive now
     */
    public static Packet exchange(Transceiver t, Packet offer) throws IOException {
        return exchange(t, offer, null);
    }

    /**
     * @param onSelected called (on the reader's thread) as soon as the other side answered as a SoleDrop phone
     *                   that receives: the moment of contact, before the rest of the exchange
     */
    public static Packet exchange(Transceiver t, Packet offer, Runnable onSelected) throws IOException {
        byte[] r = t.transceive(selectApdu());
        int sw = statusOf(r);
        if (sw == SW_NOT_ACCEPTING) {
            throw new ProtocolException("not accepting", sw);
        }
        if (sw != SW_OK || r.length < 6 || r[0] != 'S' || r[1] != 'D') {
            throw new ProtocolException("select failed", sw);
        }
        if (onSelected != null) {
            onSelected.run();
        }
        final byte[] packet = offer.encode();
        if (packet.length > MAX_PACKET) {
            throw new ProtocolException("offer too big");
        }
        final int chunks = chunkCount(packet.length);
        for (int i = 0; i < chunks; i++) {
            sw = statusOf(t.transceive(offerApdu(packet, i)));
            if (sw != SW_OK) {
                throw new ProtocolException("offer refused", sw);
            }
        }
        final byte[] identity = new byte[MAX_PACKET];
        int identityLen = 0, total = 1;
        for (int i = 0; i < total; i++) {
            r = t.transceive(identityApdu(i));
            sw = statusOf(r);
            if (sw != SW_OK || r.length < 3) {
                throw new ProtocolException("identity refused", sw);
            }
            final int count = r[0] & 0xFF;
            if (i == 0) {
                total = count;
                if (total < 1 || total > MAX_CHUNKS) {
                    throw new ProtocolException("bad identity chunks");
                }
            } else if (count != total) {
                throw new ProtocolException("identity chunks changed");
            }
            final int len = r.length - 3;
            if (len > CHUNK || identityLen + len > identity.length || (i < total - 1 && len != CHUNK)) {
                throw new ProtocolException("bad identity chunk");
            }
            System.arraycopy(r, 1, identity, identityLen, len);
            identityLen += len;
        }
        final Packet id = Packet.decode(identity, identityLen);
        if (id.kind != KIND_IDENTITY || id.session != offer.session) {
            throw new ProtocolException("not our identity");
        }
        final int status = offer.userId != 0 && offer.userId == id.userId ? ACK_SELF : ACK_OK;
        try {
            t.transceive(ackApdu(Packet.ack(offer.session, status).encode(), status));
        } catch (IOException ignore) {
            // the identity is what matters; a tap that ended before the ack is still a tap
        }
        return id;
    }
    // endregion

    // region Rate limit

    /** At most {@code max} events in any {@code windowMs}; the clock is passed in (testable). */
    public static final class RateLimit {
        private final long[] times;
        private final long windowMs;
        private int head, count;

        public RateLimit(int max, long windowMs) {
            this.times = new long[max];
            this.windowMs = windowMs;
        }

        public synchronized boolean tryAcquire(long now) {
            while (count > 0 && now - times[head] >= windowMs) {
                head = (head + 1) % times.length;
                count--;
            }
            if (count >= times.length) {
                return false;
            }
            times[(head + count) % times.length] = now;
            count++;
            return true;
        }
    }
    // endregion
}
