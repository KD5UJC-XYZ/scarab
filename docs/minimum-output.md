# Restricted minimum-output predicate

Local development; paired live admission evidence is pending. This does not establish full transaction or ledger validity.

The `conway-pv9-testnet-ada-minimum-output-v1` profile accepts Conway ledger protocol 9.0 only, scalar ADA outputs with Shelley testnet payment-key address kinds 0/6, and address/value-only array or map outputs. Existing Coverage rejects other body/witness/output fields. The cost must be a positive uint64. The predicate checks every created output including change; collateral, assets, datum and reference scripts are outside this profile.

For each original decoded output span:

`required lovelace = (160 + original output CBOR byte count) * coinsPerUTxOByte`

Integer arithmetic is exact, with no byte-to-word rounding. The exported `utxoCostPerByte` parameter is read using the bounded exact JSON parser. In the integrated transfer command it comes from the same SHA-256-bound pre/post parameter source as the existing typed context; digests attribute bytes but do not authenticate the exporter. The standalone command accepts caller-provided parameters without claiming source authentication.

```sh
java -cp "$(cat app/target/runtime-classpath.txt)" lab.Main minimum-output PARAMETERS_JSON TRANSACTION_CBOR_HEX
```

Exit 0 means every supported output satisfies this predicate; 1 means a supported output is below its computed minimum; 2 means malformed or unsupported input. JSON receipts include each original output, measured byte count, actual coin and threshold. `cluster-transfer` additionally requires this predicate and emits its separate receipt; the existing pure transition profile is unchanged.

## Source provenance

Research pin: cardano-ledger `226b002d5b5e83e24355f8a28ab214f3259eabda` (the repository's fixture ledger pin). This is not the version identity embedded in the official node 11.1.3 binary. Read-only inspection of the SHA-verified binary found Conway 1.23.0.0, Babbage 1.14.0.0 and binary 1.9.1.0 unit identifiers. Matching source packages in the node-pinned [CHaP revision 6eb65fb4](https://github.com/IntersectMBO/cardano-haskell-packages/tree/6eb65fb4b6efe04ff586ad807ae022a36523e6af) contain the same formula and original-span decoder. Their archive SHA256 values are respectively `486831d3d94060fff90e5e85c74030c744008526aa83f4a18215da124720cf1a`, `8a0466673e9de9f0a2375cd137fb1b6368c901929865b0def41abd83a200730d`, and `05d59229edffc94b8f61273117456601532e23d8656f3076775bbf38e600bb8a`. Embedded identifiers plus matching packages support version-level provenance; they do not establish the complete resolved build plan, package revisions/flags or reproducible binary identity. Release notes list binary 1.9.0.1 instead of embedded 1.9.1.0, so their table cannot replace build-plan evidence. No such complete identity is claimed.

- [Babbage minimum rule](https://github.com/IntersectMBO/cardano-ledger/blob/226b002d5b5e83e24355f8a28ab214f3259eabda/eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs): integer `(160 + sizedSize) * coinsPerUTxOByte`.
- [Conway output instance](https://github.com/IntersectMBO/cardano-ledger/blob/226b002d5b5e83e24355f8a28ab214f3259eabda/eras/conway/impl/src/Cardano/Ledger/Conway/TxOut.hs): delegates the sized minimum to Babbage.
- [Decoded Sized](https://github.com/IntersectMBO/cardano-ledger/blob/226b002d5b5e83e24355f8a28ab214f3259eabda/libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Sized.hs): records end minus start of the original decoded span. Reserialization need not have the same byte length.
- [Core API](https://github.com/IntersectMBO/cardano-ledger/blob/226b002d5b5e83e24355f8a28ab214f3259eabda/libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs): `getMinCoinTxOut` computes a fresh serialized size at the parameter protocol version. This is different from validating a decoded `Sized` output. The prototype uses original spans and does not extrapolate to other protocol versions.

At cost 4310, a 37-byte enterprise-address array output requires 849070 lovelace; the corresponding 39-byte map requires 857690. Synthetic tests also measure nonminimal integer spans and integer width boundaries; these are predicate tests, not claims that the reference accepts those encodings. The official CLI's minimum estimator returned 849070 for the chosen address; its internal estimator normalization remains to be traced and is not admission evidence.

## Paired private test

`scripts/private_cluster_minimum_output.py` extends the existing relay-only launcher, with its internal Docker network, temporary generated keys, resource bounds, readiness observations, producer pauses and cleanup. Before submitting, the adapter checks the actual Scala receipts for 37-byte array destination outputs at 849070 and 849069, both thresholds 849070, change increased by exactly one, and unchanged change byte width. Any mismatch stops the scenario. The negative must return an OutputTooSmallUTxO rejection with 849070, and leave the observed UTxO bytes and point unchanged. Then the boundary transaction follows the existing relay admission, inclusion, signature, balance, fee and original-block comparison path. The observations remain separate acquisitions rather than an atomic reference snapshot.

No paired reference run has started: automatic approval review blocked local test submissions under the original no-live-submission instruction. Direct user approval is pending. No admission success is claimed.
