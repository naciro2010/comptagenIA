import io
import os
from typing import List, Dict, Any, Optional

import pandas as pd
import streamlit as st

from src.invoice_extraction import extract_invoices_from_pdfs
from src.bank_matching import load_bank_statement, match_invoices_to_bank
from src.xml_export import invoices_to_xml_string


st.set_page_config(page_title="Lecteur Factures → XML + Matching Bancaire", layout="wide")


def main():
    st.title("Lecteur de Factures PDF → XML et Matching Bancaire")
    st.caption("Local, simple, extensible. Option LLM locale via Ollama.")

    with st.sidebar:
        st.header("Paramètres")
        use_llm = st.checkbox("Utiliser LLM local (Ollama)", value=False,
                              help="Si un serveur Ollama tourne en local (http://localhost:11434).")
        model_name = st.text_input("Modèle Ollama", value="mistral", help="Nom du modèle chargé dans Ollama.")
        date_tolerance_days = st.number_input("Tolérance jour matching", min_value=0, max_value=365, value=90)
        amount_tol = st.number_input("Tolérance montant (EUR)", min_value=0.0, value=0.02, step=0.01,
                                     help="Tolérance absolue sur le montant pour le matching.")

    st.subheader("1) Charger les factures PDF")
    pdf_files = st.file_uploader("Sélectionnez une ou plusieurs factures (PDF)", type=["pdf"], accept_multiple_files=True)

    st.subheader("2) Charger le relevé bancaire (CSV ou XLSX)")
    bank_file = st.file_uploader("Sélectionnez le relevé bancaire", type=["csv", "xlsx", "xls"]) 

    extracted_invoices: List[Dict[str, Any]] = []
    bank_df: Optional[pd.DataFrame] = None

    col1, col2 = st.columns(2)
    with col1:
        if st.button("Extraire factures"):
            if not pdf_files:
                st.warning("Veuillez charger au moins une facture PDF.")
            else:
                with st.spinner("Extraction en cours..."):
                    extracted_invoices = extract_invoices_from_pdfs(pdf_files, use_llm=use_llm, llm_model=model_name)
                st.success(f"Extraction terminée: {len(extracted_invoices)} facture(s).")

    with col2:
        if st.button("Charger relevé"):
            if not bank_file:
                st.warning("Veuillez charger un relevé bancaire.")
            else:
                with st.spinner("Lecture du relevé..."):
                    bank_df = load_bank_statement(bank_file)
                st.success(f"Relevé chargé: {len(bank_df) if bank_df is not None else 0} opérations.")

    # Mémoire de session pour conserver les résultats
    if "extracted_invoices" not in st.session_state:
        st.session_state["extracted_invoices"] = []
    if extracted_invoices:
        st.session_state["extracted_invoices"] = extracted_invoices

    if "bank_df" not in st.session_state:
        st.session_state["bank_df"] = None
    if bank_df is not None:
        st.session_state["bank_df"] = bank_df

    # Afficher factures
    if st.session_state["extracted_invoices"]:
        st.subheader("Factures extraites")
        df_inv = pd.DataFrame(st.session_state["extracted_invoices"]).drop(columns=["raw_text"], errors="ignore")
        st.dataframe(df_inv, use_container_width=True)

        xml_str = invoices_to_xml_string(st.session_state["extracted_invoices"])
        st.download_button(
            label="Télécharger XML des factures",
            data=xml_str.encode("utf-8"),
            file_name="factures.xml",
            mime="application/xml"
        )

    # Matching
    if st.session_state["extracted_invoices"] and st.session_state["bank_df"] is not None:
        st.subheader("3) Matching factures ↔ relevé")
        with st.spinner("Matching en cours..."):
            matched = match_invoices_to_bank(
                st.session_state["extracted_invoices"],
                st.session_state["bank_df"],
                amount_tolerance=amount_tol,
                max_days_delta=int(date_tolerance_days)
            )
        st.success("Matching terminé.")
        st.dataframe(matched, use_container_width=True)

        csv = matched.to_csv(index=False)
        st.download_button(
            label="Télécharger résultats (CSV)",
            data=csv.encode("utf-8"),
            file_name="matching_resultats.csv",
            mime="text/csv"
        )


if __name__ == "__main__":
    main()

