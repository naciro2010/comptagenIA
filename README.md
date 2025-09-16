# Lecteur Factures PDF → XML + Matching Bancaire (Local)

Une application locale simple pour:

- Lire des factures PDF et en extraire les champs clés
- Convertir en XML (si vous vouliez « xql », merci de confirmer; ici on exporte en XML)
- Charger un relevé bancaire (PDF/CSV/XLSX)
- Faire le matching facture ↔ opération bancaire
- Exporter les résultats (CSV) et les factures extraites (XML)

Tech choisi: Python + Streamlit (simple, local, facile à déployer et à faire évoluer). Option d'extraction via LLM local (Ollama) si disponible.

## Prérequis

- Python 3.10+
- pip
- (Optionnel) [Ollama](https://ollama.com) en local pour activer le mode LLM (`ollama run mistral` par exemple)

## Installation sur laptop (macOS/Windows/Linux)

- Créez un environnement virtuel:
  - macOS/Linux:
    - `python3 -m venv .venv && source .venv/bin/activate`
  - Windows (PowerShell):
    - `py -3 -m venv .venv ; .venv\\Scripts\\Activate.ps1`
- Installez les dépendances: `pip install -r requirements.txt`
- (Optionnel) Dev tools: `pip install -r requirements-dev.txt`

## Installation

```bash
pip install -r requirements.txt
```

## Lancement

```bash
streamlit run app/main.py
```

L’interface s’ouvrira dans votre navigateur.

Pour le dev (tests, lint):

```bash
pip install -r requirements-dev.txt
```

## Déploiement Cloud

Plusieurs options selon vos préférences. Voici deux chemins simples:

1) Streamlit Community Cloud (le plus simple)
- Poussez ce repo sur GitHub.
- Allez sur https://share.streamlit.io, connectez votre repo.
- Réglez:
  - Main file path: `app/main.py`
  - Python version (3.11+ de préférence)
  - `requirements.txt` pris en charge automatiquement
- Déployez. L’app sera accessible via une URL publique.

2) Docker + Cloud Run (GCP) ou autre PaaS
- Construire l’image:
  - `docker build -t invoices-app:latest .`
- Test local:
  - `docker run -p 8501:8501 invoices-app:latest`
- Déploiement Cloud Run (exemple GCP):
  - `gcloud builds submit --tag gcr.io/PROJECT_ID/invoices-app`
  - `gcloud run deploy invoices-app --image gcr.io/PROJECT_ID/invoices-app --platform managed --allow-unauthenticated --region REGION`
  - L’app écoute sur `$PORT` et `0.0.0.0` (voir `Dockerfile`).

Autres options rapides: Render, Railway, Fly.io, ou un simple VM/Docker sur AWS EC2.

Notes Cloud:
- L’option LLM local (Ollama) n’est pas disponible par défaut en Cloud. Vous pouvez pointer `OLLAMA_BASE_URL` vers une instance accessible si nécessaire.
- Si vous utilisez un reverse proxy, `.streamlit/config.toml` désactive CORS et active la protection XSRF.

## Utilisation

1. Chargez une ou plusieurs factures PDF.
2. Chargez le relevé bancaire (PDF/CSV/XLSX). Le mapping de colonnes est détecté automatiquement (date, description, montant).
3. Cliquez sur « Extraire factures » puis « Charger relevé ».
4. Si souhaité, activez « Utiliser LLM local (Ollama) » dans la barre latérale (serveur Ollama nécessaire, modèle par défaut: `mistral`).
5. Téléchargez l’export XML des factures et le CSV des résultats de matching.

### Tour de l’interface
- Bandeau d’accueil avec rappel des formats pris en charge et exports.
- Barre latérale: paramètres (LLM, tolérances) + guide rapide.
- Zone centrale: upload des PDF, extraction, chargement de relevé, et résultats avec boutons de téléchargement.
- Bloc “Comment ça marche ?” expliquant le flux et la confidentialité locale.

## Détails techniques

- UI: `Streamlit` avec un style léger (voir `app/assets/styles.css`).
- Extraction PDF: `pdfplumber` + heuristiques (numéro de facture, date, total, devise) puis enrichissement optionnel par LLM.
- Relevé bancaire: PDF/CSV/XLSX. Pour PDF, extraction par tables puis fallback texte.
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
