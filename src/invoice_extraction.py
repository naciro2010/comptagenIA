import io
import re
from typing import List, Dict, Any, Optional

import pdfplumber

from .utils import parse_date, parse_amount, safe_lower
from .llm_extraction import extract_with_llm


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


def heuristic_parse(text: str) -> Dict[str, Any]:
    low = safe_lower(text)

    # Invoice number
    invoice_number: Optional[str] = None
    for pat in INVOICE_NUMBER_PATTERNS:
        m = re.search(pat, low, flags=re.IGNORECASE)
        if m:
            invoice_number = m.group(1).strip().strip(":#-/ ")
            break

    # Date
    invoice_date: Optional[str] = None
    # Try date hints
    for pat in DATE_HINTS:
        m = re.search(pat, low, flags=re.IGNORECASE)
        if m:
            dt = parse_date(m.group(1))
            if dt:
                invoice_date = dt.date().isoformat()
                break
    if not invoice_date:
        # fallback: first date-looking token found
        m = re.search(r"(\d{1,2}[\-/\.]\d{1,2}[\-/\.]\d{2,4}|\d{4}[\-/\.]\d{1,2}[\-/\.]\d{1,2})", low)
        if m:
            dt = parse_date(m.group(1))
            if dt:
                invoice_date = dt.date().isoformat()

    # Total amount
    total_amount: Optional[float] = None
    for pat in TOTAL_PATTERNS:
        m = re.search(pat, low, flags=re.IGNORECASE)
        if m:
            amt = parse_amount(m.group(1))
            if amt is not None:
                total_amount = amt
                break
    if total_amount is None:
        # fallback: last amount-looking number could be total
        amounts = re.findall(r"[+-]?\s*[0-9\s\.,]{2,}", low)
        candidates = [parse_amount(a) for a in amounts]
        candidates = [c for c in candidates if c is not None]
        if candidates:
            total_amount = max(candidates)

    # Currency
    currency: Optional[str] = None
    for marker in CURRENCY_MARKERS:
        if marker in low:
            currency = "EUR"
            break

    return {
        "invoice_number": invoice_number,
        "invoice_date": invoice_date,
        "total_amount": total_amount,
        "currency": currency or "EUR",  # default
    }


def extract_invoices_from_pdfs(files, use_llm: bool = False, llm_model: str = "mistral") -> List[Dict[str, Any]]:
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

        parsed["filename"] = getattr(f, 'name', 'facture.pdf')
        parsed["raw_text"] = text
        results.append(parsed)
    return results

