# Lecteur Factures PDF → XML + Matching Bancaire (Local)

Une application locale simple pour:

- Lire des factures PDF **ou images** (PNG/JPG/TIFF) et en extraire les champs clés

- Charger un relevé bancaire (PDF/CSV/XLSX ou image scannée) et reconstruire les - Convertir en XML (si vous vouliez « xql », merci de confirmer; ici on exporte en XML)
- Charger un relevé bancaire (PDF/CSV/XLSX ou image scannée)

- Faire le matching facture ↔ opération bancaire
- Exporter les résultats (CSV) et les factures extraites (XML)

Deux implémentations cohabitent :

1. **Service Spring Boot Kotlin (`spring-app/`)** – pipeline OCR hybride (PDFBox + Tesseract) exposé via une API REST et une page web statique.
2. **Prototype Streamlit Python (`app/`)** – conservé pour référence historique.

Le reste de ce document détaille les deux options. Si vous partez de zéro, privilégiez la version Kotlin.

## Prérequis

- Python 3.10+
- pip
- (Optionnel) [Ollama](https://ollama.com) en local pour activer le mode LLM (`ollama run gpt-oss:20b` par exemple)

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

1. Chargez une ou plusieurs factures (PDF ou images scannées).
2. Chargez le relevé bancaire (PDF/CSV/XLSX ou photo). Le mapping de colonnes est détecté automatiquement (date, description, montant).
3. Cliquez sur « Extraire factures » puis « Charger relevé ».
4. Si souhaité, activez « Utiliser LLM local (Ollama) » dans la barre latérale (serveur Ollama nécessaire, modèle par défaut: `gpt-oss:20b`).
5. Téléchargez l’export XML des factures et le CSV des résultats de matching.

### Tour de l’interface
- Bandeau d’accueil avec rappel des formats pris en charge et exports.
- Barre latérale: paramètres (LLM, tolérances) + guide rapide.
- Zone centrale: upload des PDF, extraction, chargement de relevé, et résultats avec boutons de téléchargement.
- Bloc “Comment ça marche ?” expliquant le flux et la confidentialité locale.

## Détails techniques

- UI: `Streamlit` avec un style léger (voir `app/assets/styles.css`).
- Extraction documents: `PyMuPDF` pour le texte natif + OCR hybride (`PaddleOCR` multi-langue puis `pytesseract` en secours) afin de lire les scans et photos. Les heuristiques (numéro de facture, date, total, devise) restent identiques avec enrichissement optionnel par LLM.
- Relevé bancaire: PDF/CSV/XLSX/Images. Pour PDF, extraction par tables puis fallback texte/OCR partagé avec les factures.
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

## Module Spring Boot Kotlin

Un service REST Kotlin/Spring Boot (`spring-app/`) reprend les briques principales : OCR hybride, parsing heuristique et exposition d'une API simple pour les factures et relevés bancaires.

### Lancer le service
- Prérequis: JDK 21+
- `cd spring-app`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew bootRun`

### API principale
- `POST /api/documents/invoices/extract` (`multipart/form-data`)
  - Champ `files`: un ou plusieurs fichiers facture (PDF natif ou image scannée)
  - Réponse: tableau JSON avec `invoiceNumber`, `invoiceDate`, `totalAmount`, `currency`, `rawText`
- `POST /api/documents/bank-statements/extract` (`multipart/form-data`)
  - Champ `file`: relevé bancaire (PDF/CSV/XLS/XLSX ou image)
  - Réponse: objet JSON contenant `transactions` (date/description/montant normalisés) et `rawText` (texte OCR brut si pertinent)

### Front de test rapide
Une page statique est disponible sur `http://localhost:8080/` (servie depuis `spring-app/src/main/resources/static/index.html`). Elle appelle directement les deux endpoints ci-dessus et affiche:

- Les métadonnées extraites des factures (numéro, date, total, devise)
- Les transactions reconstituées du relevé bancaire, avec accès au texte OCR complet

**Note upload**: la limite côté serveur est fixée à ~120 Mo par fichier (130 Mo par requête). En cas d'échec (format exotique, colonnes introuvables), l'API répond avec un message explicite.

### Tests
`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test`
