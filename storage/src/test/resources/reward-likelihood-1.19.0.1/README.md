# Native reward likelihood results

Generated 2026-10-20 by the unchanged `cardano-ledger-shelley 1.19.0.1` reward API,
source `4c81e909df7555b6a58a000db8f8f4f89c7acdc4`, core 1.21.0.0 and binary
1.9.1.0, using the [existing reward wrapper](../../../../../scripts/oracle/rewards/README.md).
The observer image is `sha256:80a347549c20cec9203fc3144d93b329d70bc21b78ef4fbf9537acc479eb81b4`.
No reward formula is copied into the wrapper. The original fifteen-case pulser and
fifty-five-case timing corpora remain unchanged; their comparisons now include likelihoods.

`RewardLikelihoodCases` produces eight additional complete-state cases: no pools,
no produced blocks, one producing pool among nonproducing pools, native history
combination both with and without production, and Shelley decentralisation 0, 1/2
and 1. The two nonempty-history cases include a pool absent from the new map:
completion must follow Haskell's new-key-only traversal. Initial states plus one
pulse and completion compare as complete encoded CBOR, including exact Float bits.

Generate inputs with:

```sh
bash scripts/sbtw 'storage/Test/runMain xyz.kd5ujc.scarab.storage.haskell.state.RewardLikelihoodCases /evidence/likelihood-inputs.json'
```

Run sbt inside the Docker JDK image and execute the reward observer on those inputs
as the wrapper README describes. `RewardOracleSuite` binds the retained input JSON
to this fixture producer and compares every complete encoded result. Compressed
resources use gzip mtime zero. Decompressed SHA-256 values:

| Resource | SHA-256 |
|---|---|
| inputs.json | `1001cc446022256e1a9cc7fec97a70f2a4c316a28d59e524d3a2c4c2be5b34d1` |
| results.json | `7b368fa91ce69381549d538c46148e051e46b4125264fbf2b2addc609db0315e` |
| preview-pools.json | `2d882a8d96eb2f320a35182d4dd9cc1bea6f6d14c137a85abb73d1bb3473186f` |
| preview-likelihood-bits.json | `cac9114216f49be2df72c0f7e9a9dcfbede1c6af21eb9ff7150b5281784cbe12` |
| preview-completed-bits.json | `e9302cad43b12ef260810773b1cbaa2786067ebde84ecfa449b9a343d0adaa44` |
| preview-history-completed-bits.json | `bb1d8cdf0cff81033e2fad0a52b1abda1b430306bf42fdc7bc3df3f9f70fb99e` |

## Real pool inputs

The reduced Preview corpus comes from native V5 snapshot at slot 125,231,955,
block 4,753,556, epoch 1449, anchor
`2adcc2d82fe982320b5e065476a1a9b9671a2fcb44c75e4f4d1deca2cc04df5c`.
Its V5 file SHA-256 is `1323741149d81ef700b671f34d4964082102b22d0df4f097418ec7597dd8f536`.
This is the preserved current-format conversion used by the offline reward replay;
original capture/conversion provenance stays with the private archive. It contains
727 snapshot pools, 79,851 active credentials and 71 block-producing map entries;
its previous likelihood history is empty, a known limitation of the old runtime.
The epoch length is 86,400, f=1/20 and k=432. Circulating stake is
37,511,237,419,704,189 lovelace.

The unchanged Haskell wrapper decoded the complete captured EpochState and
BlocksMade and generated start and completed reward states. Local start and
completion both compared byte-for-byte equal across all fields. The reduced
resources preserve each original pool hash, stake and observed block count, plus
all 72,700 original generated Float bit patterns (unsigned 32-bit integers).
`RewardLikelihoodSuite` reconstructs synthetic account/registration details and
compares only likelihood generation and completion against these real scalar
inputs; it does not claim that reconstruction is the original whole ledger.

The history continuation is a second explicit API experiment: take the first
native completed NonMyopic field, replace only the input EpochState's NonMyopic
CBOR span with those original bytes, then call the unchanged native API again on
the same pool/block inputs. Compare the resulting likelihoods after Float 0.9
decay, addition and normalization. This exercises a native-generated preceding
history; it is not an assertion that the archived node had that history or a
second real epoch replay. Missing original histories are never synthesized in
production.

Private full-input/output SHA-256 bindings:

- Original input: `311f743d89e0aa9b2c1b370825f260cde616a0d4ea0af5f3509b260a31817748`.
- Original result: `c3bce5748a37892931846076a7039f1bdeacf1a3c29f01c8f5da8242c19193a2`.
- History input: `ce88bd13e5aa792f27cc6e02e62d26d864b985fc43c169f597def40b8b3dd84c`.
- History result: `893bc72055575d521ee3977762d7fdab1d3ebd58769a328f4a5bf06a6e17009e`.

The standard-library-only reducer preserves the original decoded scalars and Float bits:

```sh
python3 scripts/oracle/rewards/reduce_likelihoods.py INPUTS RESULTS HISTORY_RESULTS OUTPUT_DIRECTORY
```

`INPUTS` and `RESULTS` are the first private original API files; `HISTORY_RESULTS`
is the second API result described above. The reducer output can differ in JSON
whitespace from the retained resources; compare decoded JSON values. It does not
run the oracle or fill in a missing sample. Its output reproduces all retained
pool/scalar/sample values exactly.

The full states and operational logs remain outside the checkout. These reduced
public pool statistics and generated API values contain no signing keys or
private operational data. Scarab fixture producers are Apache-2.0; upstream
notices remain in fetched packages and [fixture licenses](../../../../../fixtures/licenses).
