# Lecteur Factures PDF → XML + Matching Bancaire (Local)

Une application locale simple pour:

- Lire des factures PDF et en extraire les champs clés
- Convertir en XML (si vous vouliez « xql », merci de confirmer; ici on exporte en XML)
- Charger un relevé bancaire (CSV/XLSX)
- Faire le matching facture ↔ opération bancaire
- Exporter les résultats (CSV) et les factures extraites (XML)

Tech choisi: Python + Streamlit (simple, local, facile à déployer et à faire évoluer). Option d'extraction via LLM local (Ollama) si disponible.

## Prérequis

- Python 3.10+
- pip
- (Optionnel) [Ollama](https://ollama.com) en local pour activer le mode LLM (`ollama run mistral` par exemple)

## Installation

```bash
pip install -r requirements.txt
```

## Lancement

```bash
streamlit run streamlit_app.py
```

L’interface s’ouvrira dans votre navigateur.

## Utilisation

1. Chargez une ou plusieurs factures PDF.
2. Chargez le relevé bancaire (CSV/XLSX). Le mapping de colonnes est détecté automatiquement (date, description, montant).
3. Cliquez sur « Extraire factures » puis « Charger relevé ».
4. Si souhaité, activez « Utiliser LLM local (Ollama) » dans la barre latérale (serveur Ollama nécessaire, modèle par défaut: `mistral`).
5. Téléchargez l’export XML des factures et le CSV des résultats de matching.

## Détails techniques

- Extraction PDF: `pdfplumber` + heuristiques (numéro de facture, date, total, devise) puis enrichissement optionnel par LLM.
- Matching: tolérance de montant configurable (par défaut 0.02 EUR) et fenêtre de temps (90 jours). Vérifie aussi la présence du numéro de facture dans la description via fuzzy matching.
- Export: XML des factures; CSV des correspondances.

## Remarque « xql »

Vous avez mentionné « xql ». J’ai implémenté un export **XML** (standard). Si vous vouliez un autre format (XLS/XLSX, XQuery, XQL spécifique), dites‑moi lequel et je l’ajuste rapidement.

## Roadmap légère

- Améliorer les heuristiques d’extraction (ligne d’items, TVA, etc.)
- Mapping manuel des colonnes du relevé si l’auto-détection échoue
- Export XLSX additionnel
- Sauvegarde/chargement de sessions
# comptagenIA
