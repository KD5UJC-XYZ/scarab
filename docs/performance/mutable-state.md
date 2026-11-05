# Retained mutable state

**Status**: An index of the mutation the main sources retain, one row per cluster, checked against the tree at commit `c93f09b` (2026-11-04). It is an index, not a measurement: the sections it links are the evidence, and the issues it names are where the evidence is missing.

AGENTS.md allows imperative, side-effecting code only in performance-critical paths, with profiling or
benchmark evidence and a written reason for the specific path. It also names narrow exceptions that are
not performance claims at all: a third-party interface that requires the form, and key material that
must be erased in place. WartRemover enforces the default — it refuses `var`, `while`, `throw` and the
mutable collections in the main sources of every module — so a site that keeps mutation carries
`@SuppressWarnings(Array("org.wartremover.warts.<Wart>"))` on its smallest definition, with a comment
naming its measurement, its library contract, or why it is erased in place.

This page is the other half of that arrangement. For each cluster of retained declarations it answers
two questions: which section here measures the path, and if none does, which issue owns the missing
measurement or which contract makes the form unavoidable. A cluster with neither is a finding, not an
exemption.

## What the count is

The audit that produced this page counted 213 top-level `var` declarations in the main sources at
`7dfcded1` (2026-10-19), before WartRemover existed. It counts declarations rather than clusters, and
many of those declarations have since been converted: what is left is 77 declarations in 24 suppressed
definitions, across fifteen clusters in eight modules. Every one of them is under a suppression; none
is an unguarded site the compiler let through.

The inventory, so that this page can be re-derived rather than trusted:

```
grep -rn --include=*.scala 'warts.Var' */src/main          # the suppressions that keep a var
grep -rn --include=*.scala -E '(^|\s)var\s+\w+' */src/main # the declarations themselves
```

A row's count is the declarations in that cluster. A cluster may keep several suppressions — one per
definition — because the rule asks for the smallest definition, not the smallest file.

Scope: this page covers the `Var` warts only. `While`, `MutableDataStructures` and `Throw` sit under
the same rule and are not indexed here, and `try`/`catch`/`finally` has its own register in
`docs/data/imperative-baseline.json`, written by `scripts/check_imperative.py`.

## The index

