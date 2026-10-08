# Conway PV9 required-key witness coverage

Two genuine Haskell-derived transaction events from the pinned Blueprint archive:
an accepted setup transaction and a transfer rejected with `MissingVKeyWitnessesUTXOW`.
Both transactions' provided signatures verified in the separate archived public-only
Cardano-fork sodium experiment. The rejected transaction is missing Alice's payment
key witness despite its one provided signature verifying.

## Reproduce

From the repository root:

    python3 scripts/project-coverage-fixtures.py
    python3 scripts/project-coverage-fixtures.py --check
    python3 scripts/verify-coverage-projector.py

The projector uses only Python standard-library public-input parsing and hashing.
It pins every input before interpreting it, reuses the pinned bounded CBOR decoder,
checks duplicate map/set/input identities, limits file/tar/CBOR resources, verifies
Conway/PV9 configuration and exact transaction/witness profiles, and preserves all
raw transaction bytes. `--check` writes nothing. It neither reruns Haskell nor loads
native libraries or invokes the reference CLI. It fails under Python `-O`.

## Wire schema

Each `conway-pv9-missing-vkey-{accept,reject}.tx.cbor.hex` contains the complete,
unchanged four-element transaction envelope as lowercase hex plus newline.
The corresponding `.resolved.cbor.hex` is a CBOR map whose keys are actual
`[transactionId bytes32, outputIndex uint16]` pairs and whose values are the full
original two-element output `[address bytes, value]`. Outputs retain original
CBOR bytes and addresses, rather than only precomputed payment-key requirements.
Only the transaction's spent inputs appear in this fixture map. The application
should independently derive required hashes from addresses and provided hashes
from actual witness public keys.

`manifest.json` identifies cases and artifact hashes. `coverage-vectors.tsv` has
four tab-separated columns: name, full transaction hex, resolved-output map hex,
expected coverage boolean (comment header starts with `#`). `projection.json`
adds half-open ranges into `raw/missing-vkey.cbor`, exact original body bytes and
Blake2b-256 transaction IDs, full resolved outputs, and reconstructed key sets.

The accepted event is applied using its exact original body hash; the rejected
event is not applied. The final UTxO projection matches both addresses and values
against the archived final snapshot. This is not a full ledger-state transition.

## Evidence boundaries

The raw archive stores an actual Haskell LEDGER boolean for each event. The pinned
`upstream/generator-UtxowSpec.hs` lines 64–77 explicitly removes Alice's witness and
asserts `MissingVKeyWitnessesUTXOW [asWitness aliceKh]`. The separately referenced
pinned `amaru-fork-ShelleyImpTest.hs` lines 1165–1170 record actual transition success;
lines 1193–1217 assert exact failure lists. The reconstructed missing hash in JSON
is not a serialized Haskell golden failure payload. No original Haskell tests were
rerun, and this is archived PV9 evidence, not freshly generated node-11.1.3/PV10
full-ledger conformance.

The genuine pair has body fields `{0,1,2}`, witness field `{0}`, `isValid=true`, no
auxiliary data, and payment-address types 0 and 6. Explicit signer field 14 and
empty/unknown input negatives have source-derived/synthetic evidence only.
Coverage success does not prove provided-signature validity or transaction validity.

## Public oracle records

`oracle/` preserves the unchanged research CLI output, public verification-key
text envelopes, sodium signature observations, CLI binary hash provenance, and the
original research runner. Paths in the original runner and command records are
historical; that runner expects the separate research layout and binaries and is
not a repository runtime dependency. The production projector checks archived
key-hash observations against actual transaction public keys and checks the public
envelopes, without executing the runner. CLI version is 11.2.3.0, bundled with
node 11.1.3. Hashing public keys is not a ledger-validation oracle.

An optional portable check accepts a separately acquired binary by explicit path:

    python3 scripts/check-coverage-public-key-hashes.py --cli /path/to/cardano-cli

It verifies the exact pinned CLI SHA256 before execution, validates all fixture
inputs, and compares both actual public-key hashes to the projected Blake2b-224
values. It uses a minimal environment without socket variables, closes inherited
file descriptors, imposes per-command timeouts, and prints results without writing
fixtures. The binary is not included and remains an optional research tool.

No signing keys, secret seeds, key generation, signing, node startup, socket,
external-peer activity, or transaction submission is included.

## Attribution

Archive, parameter bytes and licenses are reused by reference from
`../ledger/` and `../licenses/`; full URLs, pins and hashes are in `provenance.json`.
The unmodified new generator source uses the same Apache-2.0 Haskell-generator
license and NOTICE already retained there. No Haskell implementation was copied
into the Scala implementation.
