### Fixed (Plutus admission rules, #155, #166, #167)
- **A `value` constant is checked as the reference decodes it.** Scalus rebuilt a `value` constant as a normalised map, so a constant with unordered or repeated keys, an empty inner map, a zero quantity, a key over 32 bytes or a quantity beyond 127 bits was a valid script here and an undecodable one for the reference. `FlatScan` walks the original flat bytes once (explicit stack) and `ScriptDecoder.validate` and the evaluator refuse such a script, at every protocol version and in every language (phase 1 from Babbage on).
- **Protocol-11 decode limits** (#166): from protocol 11 a constant whose type header has more than 32 tags, or a `constr` with more than 1024 fields, does not decode; below 11 there is no bound.
- **The applied term must be closed, as the ledger checks it** (#167): a variable the scope check refuses fails the evaluation at setup (`EvalFailure.OpenTerm`, a phase-2 failure) even inside an unforced `delay`; the check mirrors `checkScope` and keeps the 64-bit index, so an index that Scalus would narrow is not read as another variable.
- **`hashToGroup` with a domain separation tag of non-ASCII bytes fails with a typed `EvalFailure.Unsupported`** instead of answering with another point than the reference (Scalus passes the tag to blst as a string).

### Changed (Plutus conformance, #155)
- [docs/plutus-conformance.md](https://github.com/KD5UJC-XYZ/scarab/blob/fadf4ae2363e2986ab620ee6ff2fcc8e59ab7948/docs/plutus-conformance.md) pins the reference evaluator (plutus 1.70.0.0, commit `507cab8c`, read from the package identifiers of the pinned 11.1.3 executable) and classifies the 22 allowlisted cases: nine reachable by a transaction, two not a divergence, eleven differences of the suite harness. The allowlist keeps the 22 with new classes and reasons.

#### Known gaps
- `hashToGroup` with a non-ASCII tag is refused, not computed; the Haskell side of the boundary cases is read from source, not run (plutus-conformance.md, section 5).