| Cluster | Module and file | `var` declarations (suppressions) | Why it is kept | Evidence |
|---|---|---:|---|---|
| `Bytes` hash and prefix caches | codec, `codec/src/main/scala/xyz/kd5ujc/scarab/codec/Bytes.scala` | 2 (2) | Computed on first use and kept, racy but idempotent as `String`'s hash: without them the decode of a real preprod state took 10 percent longer | [Section 49](memory-and-snapshots.md#49-reading-a-decoded-document-functional-against-imperative-navigation-sharing-and-caching-650) |
| The CBOR stack machine | codec, `codec/src/main/scala/xyz/kd5ujc/scarab/codec/Cbor.scala` | 18 (1) | The decoder's position, item count, record and open-container state, and the loops that read arguments, floats and strings; the same decode without mutation took 11 to 14 times as long and allocated 23 to 70 times as much | [Section 48](peers-and-network.md#48-the-cbor-decoder-functional-against-imperative-650) |
| The decoded-document index and its navigation | codec, `codec/src/main/scala/xyz/kd5ujc/scarab/codec/Document.scala` | 19 (5) | The index is written as the decoder reads; the child and entry walks built as lists or `Iterator.unfold` made a preprod decode allocate 36 to 71 percent more and take 11 to 23 percent longer, and the iterators alone 14 percent more allocation | [Section 48](peers-and-network.md#48-the-cbor-decoder-functional-against-imperative-650) for the index, [section 49](memory-and-snapshots.md#49-reading-a-decoded-document-functional-against-imperative-navigation-sharing-and-caching-650) for the walks |
| The interning table and its lookaside | codec, `codec/src/main/scala/xyz/kd5ujc/scarab/codec/Interns.scala` | 7 (2) | The last table found is a volatile field rather than an `AtomicReference` of an `Option`, which made a preprod decode 16 percent slower; the grow path is 1.67 times the time and 1.54 times the allocation without it | [Section 49](memory-and-snapshots.md#49-reading-a-decoded-document-functional-against-imperative-navigation-sharing-and-caching-650) |
| The mini-protocol reader cursor | protocol, `protocol/src/main/scala/xyz/kd5ujc/scarab/protocol/ProtocolWire.scala` | 8 (1) | A cursor over the input with `while` loops and a mutable stack of open containers; the functional walk of the same checks took 2.4 to 3.2 times as long and allocated 4 to 11 times as much on real blocks | [Section 45](peers-and-network.md#45-the-cbor-walk-of-the-mini-protocol-reader-functional-against-imperative-657) |
| The snapshot byte sink and source | storage, `storage/src/main/scala/xyz/kd5ujc/scarab/storage/Codec.scala` | 3 (2) | Every byte of a snapshot is written through the sink and read through the source; a functional form would thread the position through every codec. Not measured yet | Exemption: the measurement is [#662](https://github.com/KD5UJC-XYZ/scarab/issues/662) |
| The detached-record walk | ledger, `ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/DetachedRecords.scala` | 5 (1) | Walked with a frame an item rather than a stack: a deeply nested refused input made the fuzz gate's blocks and outputs allocate past their bound of 1000 bytes an input byte | [Section 53](peers-and-network.md#53-detached-recd-records-what-reading-them-costs-an-ordinary-decode-687) |
| The UTxO change scan | ledger, `ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/Utxo.scala` | 1 (1) | A loop with a builder over the diff; as a fold of the pair (taken, newest) it took 2.3 times as long with 4 times the allocation | [Section 51](memory-and-snapshots.md#51-the-ledgers-loops-functional-against-imperative-650) |
| The MemPack cursors | ledger, `ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/mempack/MemPack.scala` | 2 (2) | The packer's write position and the unpacker's read position, in the form the protocol reader's cursor is measured in; this path itself is not measured | Exemption: the measurement is [#674](https://github.com/KD5UJC-XYZ/scarab/issues/674), on the technique of [section 45](peers-and-network.md#45-the-cbor-walk-of-the-mini-protocol-reader-functional-against-imperative-657) |
| The sorted-state cursor | ledger, `ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/state/Shared.scala` | 1 (1) | The epoch boundary's walks are built on it, where they are 41 percent of the tick; a functional cursor threads its position through every walk, which is a different path from the one measured | [Section 16](era-rules.md#16-the-epoch-boundary-of-the-era-indexed-rules-made-fast-131) for the walk; the cursor's own measurement is [#674](https://github.com/KD5UJC-XYZ/scarab/issues/674) |
| The mux receive buffer | network, `network/src/main/scala/xyz/kd5ujc/scarab/network/ReceiveBuffer.scala` | 4 (1) | One array that doubles, instead of `held ++ more` copying the buffer on every read: a 64 MiB message fell from 10.1 s to 0.88 s, and 32 MiB from 1.88 s to 0.42 s | [Section 23, mux receive buffer](peers-and-network.md#23-mux-receive-buffer-356) |
| The Scalus budget spenders | plutus, `plutus/src/main/scala/xyz/kd5ujc/scarab/plutus/ReferenceBudget.scala` | 3 (2) | Scalus's `BudgetSpender` is a mutable callback of the machine, as the reference's spender is a counter array; the restricting spender also signals an exhausted budget only by throwing, which the machine stops on | [Section 44](plutus-budget-arithmetic.md#44-the-references-budget-arithmetic-in-the-evaluator-485) for the arithmetic; the callback contract for the form |
| The evaluation-local refusal log | plutus, `plutus/src/main/scala/xyz/kd5ujc/scarab/plutus/ScalusEvaluator.scala` | 1 (1) | `BuiltinRuntime.f` returns a `CekValue`, not an `Either`, and the machine catches callback exceptions into a `BuiltinError`: there is no failure-value path, so the typed refusal is kept here and signalled by the documented exception | Exemption: a library contract, not a performance claim |
| The KES signing key's period | crypto, `crypto/src/main/scala/xyz/kd5ujc/scarab/crypto/kes/Sum6KesSigning.scala` | 1 (1) | The key evolves in place and zeroes the material of the periods it leaves, so that no copy of an earlier period's key survives: forward security, not speed | Exemption: erased in place, which AGENTS.md allows without a measurement |
| The models runtime writer | models, `models/src/main/scala/xyz/kd5ujc/scarab/models/runtime/Writer.scala` | 2 (1) | The buffer and its fill count that every encoder of the models runtime writes through; a functional writer would thread the output through every codec. Not measured yet | Exemption: the measurement is [#664](https://github.com/KD5UJC-XYZ/scarab/issues/664) |

Nine clusters cite a section that measures their own path. The other six rest on a measurement of the
same technique elsewhere, an open measurement issue, or a contract that is not a performance claim —
and each says which, rather than borrowing a section it does not belong to. The three open issues
(#662, #674, #664) exist for exactly the sites whose numbers are still missing; when one lands, its row
moves from an exemption to a section here, and the code comment at the site gains the section number.

## Limitations

- **The numbers are the sections', not this page's.** They were taken on the builds and hosts those
  sections name, some of them months before this index; a row says where the evidence is, not that it
  still holds on today's tree. Re-measuring is a benchmark change, not a documentation one.
- **A count is not a census of cost.** A cluster with eighteen declarations is not eighteen times the
  code of one with a single declaration: the CBOR and document clusters are stack machines and walks
  whose declarations are the state of one loop each.
- **The six non-section rows are live gaps.** Nothing in CI checks that a suppressed site still has a
  measurement, so a new `var` with a plausible comment would pass review and this page would only
  notice if someone updated it. The rows are the record that the gap is known, not a guard.
- **The index is per cluster, not per line.** A reader checking one declaration should read the
  suppression at its site, which names the section that covers it.
