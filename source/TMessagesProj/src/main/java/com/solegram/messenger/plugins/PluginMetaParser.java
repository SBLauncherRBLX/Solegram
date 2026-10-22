/*
 * This is the source code of Solegram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 */

package com.solegram.messenger.plugins;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Static parser for plugin metadata. Like the exteraGram loader it never executes the plugin:
 * it only reads top-level assignments such as {@code __id__ = "x"} whose values are string
 * literals (single, double or triple quoted, optionally concatenated / parenthesised) or lists of
 * string literals.
 */
public final class PluginMetaParser {

    private PluginMetaParser() {
    }

    public static PluginMeta parse(File file) throws Exception {
        byte[] data;
        try (InputStream in = new FileInputStream(file)) {
            data = readAll(in);
        }
        return parse(new String(data, StandardCharsets.UTF_8));
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) {
            out.write(buf, 0, r);
            if (out.size() > 8 * 1024 * 1024) {
                throw new IllegalArgumentException("Plugin file is too large");
            }
        }
        return out.toByteArray();
    }

    public static PluginMeta parse(String src) {
        PluginMeta meta = new PluginMeta();
        if (src.startsWith("﻿")) {
            src = src.substring(1);
        }
        int pos = 0;
        int len = src.length();
        while (pos < len) {
            int lineEnd = src.indexOf('\n', pos);
            if (lineEnd < 0) {
                lineEnd = len;
            }
            if (pos < len && src.startsWith("__", pos)) {
                int eq = src.indexOf('=', pos);
                if (eq > 0 && eq < lineEnd) {
                    String name = src.substring(pos, eq).trim();
                    if (name.matches("__[a-z_]+__") && (eq + 1 >= len || src.charAt(eq + 1) != '=')) {
                        int[] end = new int[1];
                        Object value = parseValue(src, eq + 1, end);
                        if (value != null) {
                            apply(meta, name, value);
                            int next = src.indexOf('\n', end[0]);
                            pos = next < 0 ? len : next + 1;
                            continue;
                        }
                    }
                }
            } else if (pos < len && (src.startsWith("\"\"\"", pos) || src.startsWith("'''", pos))) {
                // skip module-level docstrings
                String q = src.substring(pos, pos + 3);
                int close = src.indexOf(q, pos + 3);
                if (close > 0) {
                    int next = src.indexOf('\n', close + 3);
                    pos = next < 0 ? len : next + 1;
                    continue;
                }
            }
            pos = lineEnd + 1;
        }
        if (meta.name == null) {
            meta.name = meta.id;
        }
        return meta;
    }

    @SuppressWarnings("unchecked")
    private static void apply(PluginMeta meta, String name, Object value) {
        switch (name) {
            case "__id__":
                meta.id = str(value);
                break;
            case "__name__":
                meta.name = str(value);
                break;
            case "__description__":
                meta.description = str(value);
                break;
            case "__author__":
                meta.author = str(value);
                break;
            case "__version__":
                meta.version = str(value);
                break;
            case "__icon__":
                meta.icon = str(value);
                break;
            case "__app_version__":
                meta.appVersion = str(value);
                break;
            case "__min_version__":
                if (meta.appVersion == null) {
                    String v = str(value);
                    meta.appVersion = v != null && !v.isEmpty() && Character.isDigit(v.charAt(0)) ? ">=" + v : v;
                }
                break;
            case "__sdk_version__":
                meta.sdkVersion = str(value);
                break;
            case "__requirements__":
                if (value instanceof List) {
                    meta.requirements = (List<String>) value;
                } else if (value instanceof String) {
                    ArrayList<String> l = new ArrayList<>();
                    l.add((String) value);
                    meta.requirements = l;
                }
                break;
        }
    }

    private static String str(Object v) {
        return v instanceof String ? (String) v : null;
    }

    private static int skipSpaces(String s, int i, boolean newlines) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || (newlines && c == '\n')) {
                i++;
            } else if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                i += 2;
            } else if (newlines && c == '#') {
                while (i < s.length() && s.charAt(i) != '\n') {
                    i++;
                }
            } else {
                break;
            }
        }
        return i;
    }

    private static Object parseValue(String s, int i, int[] end) {
        i = skipSpaces(s, i, false);
        if (i >= s.length()) {
            return null;
        }
        char c = s.charAt(i);
        if (c == '[' || c == '(') {
            char close = c == '[' ? ']' : ')';
            i++;
            ArrayList<String> list = new ArrayList<>();
            StringBuilder concat = null;
            boolean parenString = c == '(';
            while (true) {
                i = skipSpaces(s, i, true);
                if (i >= s.length()) {
                    return null;
                }
                char ch = s.charAt(i);
                if (ch == close) {
                    end[0] = i + 1;
                    if (parenString && concat != null && list.isEmpty()) {
                        return concat.toString();
                    }
                    if (concat != null) {
                        list.add(concat.toString());
                    }
                    return list;
                }
                if (ch == ',') {
                    parenString = false;
                    if (concat != null) {
                        list.add(concat.toString());
                        concat = null;
                    }
                    i++;
                    continue;
                }
                int[] e = new int[1];
                String lit = parseString(s, i, e);
                if (lit == null) {
                    return null;
                }
                if (concat == null) {
                    concat = new StringBuilder();
                }
                concat.append(lit);
                i = e[0];
            }
        }
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        while (true) {
            int[] e = new int[1];
            String lit = parseString(s, i, e);
            if (lit == null) {
                break;
            }
            any = true;
            sb.append(lit);
            i = e[0];
            int j = skipSpaces(s, i, false);
            if (j < s.length() && isStringStart(s, j)) {
                i = j;
            } else {
                break;
            }
        }
        if (!any) {
            return null;
        }
        end[0] = i;
        return sb.toString();
    }

    private static boolean isStringStart(String s, int i) {
        char c = s.charAt(i);
        if (c == '"' || c == '\'') {
            return true;
        }
        if ((c == 'r' || c == 'R' || c == 'u' || c == 'U') && i + 1 < s.length()) {
            char n = s.charAt(i + 1);
            return n == '"' || n == '\'';
        }
        return false;
    }

    private static String parseString(String s, int i, int[] end) {
        if (i >= s.length() || !isStringStart(s, i)) {
            return null;
        }
        boolean raw = false;
        char c = s.charAt(i);
        if (c == 'r' || c == 'R') {
            raw = true;
            i++;
        } else if (c == 'u' || c == 'U') {
            i++;
        }
        char q = s.charAt(i);
        boolean triple = s.startsWith(String.valueOf(q) + q + q, i);
        String terminator = triple ? String.valueOf(q) + q + q : String.valueOf(q);
        i += terminator.length();
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            if (s.startsWith(terminator, i)) {
                end[0] = i + terminator.length();
                return sb.toString();
            }
            char ch = s.charAt(i);
            if (ch == '\n' && !triple) {
                return null;
            }
            if (ch == '\\' && !raw && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case 'n':
                        sb.append('\n');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '\'':
                        sb.append('\'');
                        break;
                    case '"':
                        sb.append('"');
                        break;
                    case '\n':
                        break;
                    case 'u':
                        if (i + 5 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                                i += 4;
                                break;
                            } catch (NumberFormatException ignore) {
                            }
                        }
                        sb.append("\\u");
                        break;
                    default:
                        sb.append('\\').append(n);
                }
                i += 2;
                continue;
            }
            sb.append(ch);
            i++;
        }
        return null;
    }
}
