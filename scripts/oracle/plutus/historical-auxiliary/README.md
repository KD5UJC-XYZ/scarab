# Historical auxiliary-data oracle (#290 stage A)

**Current: unchanged released Haskell observer for Shelley, Allegra and Mary.**

A thin observer calls exported ledger APIs only: the era `TxAuxData` decoder plus
`validateTxAuxData` (`aux` rows), and the era `Tx` decoder plus the Utxow rule's exported
`validateMetadata` (`tx` rows), at PV2/3/4. It is a separate executable from the Alonzo+
[auxiliary oracle](../auxiliary/README.md), whose accepted 490 observations and hashes stay
untouched. Dependency pins are the same offline set (the context oracle image).

```sh
python3 scripts/oracle/plutus/historical-auxiliary/run.py --verify-only
python3 scripts/oracle/plutus/historical-auxiliary/run.py --build        # rebuild image, then replay
python3 scripts/oracle/plutus/historical-auxiliary/run.py               # replay the accepted image
python3 scripts/oracle/plutus/historical-auxiliary/run.py --build --capture packages.json  # first capture only
python3 -m unittest discover -s scripts/tests -p test_historical_auxiliary_oracle.py
```

`--capture` takes the released/pinned source comparison JSON recorded in the fixture
`provenance.json` (`releasedPackages`). Replays bind the immutable image, the embedded observer and
pins, and the executable hash before running read-only with no network.
