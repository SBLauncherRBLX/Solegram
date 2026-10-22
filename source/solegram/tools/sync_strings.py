#!/usr/bin/env python3
"""Inject Solegram strings (solegram/strings/<lang>.xml) into Telegram's strings.xml files.

Strings live between <!-- SOLEGRAM BEGIN --> / <!-- SOLEGRAM END --> markers so the
upstream files can still be merged from DrKLO/Telegram. Keys that already exist upstream
(e.g. AppName) are overridden in place instead of duplicated.
"""
import os, re, sys
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "TMessagesProj", "src", "main", "res")
SRC = os.path.join(ROOT, "solegram", "strings")
TARGETS = {"en": "values", "ru": "values-ru", "uk": "values-uk"}
BEGIN, END = "    <!-- SOLEGRAM BEGIN -->\n", "    <!-- SOLEGRAM END -->\n"

def load(lang):
    path = os.path.join(SRC, f"{lang}.xml")
    if not os.path.exists(path):
        return []
    raw = open(path, encoding="utf-8").read()
    ET.fromstring(raw)  # validate
    return re.findall(r'(<string name="([^"]+)"[^>]*>.*?</string>)', raw, flags=re.S)

def main():
    en = load("en")
    en_keys = [k for _, k in en]
    for lang, folder in TARGETS.items():
        path = os.path.join(RES, folder, "strings.xml")
        if not os.path.exists(path):
            continue
        entries = dict((k, e) for e, k in load(lang))
        if lang != "en":
            # fall back to English for strings that are not translated yet
            for e, k in en:
                entries.setdefault(k, e)
        text = open(path, encoding="utf-8").read()
        text = re.sub(re.escape(BEGIN) + r".*?" + re.escape(END), "", text, flags=re.S)
        block = []
        for k in (en_keys if lang != "en" else [k for _, k in en]):
            e = entries.get(k)
            if e is None:
                continue
            pattern = re.compile(r'\s*<string name="' + re.escape(k) + r'"[^>]*>.*?</string>', re.S)
            if pattern.search(text):
                text = pattern.sub("\n    " + e, text, count=1)
            else:
                block.append("    " + e + "\n")
        idx = text.rstrip().rfind("</resources>")
        text = text[:idx].rstrip("\n") + "\n" + BEGIN + "".join(block) + END + "</resources>\n"
        open(path, "w", encoding="utf-8").write(text)
        print(f"{folder}: {len(block)} Solegram strings")

if __name__ == "__main__":
    main()
