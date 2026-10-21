# BLS signature and serialization fixtures

These three retained files test the MinSig POP scheme and strict curve decoding used by Leios.
[`../README.md`](../README.md#bls-n2-bls12-381-minsig-voting-keys-votes-certificates) records original
upstream paths, commits, acquisition dates and licensing. `SHA256SUMS` binds every original file:

```sh
(cd fixtures/leios/bls && sha256sum -c SHA256SUMS)
```

`MinSigVectorSuite` reads the three public proto-devnet key pairs and proofs from
`devnet-pools.tsv`, compares derived verification keys and byte-exact regenerated proofs, and
checks every retained serialization negative from the eight-line `cardano-base` serde table.
The keys are published throwaway devnet material. `LeiosVote.golden.cbor` separately fixes the
vote codec's bytes; it is a codec golden, not a native signature-verification judgment for its
entire content. The corpus does not cover the incompatible MinPk AUG scheme or establish a native
seed-to-key golden: key generation uses the same underlying blst library as the reference.

For regeneration, retrieve the original pinned upstream files named in the parent provenance,
preserve their original public CBOR before stripping the key byte-string wrapper, and record source
and raw/derived hashes with any added rows. The historical acquisition/extraction steps have no
maintained one-command runner. Keep these original vectors/expectations unchanged; new upstream
releases or schemes require separately identified evidence rather than replaced goldens.
