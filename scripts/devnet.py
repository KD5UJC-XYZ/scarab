#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Entry point of the local devnet tooling; the implementation is the `scripts/devnet/` package.

Run `python3 scripts/devnet.py --help` for the subcommands (up, soak, e2e, relay, pool, ...).
"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from devnet.cli import main  # noqa: E402

if __name__ == "__main__":
    main()
