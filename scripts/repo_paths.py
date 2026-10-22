# SPDX-License-Identifier: Apache-2.0
"""Record provenance paths relative to the repository root, never as a machine-local absolute path.

Fixture manifests keep the argv and input directories of the commands that produced them. Written
absolute, they publish the home directory of whoever ran the capture; `check_local_paths.py` guards
the tracked tree against that.
"""
from __future__ import annotations

from collections.abc import Iterable
from pathlib import Path, PurePath
import sys

ROOT = Path(__file__).resolve().parents[1]


def repo_relative(value: str, root: Path = ROOT) -> str:
    """Rewrite each occurrence of the repository root prefix in `value`; the root itself becomes `.`.

    Substring rewriting covers plain paths and composite arguments such as Docker's `src:dst` mounts.
    A path outside the root is left unchanged: it is not ours to relativise.
    """
    prefix = str(root).rstrip("/") + "/"
    return "." if value == prefix[:-1] else value.replace(prefix, "")


def relative_argv(argv: Iterable[str | PurePath], root: Path = ROOT, interpreter: str = sys.executable) -> list[str]:
    """The argv as provenance: repository-relative paths, and the running interpreter by its base name."""
    return [Path(text).name if text == interpreter else repo_relative(text, root) for text in map(str, argv)]
