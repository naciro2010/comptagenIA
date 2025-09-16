from typing import Any, Dict, List, Optional
from pathlib import Path
import sys

import pandas as pd
import streamlit as st

from src import config
from src.bank_matching import load_bank_statement, match_invoices_to_bank
from src.invoice_extraction import extract_invoices_from_pdfs
from src.xml_export import invoices_to_xml_string

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

st.set_page_config(
    page_title="Lecteur Factures → XML + Matching Bancaire",
    page_icon="🧾",
    layout="wide",
)


def _load_local_css():
    css_path = Path(__file__).parent / "assets" / "styles.css"
    if css_path.exists():
        with open(css_path, "r", encoding="utf-8") as f:
            st.markdown(f"<style>{f.read()}</style>", unsafe_allow_html=True)


def _display_sidebar() -> Dict[str, Any]:
    """Displays the sidebar with settings and returns them as a dictionary."""
    with st.sidebar:
        st.header("Paramètres")
        use_llm = st.checkbox(
            "Utiliser LLM local (Ollama)",
            value=False,
            help="Si un serveur Ollama tourne en local (http://localhost:11434).",
        )
        model_name = st.text_input(
            "Modèle Ollama",
            value=config.DEFAULT_LLM_MODEL,
            help="Nom du modèle chargé dans Ollama.",
        )
        date_tolerance_days = st.number_input(
            "Tolérance jour matching",
            min_value=0,
            max_value=365,
            value=config.DEFAULT_DATE_TOLERANCE_DAYS,
        )
        amount_tol = st.number_input(
            "Tolérance montant (EUR)",
            min_value=0.0,
            value=config.DEFAULT_AMOUNT_TOLERANCE_EUR,
            step=0.01,
            help="Tolérance absolue sur le montant pour le matching.",
        )
        st.divider()
        st.caption("Guide rapide")
        st.markdown(
            "- 1) Ajoutez vos PDF de factures\n"
            "- 2) Ajoutez le relevé bancaire (PDF/CSV/XLSX)\n"
            "- 3) Cliquez sur Extraire puis sur Charger\n"
            "- 4) Téléchargez les exports (XML/CSV)"
        )
    return {
        "use_llm": use_llm,
        "model_name": model_name,
        "date_tolerance_days": date_tolerance_days,
        "amount_tol": amount_tol,
    }


def _display_file_uploaders() -> Dict[str, Any]:
    """Displays file uploaders and returns a dictionary of uploaded files."""
    st.subheader("1) Charger les factures PDF")
    pdf_files = st.file_uploader(
        "Sélectionnez une ou plusieurs factures (PDF)",
        type=["pdf"],
        accept_multiple_files=True,
    )

    st.subheader("2) Charger le relevé bancaire (PDF/CSV/XLSX)")
    bank_file = st.file_uploader(
        "Sélectionnez le relevé bancaire", type=["pdf", "csv", "xlsx", "xls"]
    )
    return {"pdf_files": pdf_files, "bank_file": bank_file}


def _handle_extraction(
    pdf_files: List[Any], use_llm: bool, model_name: str
) -> Optional[List[Dict[str, Any]]]:
    """Handles the invoice extraction process."""
    if st.button("🚀 Extraire factures"):
        if not pdf_files:
            st.warning("Veuillez charger au moins une facture PDF.")
            return None
        with st.spinner("Extraction en cours..."):
            extracted_invoices = extract_invoices_from_pdfs(
                pdf_files, use_llm=use_llm, llm_model=model_name
            )
        st.success(f"Extraction terminée: {len(extracted_invoices)} facture(s).")
        return extracted_invoices
    return None


def _handle_bank_statement_loading(
    bank_file: Any,
) -> Optional[pd.DataFrame]:
    """Handles the bank statement loading."""
    if st.button("📥 Charger relevé"):
        if not bank_file:
            st.warning("Veuillez charger un relevé bancaire.")
            return None
        with st.spinner("Lecture du relevé..."):
            bank_df = load_bank_statement(bank_file)
        st.success(
            f"Relevé chargé: {len(bank_df) if bank_df is not None else 0} opérations."
        )
        return bank_df
    return None


def _display_results(amount_tol: float, date_tolerance_days: int):
    """Displays the extracted invoices and matching results."""
    if st.session_state.get("extracted_invoices"):
        st.subheader("Factures extraites")
        df_inv = pd.DataFrame(st.session_state["extracted_invoices"]).drop(
            columns=["raw_text"], errors="ignore"
        )
        st.dataframe(df_inv, use_container_width=True)

        xml_str = invoices_to_xml_string(st.session_state["extracted_invoices"])
        st.download_button(
            label="Télécharger XML des factures",
            data=xml_str.encode("utf-8"),
            file_name="factures.xml",
            mime="application/xml",
        )

    if st.session_state.get("extracted_invoices") and st.session_state.get("bank_df") is not None:
        st.subheader("3) Matching factures ↔ relevé")
        with st.spinner("Matching en cours..."):
            matched = match_invoices_to_bank(
                st.session_state["extracted_invoices"],
                st.session_state["bank_df"],
                amount_tolerance=amount_tol,
                max_days_delta=int(date_tolerance_days),
            )
        st.success("Matching terminé.")
        st.dataframe(matched, use_container_width=True)

        csv = matched.to_csv(index=False)
        st.download_button(
            label="Télécharger résultats (CSV)",
            data=csv.encode("utf-8"),
            file_name="matching_resultats.csv",
            mime="text/csv",
        )


def main():
    """
    Main function to run the Streamlit application.
    """
    _load_local_css()

    # Hero section
    st.markdown(
        """
        <div class="hero">
          <h1>🧾 Factures → XML + Matching Bancaire</h1>
          <p class="muted">Local, simple et clair. Option LLM locale (Ollama) pour l'extraction avancée.</p>
          <div style="margin-top: .6rem;">
            <span class="pill">PDF factures</span>
            <span class="pill">PDF/CSV/XLSX relevé</span>
            <span class="pill">Exports XML / CSV</span>
          </div>
        </div>
        """,
        unsafe_allow_html=True,
    )

    settings = _display_sidebar()
    with st.expander("Comment ça marche ?", expanded=False):
        st.markdown(
            """
            - L'application lit vos factures PDF et en extrait: numéro, date, montant, devise.
            - Elle lit ensuite votre relevé bancaire et cherche les paiements correspondants.
            - Les fichiers restent localement sur votre poste (sauf si vous déployez en cloud).
            - Vous pouvez activer un LLM local (Ollama) pour améliorer l'extraction si besoin.
            """
        )

    files = _display_file_uploaders()

    col1, col2 = st.columns(2)
    with col1:
        extracted_invoices = _handle_extraction(
            files["pdf_files"], settings["use_llm"], settings["model_name"]
        )
    with col2:
        bank_df = _handle_bank_statement_loading(files["bank_file"])

    # Initialize session state
    if "extracted_invoices" not in st.session_state:
        st.session_state["extracted_invoices"] = []
    if extracted_invoices:
        st.session_state["extracted_invoices"] = extracted_invoices

    if "bank_df" not in st.session_state:
        st.session_state["bank_df"] = None
    if bank_df is not None:
        st.session_state["bank_df"] = bank_df

    _display_results(settings["amount_tol"], settings["date_tolerance_days"])


if __name__ == "__main__":
    main()
