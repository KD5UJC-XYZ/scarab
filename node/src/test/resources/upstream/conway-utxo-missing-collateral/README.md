# Native-only missing-collateral control

This separate released-reference `UTXO` observation supplements the 39 unique native/formal judgments in [the generated corpus](../conway-pv11-generators/README.md). It makes no new formal comparison claim.

The [helper](../../../../../../scripts/oracle/ledger-govcert/standalone-utxo-missing-collateral.hs) reads lawful `utxo-274101` and replaces only the transaction collateral set with one absent input. The environment and complete input state remain byte-identical. The original signed transaction is retained as `original-signal.cbor`; the changed body keeps those original witnesses, so their signatures are stale. Bare `UTXO` does not validate witnesses, and the observed failure is precisely missing input existence with no redeemers.

Released native ledger `f649f9751074d2ab3de033fc3912f29c9862c1f5` returns a singleton `BadInputsUTxO` for the constructed collateral input. The production `Sts(Utxo)` replay compares the complete typed predicate encoding against that native failure. Native source checks all spending, collateral and reference inputs unconditionally in `Babbage/Rules/Utxo.hs:375`, called by Conway before script execution/state update.

The manifest binds all original/modified CBOR, helper, separately built executable and private execution log. Its exact invocations use the private build/cache paths retained for this observation. Executable and run logs remain outside the checkout. The original generated corpus, original 39-judgment count, exporter executable and original failures are unchanged.

Before the standalone input-existence correction, the production replay accepted this native-rejected transaction; the failed regression log remains bound in the manifest. With correction `058b8700a7cc4dd39017a3e4cc145b1b55eb7bd6`, all 53 tests in the containing suite pass, as do formatting and the focused scalafix check.
