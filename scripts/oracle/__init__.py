# SPDX-License-Identifier: Apache-2.0
"""Haskell ledger-state oracle (roadmap K1): project db-analyser snapshots and compare them with the Scala node.

The entry point is `scripts/oracle.py`; the stages are `analyse` (db-analyser snapshots), `project` (a
snapshot to the comparable projection) and `compare` (against `follow --dump-state` digests).
"""
