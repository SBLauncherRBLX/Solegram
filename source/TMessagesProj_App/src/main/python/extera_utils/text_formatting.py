"""HTML / Markdown -> plain text + Telegram message entities (offsets in UTF-16 units)."""
import enum
import re
from dataclasses import dataclass
from html.parser import HTMLParser
from typing import Any, Dict, List, Optional

__all__ = ["parse_text", "TLEntityType", "RawEntity", "parse_html", "parse_markdown_entities"]


class TLEntityType(enum.Enum):
    CODE = "code"
    PRE = "pre"
    STRIKETHROUGH = "strikethrough"
    TEXT_LINK = "text_link"
    BOLD = "bold"
    ITALIC = "italic"
    UNDERLINE = "underline"
    SPOILER = "spoiler"
    CUSTOM_EMOJI = "custom_emoji"
    BLOCKQUOTE = "blockquote"


@dataclass
class RawEntity:
    type: TLEntityType
    offset: int
    length: int
    language: Optional[str] = None
    url: Optional[str] = None
    document_id: Optional[int] = None
    collapsed: bool = False

    def to_tlrpc_object(self):
        from java import jclass
        TLRPC = jclass("org.telegram.tgnet.TLRPC")
        t = self.type
        if t == TLEntityType.BOLD:
            e = TLRPC.TL_messageEntityBold()
        elif t == TLEntityType.ITALIC:
            e = TLRPC.TL_messageEntityItalic()
        elif t == TLEntityType.UNDERLINE:
            e = TLRPC.TL_messageEntityUnderline()
        elif t == TLEntityType.STRIKETHROUGH:
            e = TLRPC.TL_messageEntityStrike()
        elif t == TLEntityType.SPOILER:
            e = TLRPC.TL_messageEntitySpoiler()
        elif t == TLEntityType.CODE:
            e = TLRPC.TL_messageEntityCode()
        elif t == TLEntityType.PRE:
            e = TLRPC.TL_messageEntityPre()
            e.language = self.language or ""
        elif t == TLEntityType.TEXT_LINK:
            e = TLRPC.TL_messageEntityTextUrl()
            e.url = self.url or ""
        elif t == TLEntityType.CUSTOM_EMOJI:
            e = TLRPC.TL_messageEntityCustomEmoji()
            e.document_id = int(self.document_id or 0)
        elif t == TLEntityType.BLOCKQUOTE:
            e = TLRPC.TL_messageEntityBlockquote()
            e.collapsed = bool(self.collapsed)
        else:
            raise ValueError(t)
        e.offset = int(self.offset)
        e.length = int(self.length)
        return e


def _u16(s: str) -> int:
    return len(s.encode("utf-16-le")) // 2


# --------------------------------------------------------------------------- HTML

_TAGS = {
    "b": TLEntityType.BOLD, "strong": TLEntityType.BOLD,
    "i": TLEntityType.ITALIC, "em": TLEntityType.ITALIC,
    "u": TLEntityType.UNDERLINE, "ins": TLEntityType.UNDERLINE,
    "s": TLEntityType.STRIKETHROUGH, "del": TLEntityType.STRIKETHROUGH, "strike": TLEntityType.STRIKETHROUGH,
    "code": TLEntityType.CODE, "pre": TLEntityType.PRE,
    "spoiler": TLEntityType.SPOILER, "tg-spoiler": TLEntityType.SPOILER,
    "a": TLEntityType.TEXT_LINK, "blockquote": TLEntityType.BLOCKQUOTE,
    "emoji": TLEntityType.CUSTOM_EMOJI, "tg-emoji": TLEntityType.CUSTOM_EMOJI,
}


