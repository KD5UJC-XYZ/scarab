# Genesis initial-funds input boundaries

Twelve cases captured through unchanged Haskell Addr/Coin JSON decoders, address serialization,
`initialFundsPseudoTxIn` and Coin compaction. Null results record native refusals. Each JSON line
retains all input and output fields, including canonical address and pseudo-input CBOR bytes.

[Provenance](provenance.json) records the immutable image, compiler, package versions, source
identity checks and hashes. Address.hs, Coin.hs and Shelley/Genesis.hs from the installed package
archives match ledger `f649f9751074d2ab3de033fc3912f29c9862c1f5` byte-for-byte; this is not a
claim that the complete installed package graph matches that checkout.

Replay from the repository root:

```sh
python3 scripts/oracle/genesis-initial-funds/run.py --image sha256:0d7d3971a5ff78c8a6786459d191f39b166d69a4d71a6eb310c9d8338d6e035e
```

The [native harness](../../../scripts/oracle/genesis-initial-funds/Main.hs) captures the cases;
the [replay](../../../scripts/oracle/genesis-initial-funds/run.py) compares every result.
`GenesisStartSuite` checks the captured boundaries. This corpus does not establish complete
genesis parser or state transition conformance.
