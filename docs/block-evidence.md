# Partial same-block evidence ingestion

`block-evidence` consumes one original disk block plus one explicit context file. One bounded, owned CBOR parse feeds structural indexing, body commitments and a typed header projection. Every key, certificate, signature, slot, body declaration and KES message is derived from that same input. The CLI writes a receipt to stdout and changes no store or chain state.

```sh
./scripts/sbtw 'app/run block-evidence fixtures/block-evidence/blocks/original-08.cbor fixtures/block-evidence/contexts/original-08.cbor.tsv'
```

A successful receipt means the body hash/size, OpCert signature, supplied timing bounds and source-profile candidate-message Sum6 checks completed. VRF is `not_checked_missing_nonce` unless an explicit hashed nonce plus its source digest was supplied; a supplied nonce must pass proof and claimed-output checks. Missing nonce is never replaced with NeutralNonce or zero bytes. This ingestion profile accepts hashed nonces only; the separate primitive's NeutralNonce support does not broaden it.

There is no `ValidBlock` or consensus-valid conversion. Issuer/pool authorization, registered VRF-key binding, OpCert counter state, state-derived nonce, leadership, protocol-version admissibility, ledger application, historical inclusion and chain selection remain unchecked. A valid signature under a self-chosen header key establishes none of them.

## Local profile and message contract

Profile `praos-body-opcert-kes-source-candidate-v1` admits only:

- Babbage disk tag 6 with declared protocol 7.0 or 8.0
- Conway disk tag 7 with declared protocol 9.0, 9.1 or 10.0
- Concrete 32-byte parent, expected key/signature/proof widths; profile VRF output width 64
- Word64 slot/block-number/counter, Word32 body size/protocol minor, and locally narrowed Word32 KES start
- A ten-field Praos body and complete header whose existing explicit shortest-definite encoder reproduces the original bytes exactly

These pairs are local source/candidate admission, not allowable protocol parameters of a ledger state. TPraos, Byron, null parent, unsupported envelopes/versions and alternate header encodings are `Unsupported`. The source KES start is machine Word and OutputVRF can decode arbitrary byte-array lengths; the local narrowing is therefore an unsupported-profile boundary, not a claim that those source inputs are malformed.

Pinned Haskell source signs `serialize' (pvMajor hbProtVer) HeaderBody`, not arbitrary original bytes. The retained source-closure packet establishes the reached field order, nested groups, widths and common PV7/8/9/10 routing. Its actual cborg/Serialise resolved build plan remains unavailable. There is no fresh Haskell encoder/decoder/full-Sum6 oracle and no general serializer/decoder equivalence claim. The message evidence is explicitly `source_profile_shortest_definite_candidate`; KES verifies those candidate bytes. Original full-header hashing remains byte-preserving.

The profile hash includes source revisions, local admission rules, explicit implementation/backend/evidence revisions and a revision policy. Any change to acceptance rules, strict crypto backend semantics, checked-stage coverage or evidence interpretation requires changing that profile revision. Receipt digests are reproducible diagnostic identities, not authenticity seals against an attacker who can rewrite the receipt and digest.

See the retained source review (optional private corpus; not distributed) and source index (optional private corpus; not distributed). Package-source revisions differ from the broad monorepo pins, although compared changes do not alter the reached HeaderBody expression. The copied packet contains reached Haskell sources and retrieval manifests; it does not contain a resolved build plan or executable reference oracle. Relative links in the upstream research review may refer to sibling research packets; the vendored module inventory and immutable URLs identify the retained sources.

## Context and receipt binding

The context TSV is exactly one LF-terminated ASCII row with eleven fields:

1. `block-evidence-context-v1`
2. Expected original block SHA-256
3. Expected original header Blake2b-256
4. Expected parent hash
5. Expected slot as canonical unsigned decimal
6. Bounded ASCII network/assumption attribution label
7. Timing-source SHA-256
8. Slots per KES period, positive uint64
9. Maximum KES evolutions, 1–64
10. Supplied nonce hash32, or `-`
11. Nonce-source SHA-256, or `-`; both nonce fields must be supplied or absent

