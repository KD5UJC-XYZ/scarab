<!-- SPDX-License-Identifier: Apache-2.0 -->
# Native wide serialized-byte observer

Status: Current. Executed evidence for [#749](https://github.com/KD5UJC-XYZ/scarab/issues/749).

`Main.hs` constructs an opaque `Serialised` value containing 2,147,483,649 copies of byte `a5`.
The released native ChainSync codec encodes it as a RollForward with TipGenesis. The actual
plain `runPeer` client decodes that message from 1-MiB pieces and re-encodes its result.
The observer prints complete payload and wire lengths and SHA-256 identities. It never writes
the large value to disk. The payload is deliberately not a valid ledger block.

The image, packages, exact source identities and executable identity of the 2026-11-05 run are
recorded in the [fixture provenance](../../../fixtures/reference/chainsync-wide-serialised/provenance.json).
`build.sh` uses those already-installed packages, builds in disposable container storage, and
runs the witness with a 12-GiB heap ceiling. `run.py` requires the recorded immutable image,
gives its disposable container 16 GiB of memory, and records source hashes before and after
execution along with the output identity. Choose a fresh evidence directory outside the checkout:

```sh
WITNESS_OUTPUT="${SCARAB_DATA_DIR:-/tmp/scarab}/wide-serialised-your-owner"
python3 scripts/oracle/chainsync-wide-serialised/run.py "$WITNESS_OUTPUT" --owner your-owner
python3 scripts/oracle/chainsync-wide-serialised/capture.py "$WITNESS_OUTPUT/native.log" \
  --manifest "$WITNESS_OUTPUT/execution.json"
python3 scripts/check_test_coverage.py --refresh-fixtures
```

The capture first validates that the execution manifest binds the observed image, sources and
output. It then checks both native observations against an independently assembled wire recipe,
using bounded chunks while hashing all logical bytes. It writes only compact fixture files.
Refreshing the inventory records those identities; it does not establish conformance by itself.
Keep compact evidence and remove the disposable log when review is complete. The container's
compiled artifacts disappear with `--rm`; raw run artifacts are not published.
