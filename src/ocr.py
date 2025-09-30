"""High quality OCR utilities used for invoice and statement extraction."""

from __future__ import annotations

import io
from functools import lru_cache
from typing import Iterable, List, Optional

import fitz  # type: ignore[import]
import numpy as np
from PIL import Image

try:  # pragma: no cover - optional dependency heavy to import in tests
    from paddleocr import PaddleOCR  # type: ignore
except Exception:  # pragma: no cover - paddleocr is optional at runtime
    PaddleOCR = None  # type: ignore[assignment]

try:  # pragma: no cover - optional dependency
    import pytesseract
    from pytesseract import TesseractNotFoundError
except Exception:  # pragma: no cover - optional dependency
    pytesseract = None  # type: ignore[assignment]
    TesseractNotFoundError = RuntimeError  # type: ignore[assignment]


SUPPORTED_IMAGE_FORMATS = {".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp", ".webp"}


def _pixmap_to_image(pix: "fitz.Pixmap") -> Image.Image:
    """Converts a PyMuPDF pixmap to a PIL image."""

    mode = "RGB"
    if pix.alpha:  # remove alpha channel for better OCR stability
        pix = fitz.Pixmap(pix, 0)  # type: ignore[arg-type]
    if pix.colorspace and pix.colorspace.n == 1:
        mode = "L"
    return Image.frombytes(mode, [pix.width, pix.height], pix.samples)


@lru_cache(maxsize=1)
def _get_paddle_ocr() -> Optional["PaddleOCR"]:
    """Returns a cached PaddleOCR instance if the library is available."""

    if PaddleOCR is None:  # pragma: no cover - optional dependency
        return None
    try:
        return PaddleOCR(lang="fr", use_angle_cls=True, show_log=False)
    except Exception:  # pragma: no cover - Paddle may fail to initialise
        return None


def _ocr_with_paddle(image: Image.Image) -> Optional[str]:
    ocr = _get_paddle_ocr()
    if ocr is None:
        return None
    arr = np.array(image)
    result = ocr.ocr(arr, cls=True)
    lines: List[str] = []
    for page in result or []:
        for line in page:
            if line and len(line) >= 2:
                text = line[1][0]
                if text:
                    lines.append(text)
    return "\n".join(lines) if lines else None


def _ocr_with_tesseract(image: Image.Image) -> Optional[str]:
    if pytesseract is None:  # pragma: no cover - optional dependency
        return None
    try:
        return pytesseract.image_to_string(image, lang="fra+eng")
    except TesseractNotFoundError:  # pragma: no cover - depends on host setup
        return None


def ocr_image(data: bytes) -> str:
    """Runs OCR on an image binary payload."""

    with Image.open(io.BytesIO(data)) as img:
        img = img.convert("RGB")
        return ocr_image_from_pil(img)


def extract_text_from_pdf(data: bytes, zoom: float = 2.0) -> str:
    """Extracts text from a PDF using native text extraction + OCR fallback."""

    texts: List[str] = []

    with fitz.open(stream=data, filetype="pdf") as doc:
        for page in doc:
            # Start with the embedded text when available
            raw_text = page.get_text("text") or ""
            cleaned = raw_text.strip()
            if cleaned:
                texts.append(cleaned)
                # If the native text is rich enough, skip OCR to save time
                if len(cleaned.split()) > 10:
                    continue

            # If the page is scanned (no embedded text) or text is too light, render at high resolution
            matrix = fitz.Matrix(zoom, zoom)
            pix = page.get_pixmap(matrix=matrix, alpha=False)
            image = _pixmap_to_image(pix)
            text = ocr_image_from_pil(image)
            if text:
                texts.append(text)

    return "\n".join(texts)


def ocr_image_from_pil(image: Image.Image) -> str:
    """Runs OCR on a PIL image instance."""

    if image.mode not in ("RGB", "L"):
        image = image.convert("RGB")
    text = _ocr_with_paddle(image)
    if text:
        return text
    text = _ocr_with_tesseract(image)
    if text:
        return text
    return ""


def extract_text_from_any(data: bytes, filename: str) -> str:
    """Extracts text from any supported document type."""

    lower = filename.lower()
    if lower.endswith(".pdf") or lower.endswith(".xps"):
        return extract_text_from_pdf(data)
    if any(lower.endswith(ext) for ext in SUPPORTED_IMAGE_FORMATS):
        return ocr_image(data)
    # Fallback: try to open as PDF anyway
    try:
        return extract_text_from_pdf(data)
    except Exception:
        return ""


def extract_text_from_multiple(files: Iterable[bytes], names: Iterable[str]) -> List[str]:
    """Convenience function to OCR multiple payloads."""

    return [extract_text_from_any(data, name) for data, name in zip(files, names)]

