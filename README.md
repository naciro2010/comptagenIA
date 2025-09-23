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

1. Chargez une ou plusieurs factures PDF.
2. Chargez le relevé bancaire (PDF/CSV/XLSX). Le mapping de colonnes est détecté automatiquement (date, description, montant).
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

## Module Spring Boot Kotlin

Un service REST Kotlin/Spring Boot (`spring-app/`) reprend les briques principales (extraction factures, lecture relevés, matching et export XML).

### Lancer le service
- Prérequis: JDK 21+
- `cd spring-app`
- `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew bootRun`

### API principale
`POST /api/matching/run` (`multipart/form-data`)
- `invoices`: un ou plusieurs PDF de factures
- `bankStatement`: relevé bancaire (PDF/CSV/XLS/XLSX)
- `amountTolerance` (optionnel, défaut `0.02`)
- `dateToleranceDays` (optionnel, défaut `90`)
- `useLlm` (optionnel, défaut `false`). Lorsqu'il est activé, Ollama est utilisé à la fois pour enrichir les factures **et** pour détecter automatiquement les colonnes du relevé / reconstruire les transactions si le format est atypique.
- `llmModel` (optionnel, défaut `gpt-oss:20b`)

Réponse JSON: factures extraites, résultats de matching, export XML inline.

### Front de test rapide
Une page statique est disponible sur `http://localhost:8080/` (servie depuis `spring-app/src/main/resources/static/index.html`). Elle permet d'uploader les fichiers, ajuster les paramètres et inspecter les résultats sans outil externe.

La section résultats affiche désormais les transactions du relevé (issues des heuristiques ou de l'IA). Les lignes surlignées en vert correspondent aux opérations appariées à une facture.

- Les factures multiples dans un même PDF sont détectées automatiquement (`fichier.pdf#1`, `fichier.pdf#2`, ...). Chaque fiche affiche les métadonnées principales (fournisseur, client, en-tête) et l'état du matching (vert = trouvé, rouge = manquant).

**Note upload**: la limite côté serveur est fixée à ~120 Mo par fichier (130 Mo par requête). Au‑delà, l'API renvoie un message d'erreur lisible dans l'interface.

En cas de fichier mal reconnu (par ex. colonnes manquantes), l'API renvoie un 400 avec le détail (`Impossible de détecter la colonne ...`, `Aucune transaction détectée ...`) et la page web reflète ce message.

### Configuration Ollama
- Variables dans `application.properties` (`ollama.base-url`, `ollama.model`, `ollama.enabled`).
- Par défaut, le service tente d'appeler Ollama en local mais ignore les erreurs de connexion pour rester fonctionnel.
- Si vous saisissez un modèle non installé (ex. `mistral`), le service retombe automatiquement sur le modèle par défaut (`gpt-oss:20b`) et loggue un avertissement. Pour utiliser un autre modèle, téléchargez-le au préalable (`ollama pull <modele>`).

### Tests
`JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew test`
