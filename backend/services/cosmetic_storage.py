"""Validation of uploaded Blockbench files and storage of accepted files on the local filesystem.

Uploaded files are untrusted. They are only ever parsed as JSON data: nothing in them (scripts, Molang
expressions, plugin data) is executed or interpreted. File names on disk are generated from the cosmetic ID;
the uploaded file name is stored as metadata only and never used as a path.
"""

from __future__ import annotations

import json
import logging
import os
import re
import unicodedata
from dataclasses import dataclass
from pathlib import Path

logger = logging.getLogger("orbit.storage")

ALLOWED_EXTENSIONS = (".bbmodel", ".json")
STORED_NAME = re.compile(r"^[0-9a-f]{32}\.bbmodel$")

# Structural limits for the prototype. They keep obviously abusive files out; they do not guarantee
# that a model renders well (see README, "Known limitations").
MAX_ELEMENTS = 4096
MAX_GROUPS = 4096
MAX_TEXTURES = 64
MAX_ANIMATIONS = 256
MAX_NAME_LENGTH = 64
MAX_FILENAME_LENGTH = 255


class ValidationError(Exception):
    """The upload is not an acceptable Blockbench file. The message is safe to show to the user."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


@dataclass(frozen=True)
class ValidatedModel:
    name: str | None
    format_version: str
    model_format: str | None
    element_count: int
    texture_count: int
    animation_count: int


def has_allowed_extension(filename: str) -> bool:
    return filename.lower().endswith(ALLOWED_EXTENSIONS)


def clean_text(value: str, max_length: int) -> str:
    """Removes control and formatting characters and limits the length, for names shown to users."""
    cleaned = "".join(ch for ch in value if unicodedata.category(ch)[0] != "C")
    return cleaned.strip()[:max_length].strip()


def clean_filename(filename: str | None) -> str:
    """The base name of an uploaded file, for metadata only (never used as a path)."""
    if not filename:
        return "upload.bbmodel"
    base = re.split(r"[\\/]", filename)[-1]
    return clean_text(base, MAX_FILENAME_LENGTH) or "upload.bbmodel"


def _reject_constant(value: str) -> None:
    raise ValueError(f"{value} is not allowed")


def validate_bbmodel(data: bytes) -> ValidatedModel:
    """Checks that the bytes are a JSON Blockbench project with the expected basic structure."""
    if not data:
        raise ValidationError("empty_file", "The file is empty.")
    try:
        text = data.decode("utf-8-sig")
    except UnicodeDecodeError:
        raise ValidationError("invalid_encoding", "The file is not UTF-8 text.") from None
    try:
        root = json.loads(text, parse_constant=_reject_constant)
    except RecursionError:
        raise ValidationError("too_deep", "The JSON is nested too deeply.") from None
    except ValueError as error:
        raise ValidationError("invalid_json", f"The file is not valid JSON: {error}") from None

    if not isinstance(root, dict):
        raise ValidationError("invalid_structure", "A Blockbench project must be a JSON object.")
    meta = root.get("meta")
    if not isinstance(meta, dict):
        raise ValidationError("invalid_structure", 'Missing the "meta" section of a Blockbench project.')
    format_version = meta.get("format_version", meta.get("format"))
    if not isinstance(format_version, str) or not format_version.strip():
        raise ValidationError("invalid_structure", 'The "meta" section has no "format_version".')
    if "elements" not in root and "outliner" not in root:
        raise ValidationError("invalid_structure", 'A Blockbench project needs "elements" or an "outliner".')

    elements = _list(root, "elements", MAX_ELEMENTS)
    _list(root, "outliner", MAX_GROUPS)
    _list(root, "groups", MAX_GROUPS)
    textures = _list(root, "textures", MAX_TEXTURES)
    animations = _list(root, "animations", MAX_ANIMATIONS)
    if any(not isinstance(element, dict) for element in elements):
        raise ValidationError("invalid_structure", 'Every entry in "elements" must be an object.')

    model_format = meta.get("model_format")
    name = root.get("name")
    return ValidatedModel(
        name=clean_text(name, MAX_NAME_LENGTH) or None if isinstance(name, str) else None,
        format_version=clean_text(format_version, 32),
        model_format=clean_text(model_format, 64) if isinstance(model_format, str) else None,
        element_count=len(elements),
        texture_count=len(textures),
        animation_count=len(animations),
    )


def _list(root: dict, key: str, limit: int) -> list:
    value = root.get(key, [])
    if not isinstance(value, list):
        raise ValidationError("invalid_structure", f'"{key}" must be a list.')
    if len(value) > limit:
        raise ValidationError("too_complex", f'"{key}" has {len(value)} entries; the limit is {limit}.')
    return value


class CosmeticStorage:
    """Stores accepted files as <storage_dir>/<cosmetic id>.bbmodel."""

    def __init__(self, directory: Path):
        self.directory = directory.resolve()

    def ensure_ready(self) -> None:
        self.directory.mkdir(parents=True, exist_ok=True)

    def path_for(self, stored_filename: str) -> Path:
        """Resolves a stored file name, refusing anything that is not one of our generated names."""
        if not STORED_NAME.match(stored_filename):
            raise ValueError(f"Invalid stored file name: {stored_filename!r}")
        path = (self.directory / stored_filename).resolve()
        if path.parent != self.directory:
            raise ValueError(f"Stored file outside the storage directory: {stored_filename!r}")
        return path

    def save(self, cosmetic_id: str, data: bytes) -> str:
        """Writes the file atomically (temporary file, fsync, rename) and returns the stored file name."""
        stored_filename = f"{cosmetic_id}.bbmodel"
        final_path = self.path_for(stored_filename)
        temp_path = self.directory / f".{cosmetic_id}.tmp"
        # O_EXCL: never overwrite or follow an existing file.
        fd = os.open(temp_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(fd, "wb") as handle:
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp_path, final_path)
        except BaseException:
            temp_path.unlink(missing_ok=True)
            raise
        return stored_filename

    def delete(self, stored_filename: str) -> None:
        self.path_for(stored_filename).unlink(missing_ok=True)

    def is_writable(self) -> bool:
        return self.directory.is_dir() and os.access(self.directory, os.W_OK)

    def remove_orphans(self, known: set[str]) -> int:
        """Deletes interrupted temporary files and stored files without a database entry.

        Only files matching Orbit's own naming scheme are touched; anything else in the directory is left alone.
        """
        removed = 0
        for path in self.directory.iterdir():
            name = path.name
            orphan_temp = name.startswith(".") and name.endswith(".tmp") and STORED_NAME.match(name[1:-4] + ".bbmodel")
            orphan_file = STORED_NAME.match(name) and name not in known
            if path.is_file() and (orphan_temp or orphan_file):
                path.unlink(missing_ok=True)
                removed += 1
                logger.warning("Removed orphaned file %s", name)
        return removed
