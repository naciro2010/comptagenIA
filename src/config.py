import os

# LLM Configuration
OLLAMA_BASE_URL = os.environ.get("OLLAMA_BASE_URL", "http://localhost:11434")
DEFAULT_LLM_MODEL = "mistral"

# Matching Configuration
DEFAULT_DATE_TOLERANCE_DAYS = 90
DEFAULT_AMOUNT_TOLERANCE_EUR = 0.02
