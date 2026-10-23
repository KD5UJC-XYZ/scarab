# Conway body and witness-set decoding at decoder version 9

Status: Current. Evidence for [AUD3-01/#327](https://github.com/KD5UJC-XYZ/scarab/issues/327).

`cases.json` holds 222 original encodings written by `scripts/oracle/conway-decoding/cases.py`:
147 Conway transaction bodies, 65 Conway witness sets, 4 whole Conway transactions and 6 Babbage
bodies. Each case breaks one rule of the reference decoders or keeps a rule the reference tolerates,
and most sit next to a twin that keeps the rule: repeated or empty sets, ordered sets, maps and
voting procedures; the tag 258 wrapper; indefinite-length containers; two encodings of one input;
a zero donation; key 6; empty witness lists; a Plutus script listed twice, and the same script under
two languages; repeated vkeys, native scripts, datums and redeemer keys, which the reference keeps
accepting.

The ordered sets are judged on the decoded elements under the reference's derived `Ord` (`OSet`,
`Data/OSet/Strict.hs:71-75, 109-116`; `StakePoolParams` and `PoolMetadata`, `State/StakePool.hs:293-297,
424-435`; `ConwayTxCert`, `Conway/TxCert.hs:473-486, 568-574, 643-647`; `ProposalProcedure` and
`GovAction`, `Conway/Governance/Procedures.hs:498-504, 815-848`; all f649f975). Scarab keeps a pool's
relays and metadata and a parameter update's fields as their original encodings, so the cases
repeat each constructor that keeps encoded data (pool registration, parameter change, treasury
withdrawals) and a sample of the others (stake delegation, DRep registration, hard fork, committee
update, constitution, information action, no confidence) in a second encoding of the same value (relays and
metadata written indefinite or with wide headers, a margin or rational not in lowest terms, a
parameter update or committee update with its entries in another order, treasury withdrawals
reordered) and each is next to a twin with a different value that both accept. Rationals (tag 30) are read as the reference does below decoder version 12 (`decodeRationalWithTag`,
`Binary/Plain.hs:155-163`; `BoundedRatio` `decCBOR`, `BaseTypes.hs:416-430`; `boundRational`,
`BaseTypes.hs:348-374`, f649f975): two `decodeInteger`s of any sign or size (bignums included), a
zero denominator refused (bignums only from the one-byte heads `c2`/`c3` over a definite byte
string, as cborg reads them: a widened tag head or an indefinite string is `expected integer`),
then the reduced value bounded, so `30([1, 2])`, `30([-1, -2])` and
`30([2^64, 2^65])` are one value in a proposal's parameter update (prices, thresholds), a pool margin
and a committee quorum, and `[-1, 2]` or `[3, 2]` are refused where the interval is 0 to 1. An indefinite
byte or text string, and an indefinite array where the reference reads a fixed record (a price
pair, execution units, a proposal), are refusals of other rules (see "Not covered") and are
not used as repeats.

`reference.json` is what the unchanged released decoders of cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5` (the libraries of node 11.1.3) did with each encoding:
`decodeFullAnnotator` at decoder version 9 for Conway (`eraProtVerLow`) and 7 for the Babbage
bodies, with the complete failure text. `provenance.json` records the package versions, the hash of
each decoder source file against the pin (one file differs in two unrelated lines, recorded there),
and the hashes of the harness, generator and both fixtures.

`cases.json` also names, for every refusal, `rule` (a fragment of the reference failure text, so the
case is shown to fail for the rule it breaks) and `expect` (the Scarab `LedgerDecodeError` case).
`ConwayDecodingReferenceSuite` decodes each encoding with `TxBody.decode`, `Witnesses.decode` or
`Tx.fromEnvelope` and requires the reference verdict and that case.

Scope: wire decoding only. No ledger rule, signature, script or transaction admission is run. A
refusal here is the decoder's, which in the reference ends the block or transaction before any rule.
Not covered: an indefinite-length byte string where the reference reads a definite one (it refuses
one for a hash or a script), parameter-update maps and the pre-Conway eras' empty-list rules.

Regenerate: see the [oracle recipe](../../../scripts/oracle/conway-decoding/README.md). The
encodings are deterministic; rerunning the oracle must reproduce `reference.json` byte for byte
after the whitespace formatting.