class _HTML(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.text = []
        self.length = 0
        self.stack = []
        self.entities: List[RawEntity] = []

    def handle_starttag(self, tag, attrs):
        tag = tag.lower()
        attrs = dict(attrs)
        if tag == "br":
            self.handle_data("\n")
            return
        if tag == "span" and "tg-spoiler" in (attrs.get("class") or ""):
            tag = "spoiler"
        if tag not in _TAGS:
            return
        self.stack.append((tag, attrs, self.length))

    def handle_startendtag(self, tag, attrs):
        if tag.lower() == "br":
            self.handle_data("\n")

    def handle_endtag(self, tag):
        tag = tag.lower()
        if tag == "span":
            tag = "spoiler"
        for i in range(len(self.stack) - 1, -1, -1):
            if self.stack[i][0] == tag:
                name, attrs, start = self.stack.pop(i)
                length = self.length - start
                if length <= 0:
                    return
                etype = _TAGS[name]
                ent = RawEntity(etype, start, length)
                if etype == TLEntityType.TEXT_LINK:
                    href = attrs.get("href") or ""
                    if not href:
                        return
                    if href.startswith("tg://emoji?id="):
                        ent = RawEntity(TLEntityType.CUSTOM_EMOJI, start, length, document_id=int(href.split("=", 1)[1]))
                    else:
                        ent.url = href
                elif etype == TLEntityType.PRE:
                    ent.language = attrs.get("language") or attrs.get("lang")
                    # <pre><code class="language-x">
                    for e in self.entities:
                        if e.type == TLEntityType.CODE and e.offset == start and e.length == length:
                            self.entities.remove(e)
                            break
                elif etype == TLEntityType.CODE:
                    cls = attrs.get("class") or ""
                    if cls.startswith("language-"):
                        for s in self.stack:
                            if s[0] == "pre":
                                s[1]["language"] = cls[len("language-"):]
                elif etype == TLEntityType.CUSTOM_EMOJI:
                    try:
                        ent.document_id = int(attrs.get("id") or attrs.get("emoji-id") or 0)
                    except ValueError:
                        return
                elif etype == TLEntityType.BLOCKQUOTE:
                    ent.collapsed = "expandable" in attrs or "collapsed" in attrs
                self.entities.append(ent)
                return

    def handle_data(self, data):
        self.text.append(data)
        self.length += _u16(data)


def parse_html(text: str):
    p = _HTML()
    p.feed(text)
    p.close()
    return "".join(p.text), sorted(p.entities, key=lambda e: (e.offset, -e.length))


# --------------------------------------------------------------------------- Markdown

_INLINE = [
    ("```", None), ("**", TLEntityType.BOLD), ("__", TLEntityType.UNDERLINE), ("||", TLEntityType.SPOILER),
    ("~~", TLEntityType.STRIKETHROUGH), ("*", TLEntityType.BOLD), ("_", TLEntityType.ITALIC),
    ("~", TLEntityType.STRIKETHROUGH), ("`", TLEntityType.CODE),
]
_LINK = re.compile(r"!?\[((?:[^\]\\]|\\.)*)\]\(([^)\s]+)\)")


def parse_markdown_entities(text: str):
    out = []
    entities: List[RawEntity] = []
    pos = 0  # utf-16 length of out
    open_marks: Dict[str, int] = {}
    i = 0
    n = len(text)
    line_start = True
    quote_start = None
    quote_collapsed = False

    def emit(s):
        nonlocal pos
        out.append(s)
        pos += _u16(s)

    while i < n:
        ch = text[i]
        if line_start:
            if text.startswith("**>", i):
                if quote_start is None:
                    quote_start = pos
                quote_collapsed = True
                i += 3
                if i < n and text[i] == " ":
                    i += 1
                line_start = False
                continue
            if ch == ">":
                if quote_start is None:
                    quote_start = pos
                i += 1
                if i < n and text[i] == " ":
                    i += 1
                line_start = False
                continue
            if quote_start is not None:
                # quote ended on previous line (strip trailing newline from quote range)
                end = pos - 1 if out and out[-1].endswith("\n") else pos
                if end > quote_start:
                    entities.append(RawEntity(TLEntityType.BLOCKQUOTE, quote_start, end - quote_start, collapsed=quote_collapsed))
                quote_start = None
                quote_collapsed = False
            line_start = False
        if ch == "\\" and i + 1 < n:
            emit(text[i + 1])
            i += 2
            continue
        if ch == "\n":
            emit("\n")
            i += 1
            line_start = True
            continue
        if text.startswith("```", i):
            close = text.find("```", i + 3)
            if close != -1:
                body = text[i + 3:close]
                language = None
                nl = body.find("\n")
                if nl != -1 and re.fullmatch(r"[A-Za-z0-9_+\-#.]*", body[:nl] or "") and body[:nl]:
                    language = body[:nl]
                    body = body[nl + 1:]
                if body.endswith("\n"):
                    body = body[:-1]
                start = pos
                emit(body)
                if pos > start:
                    entities.append(RawEntity(TLEntityType.PRE, start, pos - start, language=language))
                i = close + 3
                continue
        if ch == "`":
            close = text.find("`", i + 1)
            if close != -1:
                start = pos
                emit(text[i + 1:close])
                if pos > start:
                    entities.append(RawEntity(TLEntityType.CODE, start, pos - start))
                i = close + 1
                continue
        if ch in "[!":
            m = _LINK.match(text, i)
            if m:
                label = re.sub(r"\\(.)", r"\1", m.group(1))
                url = m.group(2)
                start = pos
                emit(label)
                if url.startswith("tg://emoji?id="):
                    try:
                        entities.append(RawEntity(TLEntityType.CUSTOM_EMOJI, start, pos - start, document_id=int(url.split("=", 1)[1])))
                    except ValueError:
                        pass
                elif pos > start:
                    entities.append(RawEntity(TLEntityType.TEXT_LINK, start, pos - start, url=url))
                i = m.end()
                continue
        matched = False
        for mark, etype in _INLINE[1:]:
            if mark == "`":
                continue
            if text.startswith(mark, i):
                if mark in open_marks:
                    start = open_marks.pop(mark)
                    if pos > start:
                        entities.append(RawEntity(etype, start, pos - start))
                    i += len(mark)
                    matched = True
                    break
                # only open when a closing mark exists later on
                if text.find(mark, i + len(mark)) != -1:
                    open_marks[mark] = pos
                    i += len(mark)
                    matched = True
                    break
        if matched:
            continue
        emit(ch)
        i += 1
    if quote_start is not None and pos > quote_start:
        entities.append(RawEntity(TLEntityType.BLOCKQUOTE, quote_start, pos - quote_start, collapsed=quote_collapsed))
    return "".join(out), sorted(entities, key=lambda e: (e.offset, -e.length))


def parse_text(text: str, parse_mode: Optional[str] = "HTML", is_caption: bool = False) -> Dict[str, Any]:
    mode = (parse_mode or "HTML").lower()
    if mode == "html":
        plain, raw = parse_html(text)
    elif mode in ("markdown", "md", "markdownv2"):
        plain, raw = parse_markdown_entities(text)
    else:
        raise ValueError(f"Unsupported parse_mode: {parse_mode}")
    return {
        "caption" if is_caption else "message": plain,
        "entities": [e.to_tlrpc_object() for e in raw],
    }
