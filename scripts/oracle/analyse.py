# SPDX-License-Identifier: Apache-2.0
"""Run `db-analyser --store-ledger` (reference image) to snapshot the ledger state at chosen slots.

`--store-ledger S` applies the blocks of the ImmutableDB until the first block with a slot at or after S and
stores the ledger state after that block as `ledger/<slot of that block>_db-analyser/`. Chaining runs with
`--analyse-from <previous snapshot slot>` makes each run replay only the blocks in between, so the states at
every epoch boundary of a long chain cost one snapshot load, one epoch of blocks and one snapshot write each.
"""
import re
import subprocess
import time

REFERENCE_IMAGE = "cardano-reference-11.1.3:local"
STORED = re.compile(r"Snapshot stored at SlotNo (\d+)")
TIP = re.compile(r"ImmutableDB tip: Point \(At \(Block \{blockPointSlot = SlotNo (\d+)")


class AnalyserError(RuntimeError):
    pass


class Analyser:
    """db-analyser over one ChainDB directory, run through `launch(args) -> [argv]` (a `docker exec` into a
    running container, or a `docker run` with the directories mounted)."""

    def __init__(self, launch, db, config, threshold=None, validation="minimum-block-validation"):
        self.launch, self.db, self.config = launch, db, config
        self.extra = ["--threshold", str(threshold)] if threshold is not None else []
        self.validation = validation
        self.last_seconds = 0.0
        self.tip = None  # slot of the last block of the ImmutableDB, as the last run saw it
        self.chunk = None  # the newest chunk of the copy at that time

    def store(self, target_slot, analyse_from=None, timeout=3 * 3600):
        """Store the state at the first block at or after `target_slot`; returns that block's slot, or None
        when the ImmutableDB ends before `target_slot`."""
        args = ["--db", self.db, "--config", self.config, "--in-mem", "--store-ledger", str(target_slot),
                "--db-validation", self.validation, *self.extra]
        if analyse_from is not None:
            args += ["--analyse-from", str(analyse_from)]
        started = time.monotonic()
        r = subprocess.run(self.launch(args), text=True, capture_output=True, timeout=timeout)
        self.last_seconds = time.monotonic() - started
        if r.returncode != 0:
            raise AnalyserError(f"db-analyser failed ({r.returncode}): {(r.stdout + r.stderr)[-1500:]}")
        text = r.stdout + r.stderr
        tip = TIP.search(text)
        if tip:
            self.tip = int(tip.group(1))
        stored = STORED.findall(text)
        return int(stored[-1]) if stored else None
