from __future__ import annotations

from dataclasses import dataclass
import io
from datetime import datetime, timedelta
from typing import List, Dict, Any, Optional

import pandas as pd
from rapidfuzz import fuzz
from dateutil import parser as dateparser


def load_bank_statement(file) -> pd.DataFrame:
    name = getattr(file, 'name', 'releve')
    if name.lower().endswith(('.xlsx', '.xls')):
        df = pd.read_excel(file)
    else:
        # CSV default: try common separators
        content = file.read()
        for sep in [',', ';', '\t', '|']:
            try:
                df = pd.read_csv(io.BytesIO(content), sep=sep)
                if df.shape[1] > 1:
                    break
            except Exception:
                continue

    # Normalize columns
    cols = {c.lower().strip(): c for c in df.columns}

    # Best-effort mapping
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

    c_date = pick('date', 'operation date', 'transaction date', 'booking date', 'valeur', 'date operation')
    c_desc = pick('description', 'label', 'libellé', 'libelle', 'narration', 'detail', 'details')
    c_amount = pick('amount', 'montant', 'debit/credit', 'debit', 'credit')

    if not c_date or not c_desc or not c_amount:
        raise ValueError("Impossible de détecter les colonnes (date, description, montant). Veuillez vérifier le fichier.")

    out = pd.DataFrame()
    out['date_raw'] = df[c_date].astype(str)
    out['description'] = df[c_desc].astype(str)
    out['amount_raw'] = df[c_amount]

    def parse_date(s: str) -> Optional[datetime]:
        try:
            return dateparser.parse(s, dayfirst=True, fuzzy=True)
        except Exception:
            return None

    out['date'] = out['date_raw'].apply(parse_date)

    def parse_amount(v) -> Optional[float]:
        if pd.isna(v):
            return None
        if isinstance(v, (int, float)):
            return float(v)
        s = str(v).replace('\u202f', ' ').replace('\xa0', ' ').strip()
        s = s.replace(' ', '')
        s = s.replace(',', '.')
        try:
            return float(s)
        except Exception:
            return None

    out['amount'] = out['amount_raw'].apply(parse_amount)
    out = out.dropna(subset=['date', 'amount']).reset_index(drop=True)
    return out


def match_invoices_to_bank(
    invoices: List[Dict[str, Any]],
    bank_df: pd.DataFrame,
    amount_tolerance: float = 0.02,
    max_days_delta: int = 90,
) -> pd.DataFrame:
    rows = []
    for inv in invoices:
        inv_num = inv.get('invoice_number')
        inv_date_s = inv.get('invoice_date')
        inv_total = inv.get('total_amount')
        fname = inv.get('filename')

        inv_date = None
        if inv_date_s:
            try:
                inv_date = dateparser.parse(inv_date_s).date()
            except Exception:
                pass

        best_idx = None
        best_score = -1.0
        best_row = None

        for i, row in bank_df.iterrows():
            amt_ok = False
            if inv_total is not None and row['amount'] is not None:
                # Payments are often negative on statements; match absolute amounts
                if abs(abs(row['amount']) - abs(inv_total)) <= amount_tolerance:
                    amt_ok = True
            if not amt_ok:
                continue

            date_ok = True
            if inv_date is not None and row['date'] is not None:
                d = row['date'].date()
                # payment on/after invoice date, within window
                if d < inv_date or (d - inv_date).days > max_days_delta:
                    date_ok = False
            if not date_ok:
                continue

            # textual score if invoice number present
            desc = str(row['description'])
            score = 0.0
            if inv_num:
                score = max(
                    fuzz.partial_ratio(inv_num.lower(), desc.lower()),
                    fuzz.partial_ratio(desc.lower(), inv_num.lower()),
                )
            # slight boost for exact amount match
            if abs(abs(row['amount']) - abs(inv_total or 0)) <= amount_tolerance:
                score += 5

            if score > best_score:
                best_score = score
                best_idx = i
                best_row = row

        matched = best_row is not None
        rows.append({
            'filename': fname,
            'invoice_number': inv_num,
            'invoice_date': inv_date_s,
            'total_amount': inv_total,
            'matched': matched,
            'match_score': best_score if matched else None,
            'bank_date': best_row['date'].date().isoformat() if matched else None,
            'bank_amount': best_row['amount'] if matched else None,
            'bank_description': best_row['description'] if matched else None,
        })

    return pd.DataFrame(rows)
