# Full Byron registration and signed block composition (#171)

**Status: Historical** — executed offline on 2026-10-18; test/tool fixtures, no production rule change.

The 22 cases retain complete original public proposal and block bytes, a generated genesis, exact
reference verdicts and build provenance. The fourteen signed cases use eleven version transitions
(six legal, five illegal), an unauthorized proposer, a bad proposal signature and a bad header
signature. Every complete signed block contains one funded, witnessed ordinary transaction.
The six valid composite cases register the expected protocol version, spend the genesis input and
create the transaction output. Scala's `ByronRegistrationFixtureSuite` replays the same bytes through
proposal registration, transaction validation, block-ledger rules and `ByronRules`.

This closes the earlier **reference execution** gap beyond the extracted `pvCanFollow` predicate.
The actual unchanged released Haskell APIs are:

- `Registration.registerProposal`: delegation, original signed bytes and all registration checks;
- `Byron.API.applyMempoolPayload`: proposal admission and, separately, ordinary transaction admission;
- `Byron.API.validateBlock`: the complete decoded block's ledger rules, proofs, transaction witnesses
  and proposal registration;
- `Crypto.verifySignatureDecoded` with `SignBlock genesisVK`, using the original annotated
  `recoverSignedBytes`, and `Delegation.isValid`: authentic header and certificate crypto gates.

The final result composes the last two checks. The bad-header case deliberately has
`ledgerBlock = Accepted` and `headerSignature = false`, hence `compositeBlock = InvalidSignature`.
Ledger `validateBlock` alone does not check the header signature; treating it as complete signed
block acceptance would be incorrect. This is an explicit composition of the unchanged ledger and
crypto APIs on controlled synthetic adopted-version state. It does not execute a live PBFT chain,
protocol adoption, endorsement quorum or stability history.

Eight further cases replace only one recorded CBOR item: header or proposal major/minor 65536,
alt 256 and major 4294967297. `mutations.json` records original spans, replacements and hashes.
Their original signatures/proofs become stale; the actual reference rejects their bounded fields
while decoding the complete block/proposal, before any crypto or registration. No valid-signature
claim is made for those eight variants. The standalone proposal must match the proposal's original
bytes inside each successfully decoded block; the harness checks this before evaluating it.

`build-plan.json`, the Cabal freeze file, package-archive hashes and embedded package IDs bind the
build. Compiler: GHC 9.10.3, Cabal 3.14.1.1; the release executable reports GHC 9.6. This is a
same released-package/source execution, not a bit-identical build of the reference executable.
Every queried package version matches the actual node 11.1.3 embedded ID: Byron 1.3.0.0,
crypto-wrapper 1.7.0.0, ledger-binary and cardano-binary 1.9.1.0, legacy crypto 1.3.0,
crypto-class 2.5.1.0 and prelude 0.2.2.0. The final build uses the released archives. All 91 Byron
source modules match pinned ledger `f649f9751074d2ab3de033fc3912f29c9862c1f5`; the separately
recorded unused redeem-secret-decoder difference in the cached git crypto-wrapper is eliminated by
using the released wrapper archive. Cardano libsodium is pinned by the node's `flake.lock` to
`dbb48cce5429cb6585c9034f002568964f1ce567`; Debian libsodium alone lacks required VRF symbols.

The generator uses random throwaway keys only in its process. No signing key or seed is retained;
replay uses only the public original bytes. The synthetic fixtures and harness are Apache-2.0.
Upstream notices are retained in [licenses](../../licenses); no validation implementation is copied
into the harness. Reproduction and fresh-fixture generation are documented in the
[full oracle recipe](../../../scripts/oracle/byron/full/README.md).
