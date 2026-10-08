# Supplied-context Praos eligibility (experimental)

This additive profile composes `PraosCertificateState.Applied` with the existing strict draft03 VRF verifier and a source-derived E34 leader comparison. It does not widen protocol-version acceptance, change header serialization, or claim state-derived consensus or independent reference-runtime parity. Existing profiles remain unchanged.

## API and bindings

`PraosEligibility.Context.checked` binds the certificate context and exact seed state, protocol-export SHA256, fixed epoch window, explicit epoch nonce, exact active coefficient, and issuer-to-stake map into its context ID. `PraosEligibility.check(context, steps)` accepts only a contiguous prefix of one to eight authenticated certificate transitions starting at that seed. Original header/body bytes, canonical serialization, KES timing/signature, issuer/VRF registration, OpCert signature/counter transitions, parent linkage, and checkpoint semantics come from the existing certificate pipeline. It rechecks VRF key binding, verifies the original proof against slot and supplied epoch nonce, and checks leader eligibility. A failed eligibility check publishes no new pair of branch and eligibility result.

`PraosEligibilityContext.loadTransfer(directory, protocolBytes, expectedProtocolSha256)` reuses `CertificateBranch.loadTransfer` and its v2 transfer manifest/source checks. `applyPrepared(prepared)` returns `(CertificateBranch.Branch, PraosEligibility.Checked)` only after both stages succeed. Integration can retain this pair and use the existing certificate branch rollback; replay eligibility against the same context and retained prefix when needed. No CLI is added.

Successful results explicitly expose `suppliedContextEligibilityVerified=true`, `stateDerivedConsensus=false`, and `referenceRuntimeParity=false`. The name refers to verification under supplied state, not authentication of the supplied snapshot.

## Capture contract

Capture full raw JSON bytes from the same private Conway node using:

```
cardano-cli conway query protocol-state --testnet-magic 1082026 --socket-path /work/env/socket/node3/sock --output-json
cardano-cli conway query ledger-state --testnet-magic 1082026 --socket-path /work/env/socket/node3/sock --output-json
```

Keep exact SHA256 pins and the existing transfer v2 capture manifest, raw block/header bytes and genesis. Required fields are:

| Source | Required values |
| --- | --- |
| protocol-state | `lastSlot` matching the named anchor; `oCertCounters` map; `epochNonce` as canonical 32-byte lowercase hex or explicit JSON null for NeutralNonce |
| ledger-state | `lastEpoch`; `stakeDistrib.pdTotalActiveStake`; `stakeDistrib.unPoolDistr[issuerColdKeyHash].individualPoolStake.{numerator,denominator}`; `.individualTotalPoolStake`; `.individualPoolStakeVrf` |
| genesis | `epochLength`, exact numeric `activeSlotsCoeff`, existing `slotsPerKESPeriod` and `maxKESEvolutions` |
| captured branch | existing checked anchor and original-byte blocks/headers, all inside the supplied fixed epoch window |

Bracket both state queries with stable tip reads and retain those reads as acquisition evidence. Queries are separate and non-atomic; protocol JSON has no block hash. Digest/slot/source binding does not authenticate their relationship to chain state. Missing nonce is an error, never an implicit neutral nonce. Keep `epochNonce`, not `candidateNonce`, `evolvingNonce` or `labNonce`. Cross-epoch headers need a new snapshot or actual nonce/stake evolution, neither of which this profile derives. The slot-to-epoch mapping is the explicit private genesis fixed-length profile, not a general hard-fork epoch-history interpreter.

The adapter rehashes genesis and ledger bytes against the already checked certificate context, pins protocol bytes, parses decimal/scientific coefficients through exact decimal integers, checks each stake ratio against its exported pool amount and total, and requires the stake map to match the certificate registration map. It does not derive stake from UTxO balances. Fractions are bounded to Word64 numerators/denominators; active coefficients are restricted to `(0, 1/2]` plus the reference testing case `1`. Unsupported inputs fail explicitly.

## Pinned rules and arithmetic

Consensus `ouroboros-consensus-4.2.0.1`, archive SHA256 `a645670ccbb25179c96a10c8082f5bfb84660fbab14a255193c989359cd34501`, performs pool lookup, VRF-key-hash binding, VRF verification, then `checkLeaderNatValue`. The existing public evidence `fixtures/praos/evidence/Protocol-Praos-VRF.hs` pins the VRF input and output-extension rules: Blake2b256 of big-endian Word64 slot followed by the epoch nonce (neutral adds no bytes), and unsigned big-endian Blake2b256 of ASCII `L` followed by the verified 64-byte VRF output.

Ledger commit `f649f9751074d2ab3de033fc3912f29c9862c1f5` supplies:

- [TPraos/BlockHeader.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-protocol/src/Cardano/Protocol/TPraos/BlockHeader.hs), SHA256 `16f4eb6ebf6b8218fda3f5d7b877d44eae441d8e64fb81adcee75bdf8f83059f`: `checkLeaderNatValue`, E34 comparison and the `f == 1` exception (even zero stake).
- [BaseTypes.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-ledger-core/src/Cardano/Ledger/BaseTypes.hs), SHA256 `0b86d1ecdd5abb0cf89194e7324fe1f62e58214cbd7386a1c50aeaf1c605866e`: cached active-slot logarithm, precision and nonce JSON.
- [NonIntegral.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/non-integral/src/Cardano/Ledger/NonIntegral.hs), SHA256 `12a0a3fb63f2e39a3473239f751750b960f581cc815829a4f2ac23056c1cf890`: continued fraction, epsilon `1e-24`, 1000-step Taylor comparison and reject-on-limit behavior.

The implementation uses BigInt at scale `10^34`, including floor rounding for signed multiplication/division. It specializes logarithm splitting to exponent -1 for the accepted ordinary coefficient domain. The source expression `-fromRational sigma * c` negates the product: Haskell2010 prefix negation has precedence 6 while multiplication has precedence 7. The pinned cardano-protocol cabal file selects Haskell2010 without LexicalNegation. [Haskell2010 expressions](https://www.haskell.org/onlinereport/haskell2010/haskellch3.html) and [Data.Fixed](https://github.com/ghc/ghc/blob/ghc-9.6.4-release/libraries/base/Data/Fixed.hs) support fixity and floor rounding; the latter is standard-library source evidence, not proof of the reference binary's GHC identity. No Float/Double or real-number approximation is used in the implementation.

## Validation and limits

Public tests exercise the pinned draft03 proof, wrong/neutral nonce, changed slot, required registration, zero/whole/tiny stake, malformed context and epoch/branch mismatches. A rational-power integer oracle checks a grid away from the decision boundary independently of the Taylor implementation; it is not a runtime-parity oracle. Tiny stake tests distinguish signed-floor rounding before negation. Opt-in private tests use retained exact sources, test nonce/digest tampering and missing nonce, and validate the already captured slot-199 header under its supplied epoch-zero pool distribution. Private source bytes and logs are not committed.

No fresh reference process, cluster, or independent Haskell runtime oracle was used. Near-boundary bit-for-bit acceptance parity remains unproven; a matching reference executable oracle and adversarial boundary corpus are still needed. Draft03 curve/subgroup acceptance remains limited by the existing experimental verifier. Nonce evolution, stake snapshots/rewards/delegation evolution, epoch transitions, snapshot atomicity/authentication, and full ledger/consensus validation remain outside this profile.
