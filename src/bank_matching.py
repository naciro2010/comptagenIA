from __future__ import annotations

import io
import re
import unicodedata
from datetime import datetime
from typing import Any, Dict, List, Optional

import pandas as pd
import pdfplumber
from dateutil import parser as dateparser
from rapidfuzz import fuzz


def _read_statement_to_dataframe(file) -> pd.DataFrame:
    """Reads a bank statement file (CSV or Excel) into a DataFrame."""
    name = getattr(file, "name", "releve")
    if name.lower().endswith(".pdf"):
        return _read_bank_pdf_to_dataframe(file)
    if name.lower().endswith((".xlsx", ".xls")):
        return pd.read_excel(file)

    # For CSV, try common separators
    content = file.read()
    for sep in [",", ";", "\t", "|"]:
        try:
            df = pd.read_csv(io.BytesIO(content), sep=sep)
            if df.shape[1] > 1:
                return df
        except Exception:
            continue
    raise ValueError("Impossible de lire le fichier CSV. Vérifiez le séparateur.")


def _strip_accents(s: str) -> str:
    return "".join(
        ch for ch in unicodedata.normalize("NFD", s or "") if unicodedata.category(ch) != "Mn"
    )


def _std_header(s: str) -> str:
    s = _strip_accents(str(s)).lower().strip()
    s = re.sub(r"[^a-z0-9]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def _find_table_column_map(headers: List[str]) -> Optional[Dict[str, int]]:
    """Given a list of header strings, map to expected columns.

    Returns a dict like {"date": idx, "description": idx, "amount": idx} or
    with debit/credit if present.
    """
    std = [_std_header(h) for h in headers]

    def find(*cands) -> Optional[int]:
        for i, h in enumerate(std):
            if any(c in h for c in cands):
                return i
        return None

    c_date = find("date")
    c_desc = find("description", "libelle", "label", "narration", "details")
    c_amount = find("montant", "amount", "solde")
    c_debit = find("debit", "retrait")
    c_credit = find("credit", "versement")

    if c_date is None or c_desc is None:
        return None

    # Prefer amount if present; else debit/credit
    if c_amount is not None:
        return {"date": c_date, "description": c_desc, "amount": c_amount}
    if c_debit is not None or c_credit is not None:
        return {
            "date": c_date,
            "description": c_desc,
            "debit": c_debit if c_debit is not None else -1,
            "credit": c_credit if c_credit is not None else -1,
        }
    return None


def _build_df_from_table(table: List[List[str]]) -> Optional[pd.DataFrame]:
    if not table:
        return None
    # Find a plausible header row: the first row with at least 2 non-empty cells
    header_idx = None
    for i, row in enumerate(table[:5]):
        non_empty = sum(1 for c in row if str(c or "").strip())
        if non_empty >= 2:
            header_idx = i
            break
    if header_idx is None:
        return None

    headers = [str(c or "").strip() for c in table[header_idx]]
    col_map = _find_table_column_map(headers)
    if not col_map:
        return None

    data_rows = table[header_idx + 1 :]
    recs = []
    for r in data_rows:
        # pad row length
        row = list(r) + [None] * (len(headers) - len(r))
        d = {headers[i]: row[i] for i in range(len(headers))}
        # Map to normalized fields
        date_val = d.get(headers[col_map["date"]]) if "date" in col_map else None
        desc_val = d.get(headers[col_map["description"]]) if "description" in col_map else None

        amount_val = None
        if "amount" in col_map and col_map["amount"] is not None:
            amount_val = d.get(headers[col_map["amount"]])
        else:
            debit_val = d.get(headers[col_map["debit"]]) if col_map.get("debit", -1) != -1 else None
            credit_val = d.get(headers[col_map["credit"]]) if col_map.get("credit", -1) != -1 else None
            # debit negative, credit positive
            from .utils import parse_amount as _parse_amount

            dv = _parse_amount(str(debit_val)) if debit_val is not None else None
            cv = _parse_amount(str(credit_val)) if credit_val is not None else None
            if dv is not None and dv != 0:
                amount_val = -abs(dv)
            elif cv is not None and cv != 0:
                amount_val = abs(cv)

        recs.append({
            "date": date_val,
            "description": desc_val,
            "amount": amount_val,
        })

    if not recs:
        return None
    out = pd.DataFrame(recs)
    return out


def _read_bank_pdf_to_dataframe(file) -> pd.DataFrame:
    """Extracts transactions from a PDF bank statement.

    Strategy:
    - Try to extract tables and map header columns.
    - Fallback to text line parsing if no usable tables.
    """
    content = file.read()
    frames: List[pd.DataFrame] = []
    with pdfplumber.open(io.BytesIO(content)) as pdf:
        for page in pdf.pages:
            # Try tables first
            try:
                tables = page.extract_tables() or []
            except Exception:
                tables = []
            for t in tables:
                df = _build_df_from_table(t)
                if df is not None and not df.empty:
                    frames.append(df)

            # Fallback: text lines
            try:
                text = page.extract_text(x_tolerance=2, y_tolerance=2) or ""
            except Exception:
                text = ""
            if text:
                lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
                recs = []
                date_re = re.compile(r"^(\d{1,2}[\-/]\d{1,2}[\-/]\d{2,4})\s+(.*)")
                amt_re = re.compile(r"([+-]?\s*\d[\d\s\.,]*)$")
                from .utils import parse_date as _parse_date, parse_amount as _parse_amount

                for ln in lines:
                    m = date_re.match(ln)
                    if not m:
                        continue
                    d_s, rest = m.groups()
                    amt_m = amt_re.search(rest)
                    if not amt_m:
                        continue
                    amt_s = amt_m.group(1)
                    desc = rest[: amt_m.start(1)].strip()
                    dt = _parse_date(d_s)
                    amt = _parse_amount(amt_s)
                    if dt and amt is not None:
                        recs.append({
                            "date": dt,
                            "description": desc,
                            "amount": amt,
                        })
                if recs:
                    frames.append(pd.DataFrame(recs))

    if not frames:
        raise ValueError("Aucune transaction détectée dans le PDF du relevé.")

    df = pd.concat(frames, ignore_index=True)
    # Normalize date and amount columns
    def _norm_date(v):
        if isinstance(v, datetime):
            return v
        try:
            return dateparser.parse(str(v), dayfirst=True, fuzzy=True)
        except Exception:
            return None

    def _norm_amount(v):
        if isinstance(v, (int, float)):
            return float(v)
        from .utils import parse_amount as _parse_amount

        return _parse_amount(str(v))

    out = pd.DataFrame()
    out["date"] = df["date"].apply(_norm_date)
    out["description"] = df["description"].astype(str)
    out["amount"] = df["amount"].apply(_norm_amount)
    return out.dropna(subset=["date", "amount"]).reset_index(drop=True)


def _find_statement_columns(df: pd.DataFrame) -> Dict[str, str]:
    """Tries to automatically detect the columns for date, description, and amount."""
    cols = {c.lower().strip(): c for c in df.columns}

    def pick(*cands):
        for c in cands:
            if c in cols:
                return cols[c]
        # fuzzy pick
        best = None
        best_score = 0
        for c in cols:
            for cand in cands:
                s = fuzz.partial_ratio(c, cand)
                if s > best_score:
                    best_score = s
                    best = c
        return cols.get(best) if best else None

    c_date = pick(
        "date",
        "operation date",
        "transaction date",
        "booking date",
        "valeur",
        "date operation",
    )
    c_desc = pick(
        "description", "label", "libellé", "libelle", "narration", "detail", "details"
    )
    c_amount = pick("amount", "montant", "debit/credit", "debit", "credit")

    if not c_date or not c_desc or not c_amount:
        raise ValueError(
            "Impossible de détecter les colonnes (date, description, montant). "
            "Veuillez vérifier le fichier."
        )

    return {"date": c_date, "description": c_desc, "amount": c_amount}


def _normalize_statement_dataframe(
    df: pd.DataFrame, column_map: Dict[str, str]
) -> pd.DataFrame:
    """Parses and normalizes the date and amount columns."""
    out = pd.DataFrame()
    out["date_raw"] = df[column_map["date"]].astype(str)
    out["description"] = df[column_map["description"]].astype(str)
    out["amount_raw"] = df[column_map["amount"]]

    def parse_date(s: str) -> Optional[datetime]:
        try:
            return dateparser.parse(s, dayfirst=True, fuzzy=True)
        except Exception:
            return None

    out["date"] = out["date_raw"].apply(parse_date)

    def parse_amount(v) -> Optional[float]:
        if pd.isna(v):
            return None
        if isinstance(v, (int, float)):
            return float(v)
        s = str(v).replace("\u202f", " ").replace("\xa0", " ").strip()
        s = s.replace(" ", "")
        s = s.replace(",", ".")
        try:
            return float(s)
        except Exception:
            return None

    out["amount"] = out["amount_raw"].apply(parse_amount)
    return out.dropna(subset=["date", "amount"]).reset_index(drop=True)


def load_bank_statement(file) -> pd.DataFrame:
    """
    Loads a bank statement, detects columns, and normalizes the data.
    """
    df = _read_statement_to_dataframe(file)
    column_map = _find_statement_columns(df)
    return _normalize_statement_dataframe(df, column_map)


def _check_amount_match(
    invoice_total: Optional[float],
    transaction_amount: Optional[float],
    tolerance: float,
) -> bool:
    """Checks if the invoice and transaction amounts match within a tolerance."""
    if invoice_total is None or transaction_amount is None:
        return False
    return abs(abs(transaction_amount) - abs(invoice_total)) <= tolerance


def _check_date_match(
    invoice_date: Optional[datetime.date],
    transaction_date: Optional[datetime.date],
    max_days_delta: int,
) -> bool:
    """Checks if the transaction date is valid relative to the invoice date."""
    if invoice_date is None or transaction_date is None:
        return True  # Don't penalize if dates are missing
    # Payment should be on or after the invoice date, within the allowed window
    return (
        transaction_date >= invoice_date
        and (transaction_date - invoice_date).days <= max_days_delta
    )


def _calculate_match_score(
    invoice_number: Optional[str],
    transaction_description: str,
    amount_matches_exactly: bool,
) -> float:
    """Calculates a match score based on textual similarity and amount match."""
    score = 0.0
    if invoice_number:
        score = max(
            fuzz.partial_ratio(invoice_number.lower(), transaction_description.lower()),
            fuzz.partial_ratio(transaction_description.lower(), invoice_number.lower()),
        )
    # Slight boost for exact amount match
    if amount_matches_exactly:
        score += 5
    return score


def find_best_match(
    invoice: Dict[str, Any],
    bank_df: pd.DataFrame,
    amount_tolerance: float,
    max_days_delta: int,
) -> Optional[Dict[str, Any]]:
    """Finds the best matching bank transaction for a single invoice."""
    inv_num = invoice.get("invoice_number")
    inv_total = invoice.get("total_amount")
    inv_date_s = invoice.get("invoice_date")

    inv_date = None
    if inv_date_s:
        try:
            inv_date = dateparser.parse(inv_date_s).date()
        except Exception:
            pass

    best_score = -1.0
    best_row = None

    for _, row in bank_df.iterrows():
        if not _check_amount_match(inv_total, row["amount"], amount_tolerance):
            continue

        if not _check_date_match(inv_date, row["date"].date(), max_days_delta):
            continue

        amount_matches_exactly = abs(abs(row["amount"]) - abs(inv_total or 0)) <= 1e-9
        score = _calculate_match_score(
            inv_num, str(row["description"]), amount_matches_exactly
        )

        if score > best_score:
            best_score = score
            best_row = row

    if best_row is not None:
        return {
            "match_score": best_score,
            "bank_date": best_row["date"].date().isoformat(),
            "bank_amount": best_row["amount"],
            "bank_description": best_row["description"],
        }
    return None


def match_invoices_to_bank(
    invoices: List[Dict[str, Any]],
    bank_df: pd.DataFrame,
    amount_tolerance: float = 0.02,
    max_days_delta: int = 90,
) -> pd.DataFrame:
    """Matches a list of invoices against a bank statement DataFrame."""
    rows = []
    for inv in invoices:
        match_result = find_best_match(
            inv, bank_df, amount_tolerance, max_days_delta
        )

        row = {
            "filename": inv.get("filename"),
            "invoice_number": inv.get("invoice_number"),
            "invoice_date": inv.get("invoice_date"),
            "total_amount": inv.get("total_amount"),
            "matched": match_result is not None,
        }
        if match_result:
            row.update(match_result)
        rows.append(row)

    return pd.DataFrame(rows)
