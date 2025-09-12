import json
import os
from typing import Dict, Any

import requests


def extract_with_llm(text: str, model: str = "mistral") -> Dict[str, Any]:
    """
    Call a local Ollama server to extract structured invoice fields from text.
    Minimal prompt; tolerant to server absence.
    """
    base_url = os.environ.get("OLLAMA_BASE_URL", "http://localhost:11434")
    url = f"{base_url.rstrip('/')}/api/generate"

    system = (
        "Tu es un extracteur de champs de facture. "
        "Retourne un JSON compact avec les clés: invoice_number, invoice_date (YYYY-MM-DD), total_amount (float), currency (ISO)."
    )
    prompt = (
        f"{system}\n\nTexte facture:\n{text}\n\n"
        "Réponds uniquement en JSON, sans explications."
    )

    payload = {
        "model": model,
        "prompt": prompt,
        "stream": False,
        "options": {
            "temperature": 0.2,
        },
    }

    resp = requests.post(url, json=payload, timeout=60)
    resp.raise_for_status()
    data = resp.json()
    out = data.get("response", "{}")

    # try parse json block
    try:
        parsed = json.loads(out)
        # normalize
        if isinstance(parsed.get("total_amount"), str):
            try:
                parsed["total_amount"] = float(parsed["total_amount"].replace(",", "."))
            except Exception:
                pass
        return parsed
    except Exception:
        return {}