The fixture files demonstrate the exact format. There are no implicit network settings or separately supplied issuer keys, relative periods, messages, certificate fields or proofs. The context binds the expected block/header/parent/slot before crypto. Source labels/digests record assumptions; they do not prove historical applicability. Header and block have no network identity field from which to infer a network.

Current period is decoded slot divided by supplied slots-per-period. BigInt subtraction avoids unsigned underflow; require relative >= 0, relative < supplied maximum and relative < 64. No overflow-prone `start + lifetime` operation is used. A different context with the same quotient may still verify; its context and receipt digests must change.

The context digest uses a specified binary domain/version, fixed field order, digest bytes, BE8 unsigned numbers, bounded BE4-length-prefixed ASCII label and a nonce-presence byte. The receipt binds profile hash, original block/header hashes, parent, era, slot, block number, protocol pair, candidate message digest, context digest, current/relative period, body size/hash and VRF coverage. The independent Python projector computes the same context/receipt goldens without invoking Scala.

No receipt is produced after a required check rejects or remains unsupported. A receipt with missing nonce is explicitly completed **partial evidence**, not an all-check result. Receipt publication here is stdout only; no durable adoption or atomic receipt-store claim is made. Existing fetch/store identities and structural admission APIs are unchanged.

## Independent original-block evidence

The packet contains 15 genuine retained full blocks, all with matching body hash/size and OpCert signatures. Their header-body candidates equal the exact original body spans.

| Original group | Count | Declared versions | Sum6 under 129600/62 |
| --- | ---: | --- | --- |
| Mainnet-source-labelled Babbage | 4 | 7.0 | 4 pass |
| Mainnet-source-labelled Conway | 4 | 9.1 | 4 pass |
| Preprod explorer-linked Babbage | 5 | 8.0 | 5 pass |
| Network-unestablished Pallas Babbage | 1 | 7.0 | Rejects under this supplied assumption |
| Network-unestablished Pallas Conway | 1 | 9.0 | Passes conditionally under this assumption |

For the first eight, the retained node-revision mainnet Shelley genesis supplies 129600/62. Archive source labels are not Mithril certificate authentication. The five preprod blocks match retained explorer response hash/slot/height/parent fields, while the pinned public Amaru preprod context supplies 129600/62. Explorer linkage is not chain authentication.

The two standalone Pallas networks remain unestablished. The Babbage rejection is a conditional result under 129600/62, not an invalid-Cardano-block claim. No alternate period was searched to force it to pass. The Conway success does not establish its network.

Native research used the existing pinned public Ed25519 helper and a source-derived Sum6 tree. No signing/proving/keys were used, and no independent full Haskell KES oracle was run. Runtime uses existing JVM primitives only. No epoch nonce was guessed for these positives; there is no genuine integrated full-block VRF positive. A separately recorded all-zero synthetic nonce rejects against one real block's public VRF proof.

Thirteen source-labelled full-block positives provide the vertical milestone without requiring the unrelated original-four header fixtures to have matching bodies. The earlier header-only plan is superseded for the next milestone, while its no-fabricated-body rule remains in force.

## Failure types, limits and CLI exits

Core failures distinguish malformed, unsupported, predicate rejection, resource limit and internal failure, with a stage. Fatal JVM errors propagate. The test observer can count/interrupt real stages but cannot replace a successful cryptographic result or mint a receipt from synthetic outcomes.

Limits are local policies: block 1 MiB, CBOR depth 32/items 100000/strings 1 MiB, header 4096 bytes, context file 4096 bytes. Input files must be regular files, not symlinks/special files; bounded reads detect growth. No network, output-file or persistent-store option is provided.

Direct JVM exits: 0 = completed stated partial evidence; 1 = predicate/context rejection; 2 = malformed/resource/input error; 3 = unsupported profile/input; 4 = internal verification failure. Sbt may collapse nonzero application codes into its own build failure.

[Verification record](block-evidence-verification.md). Existing APIs' original structural/body regression suites remain required. General reference decoder parity, current-state consensus, ledger replay and validator readiness remain future work.
