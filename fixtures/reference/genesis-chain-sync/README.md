# Native ChainSync jumping traces

[manifest.json](manifest.json) pins consensus
`82ecba329d7d054340bf707d44fe6e9ac27cec40` and hashes the unmodified native jumping
implementation, client state types, original narrow CSJ test, observer, executable, image,
Cabal plan, native sodium archive and payload.

The observer calls the native public `registerClient`, `mkJumping`, `rotateDynamo` and
`unregisterClient` APIs. Its jump information contains lawful `TestBlock` chains and native
`HeaderStateHistory.fromChain` histories. It records every role, pending instruction,
intersection-search state, published candidate, candidate tip and next instruction after
every event. Scala replays all 75 traces and 1037 transitions without filtering outcomes.

The original `Test.Consensus.MiniProtocol.ChainSync.CSJ` property has a constant
`Arbitrary TestSetup`: two clients that register while caught up must run normally.
That scenario is observed through the native callbacks. Ten additional traces cover accepted
jumps, binary intersection search, good-point rejection/restart, dynamo rotation, historical
rollback, a response arriving after a role change, competing objectors with different
intersections, objector replacement/backfill, agreement and rollback disengagement, and
preference for a started objector when the dynamo disappears. A further 64 reproducible QuickCheck
integer-vector seeds (331001–331064) vary jump lengths and acceptance before rotation,
disconnection and idling. These supplementary events are explicitly separate from the
original upstream generator.

This is a differential test of CSJ control decisions. The generic snapshot payload is owned
by the validated header gate; the node's signed-header/graft tests exercise its transfer.
Mock hash text is retained losslessly as UTF-8 solely to identify the native test points.

Build the shared pinned native source with
`scripts/oracle/genesis-density/export.py`, which provisions the observer executable.
To re-export from that prepared build:

```sh
python3 scripts/oracle/genesis-chain-sync/export.py \
  --container scarab-33-native-genesis \
  --source /tmp/scarab/33-genesis-density/source \
  --upstream /path/to/pinned/ouroboros-consensus \
  --probe-dir /tmp/scarab/33-genesis-density \
  --output-dir fixtures/reference/genesis-chain-sync
```

The wrapper verifies that the source still equals the pin and the compiled observer source
matches before exporting. Native consensus license and notice are retained at
[LICENSE](../../licenses/block-fetch-consensus-LICENSE) and
[NOTICE](../../licenses/block-fetch-consensus-NOTICE).
