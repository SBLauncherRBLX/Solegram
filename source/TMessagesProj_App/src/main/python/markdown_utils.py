"""Markdown parsing for plugins (exteraGram SDK compatible)."""
from dataclasses import dataclass
from typing import Tuple

from extera_utils.text_formatting import RawEntity, TLEntityType, parse_markdown_entities

__all__ = ["parse_markdown", "ParsedMessage", "RawEntity", "TLEntityType"]


@dataclass
class ParsedMessage:
    text: str
    entities: Tuple[RawEntity, ...]


def parse_markdown(markdown: str) -> ParsedMessage:
    text, entities = parse_markdown_entities(markdown)
    return ParsedMessage(text, tuple(entities))
