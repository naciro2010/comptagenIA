import io
import re
from typing import Any, Dict, List, Optional

import pdfplumber

from .llm_extraction import extract_with_llm
from .utils import parse_amount, parse_date, safe_lower

INVOICE_NUMBER_PATTERNS = [
    r"facture\s*(?:n[°o]|no|num(?:éro)?)\s*[:#-]?\s*([A-Za-z0-9\-_/]{3,})",
    r"invoice\s*(?:n[°o]|no|#)?\s*[:#-]?\s*([A-Za-z0-9\-_/]{3,})",
    r"\b(?:facture|invoice)\s*[:#-]?\s*([A-Za-z0-9\-_/]{3,})",
]

DATE_HINTS = [
    r"date\s*(?:de\s*facture|facture|invoice)?\s*[:#-]?\s*([0-9]{1,2}[\-/\.][0-9]{1,2}[\-/\.][0-9]{2,4}|[0-9]{4}[\-/\.][0-9]{1,2}[\-/\.][0-9]{1,2}|\d{1,2}\s\w+\s\d{4})",
]

TOTAL_PATTERNS = [
    r"total\s*(?:ttc)?\s*[:#-]?\s*([+-]?\s*[0-9\s\.,]+)",
    r"montant\s*(?:ttc|total)\s*[:#-]?\s*([+-]?\s*[0-9\s\.,]+)",
]

CURRENCY_MARKERS = ["eur", "€", "eur.", "euro", "euros"]


def extract_text_from_pdf(file_bytes: bytes) -> str:
    with pdfplumber.open(io.BytesIO(file_bytes)) as pdf:
        texts = []
        for page in pdf.pages:
            txt = page.extract_text(x_tolerance=2, y_tolerance=2) or ""
            texts.append(txt)
    return "\n".join(texts)


def _find_invoice_number(low_text: str) -> Optional[str]:
    """Finds the invoice number using a list of regex patterns."""
    for pat in INVOICE_NUMBER_PATTERNS:
        m = re.search(pat, low_text, flags=re.IGNORECASE)
        if m:
            return m.group(1).strip().strip(":#-/ ")
    return None


def _find_invoice_date(low_text: str) -> Optional[str]:
    """Finds the invoice date using regex hints and a fallback."""
    for pat in DATE_HINTS:
        m = re.search(pat, low_text, flags=re.IGNORECASE)
        if m:
            dt = parse_date(m.group(1))
            if dt:
                return dt.date().isoformat()
    # Fallback: first date-looking token found
    m = re.search(
        r"(\d{1,2}[-/. ]\d{1,2}[-/. ]\d{2,4}|\d{4}[-/. ]\d{1,2}[-/. ]\d{1,2})",
        low_text,
    )
    if m:
        dt = parse_date(m.group(1))
        if dt:
            return dt.date().isoformat()
    return None


def _find_total_amount(low_text: str) -> Optional[float]:
    """Finds the total amount using regex patterns and a fallback."""
    for pat in TOTAL_PATTERNS:
        m = re.search(pat, low_text, flags=re.IGNORECASE)
        if m:
            amt = parse_amount(m.group(1))
            if amt is not None:
                return amt
    # Fallback: last amount-looking number could be total
    amounts = re.findall(r"[+-]?\s*[\d\s.,]{2,}", low_text)
    candidates = [parse_amount(a) for a in amounts]
    candidates = [c for c in candidates if c is not None]
    if candidates:
        return max(candidates)
    return None


def _find_currency(low_text: str) -> Optional[str]:
    """Finds the currency using a list of markers."""
    for marker in CURRENCY_MARKERS:
        if marker in low_text:
            return "EUR"
    return None


def heuristic_parse(text: str) -> Dict[str, Any]:
    """
    Parses a raw text to extract invoice fields using heuristics.
    """
    low_text = safe_lower(text)

    return {
        "invoice_number": _find_invoice_number(low_text),
        "invoice_date": _find_invoice_date(low_text),
        "total_amount": _find_total_amount(low_text),
        "currency": _find_currency(low_text) or "EUR",  # default
    }


def extract_invoices_from_pdfs(
    files, use_llm: bool = False, llm_model: str = "mistral"
) -> List[Dict[str, Any]]:
    results: List[Dict[str, Any]] = []
    for f in files:
        content = f.read()
        text = extract_text_from_pdf(content)

        parsed = heuristic_parse(text)
        if use_llm:
            try:
                enriched = extract_with_llm(text, model=llm_model)
                # overlay LLM fields if present
                parsed.update({k: v for k, v in enriched.items() if v})
            except Exception:
                # ignore LLM failures
                pass

        parsed["filename"] = getattr(f, "name", "facture.pdf")
        parsed["raw_text"] = text
        results.append(parsed)
    return results
