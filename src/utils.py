import re
from datetime import datetime
from dateutil import parser as dateparser
from typing import Optional


AMOUNT_RE = re.compile(r"([+-]?)\s*(\d{1,3}(?:[\.,]\d{3})*|\d+)([\.,](\d{2}))?")


def parse_date(text: str) -> Optional[datetime]:
    try:
        # favor day-first formats typical in FR invoices
        return dateparser.parse(text, dayfirst=True, fuzzy=True)
    except Exception:
        return None


def parse_amount(text: str) -> Optional[float]:
    # Normalize typical FR formats: "1 234,56" or "1.234,56"
    t = text.replace("\u202f", " ").replace("\xa0", " ").strip()
    # Replace spaces in numbers
    t = re.sub(r"(?<=\d)\s+(?=\d)", "", t)

    m = AMOUNT_RE.search(t)
    if not m:
        return None
    sign, intpart, _, dec = m.groups()
    intpart = intpart.replace(".", "").replace(",", "")
    value = float(intpart)
    if dec is not None:
        value += float(dec) / 100.0
    if "," in m.group(0) and "." in m.group(0):
        # If both separators present, assume "." is thousands sep, "," is decimal
        pass
    # Apply sign
    if sign == "-":
        value = -value
    return value


def safe_lower(s: str) -> str:
    return (s or "").lower()

