# Correspondence map

**Status: Current.** The map of Scarab's mirrored definitions to their reference counterparts, and the guard that
keeps it complete and current. The rule it enforces is [REF-6](../design-rules.md#ref-6), with the classes of
[REF-4](../design-rules.md#ref-4) and [REF-7](../design-rules.md#ref-7) and the reasons in
[ADR 0001](../adr/0001-haskell-node-as-reference.md). This note says how the map and its guard work.

## The files

- `docs/data/correspondence/<area>.json`, one per area. Each has:
  - `roots`, the source directories of the area;
  - `entries`, each mapped definition and its counterpart;
  - `glue`, the Scarab-only definitions, in groups that share a reason.
- `docs/data/correspondence-baseline.json`: the definitions not mapped yet. It only shrinks.
- `scripts/check_correspondence.py`: the guard, registered in `scripts/guards.json`. Its docstring lists what it
  refuses.
- `scripts/scala_definitions.py`: the reader that finds the definitions.

| Area | Roots | Reference |
|---|---|---|
| `network` | `protocol/src/main/scala`, `network/src/main/scala` | ouroboros-network; ouroboros-consensus for the block and transaction types the protocols carry |
| `consensus` | `consensus/src/main/scala` | ouroboros-consensus; cardano-ledger's protocol packages |
| `ledger` | `ledger/src/main/scala` | cardano-ledger |
| `plutus` | `plutus/src/main/scala`, `uplc/src/main/scala` | plutus; cardano-ledger for script contexts and cost models |
| `storage` | `storage/src/main/scala` | ouroboros-consensus's storage layer; cardano-ledger's state encoders |

The areas are chosen by module source root, not by package. `tools` reuses these packages, and `plutus` holds a
`scalus.uplc` file. The `node` module is outside the five areas, although its mempool, forging, node-to-client
and governor code mirrors consensus and network code. Whether it joins them is the owner's decision.

## What a definition is

A definition is one of these, at the top level of a file or as a member of a class, trait, object or enum body:
- a `def`, `val`, `var` or `given`;
- a `class`, `trait`, `object` or `enum`;
- a `type`, opaque or not.

Methods in an `extension` block count as members of what holds the block.

Some code is part of the definition around it instead:
- a member that is `private` (or `private[this]`) in its owner;
- anything defined inside a `def`, `val`, `var`, `given` or `type`.

An enum's cases are part of the enum. Overloads share one key.

A key is the keyword and the qualified name, without `xyz.kd5ujc.scarab.`:
- `.` follows an object or a package;
- `#` follows a class, trait or enum.

For example `def protocol.KeepAlive.transition`, `def network.selection.Churn.Step#reached` and
`object protocol.KeepAlive`. An anonymous given is named from its type, as the compiler does (`given_Show_Coin`).
`--show KEY` prints a definition's file, lines and digest.

### Why a lexer

The reader works on the code that `scala_comments.split` gives: comments and the text of literals are blanked, and
offsets and line breaks are kept. It finds headers by their keyword and extents by indentation and brackets. It
needs no build, so the guard runs in CI's scripts job, which compiles nothing.

SemanticDB is on in the build, but its output exists only after compiling, and reading it needs a protobuf
decoder. The reader fails closed instead, and the guard refuses these lines as unread:
- a line under a root that no definition holds and that is not a `package a.b`, `import`, `export` or `end`
  line;
- a clause line with a `;`, or a `package` with a body;
- a definition written after a `{` on a line of a class, object, trait, enum or extension body
  (`object A { def f = 1 }`);
- anywhere, a definition after a `;` that is outside the brackets its line opens (`val a = 1; def f = 2`,
  `}; def g = 2`), a method's body included: telling there whether it is a member would need the scope of every
  brace. Inside brackets the line opens, as in `def f = { val a = 1; a }`, it is the method's own code.

So a definition is written on a line of its own, where the reader keys it.

At bc0e4995a the reader found 7,609 definitions and no unread line. A looser scan for definition keywords at the
start of a statement found nothing the reader had missed.

### Granularity

The map works at the member level, so a decision function, a data type and a state machine are each one entry.

Measured over the 20 most recent first-parent commits of `main` at bc0e4995a that change a Scala file under the
roots. For each commit, the keys of its changed files (both paths of a rename, so a file moved out of the roots
counts) are compared between the commit and its first parent:
- at the member level, 240 keys were new, 54 removed and 136 changed;
- at the level of whole top-level definitions, 186 were new, removed or changed;
- the four commits whose changes under the roots were comments only changed no digest at either level.

The coarser level would mean fewer entries to update, but one entry for a whole object can name no counterpart
finer than a module. A private helper is part of its owner, which keeps the count down without hiding code:
a change to the helper changes its owner's digest.

## An entry

```json
"def protocol.KeepAlive.transition": {
  "upstream": [{"repository": "IntersectMBO/ouroboros-network",
                "revision": "c45735a56c567fa977969173d18943bac6bb3821",
                "file": "ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/KeepAlive/Type.hs",
                "symbol": "Message", "lines": [85, 106]}],
  "class": "Mirror",
  "structure": "same",
  "note": "the reference's transitions and agency are in the message and StateAgency types; ...",
  "digest": "<16 hex>"
}
```

An entry may also have `evidence` (suite names) and `record` (a link).

- `upstream`: one or more counterparts. Each names a revision pinned in `docs/data/reference-pins.json`, and
  `lines` cover the counterpart at that revision.
- `class`: Mirror, Improvement, Deviation or Gap ([REF-4](../design-rules.md#ref-4)). An Improvement or a Deviation
  links its record. A Gap links its issue.
- `structure`: `same` when the definition has its counterpart's shape:
  - a data type has the same constructors and fields;
  - a state machine has the same states and transitions;
  - a function makes the same decisions in the same order.

  It is `differs` when the definition merges or splits counterparts, or uses another algorithm or representation.
  A `differs` entry names in `evidence` the suites that compare that behaviour with the reference: an exported
  reference corpus, an oracle, or a comparison with a running reference node. A suite that only restates what
  the source says is not such evidence. Without it the entry is a Gap, which links its issue instead.
- `digest`: the definition's code digest when the entry was last checked.

**Glue** is a group with a `reason` and its `definitions`. It holds the code that has no counterpart: Scala
plumbing, local policy, adapters. It is not a hiding place for code that mirrors.

### The digest

The digest covers a definition's own lines. These are its extent, without the extents of the keyed definitions it
holds, plus the header of the `extension` block it is in. Comments and empty lines are dropped, and outside
literals each run of white space after the indentation becomes one space, with none at a line's end. Indentation
under the header is kept, because Scala 3's indentation carries meaning, and the text of every literal is kept as
written. An `extension` block with only private methods belongs to its owner.

So:
- a comment edit, or a change of alignment, changes no digest;
- a change to a definition's code, its annotations or a private helper changes its own digest;
- a change to a member with its own key changes that member's digest only.

It is not insensitive to everything that leaves the meaning alone:
- a line break that scalafmt moves changes the digest;
- so does a comment between two tokens written with no space around it (`1/* c */+2` against `1+2`);
- so does a comment inside a string interpolation's `${...}`;
- so does a blank line between an annotation and its definition, which moves the annotation to the owner;
- an anonymous given's name is made from its first line only.

## The baseline

The baseline lists, per area, every definition that is neither mapped nor glue, by key. A count per file would
let a change remove one unmapped definition and add another in the same file, so the list holds keys, not counts.

Each key holds either `null` or a hint. A hint is the counterpart the definition's own doc comment cites, checked
at the pin like an entry's `upstream`. It was seeded only where all of these held:
- the comment names a registered revision or release in the same comment;
- the cited file resolves at that revision;
- a symbol the comment names, or the declaration the cited line starts, is declared in the cited lines;
- that symbol shares the definition's name: one contains the other, ignoring case, or the owner's name where the
  definition's own is a verb such as `decode`.

A hint is where to start the review. It can point at a related declaration rather than the counterpart, such as
the universe tag of a type rather than the type itself. It does not say that the definition mirrors it. That claim
needs a reviewed entry.

The baseline shrinks:
- a key that is mapped or glue now fails the check until `--write-baseline` drops it;
- `--write-baseline` never adds a key, and writes only when the rest of the check passes;
- a key or hint that the base's baseline did not have is refused.

The base is the target branch on a pull request in CI: the merge commit's first parent, which the scripts job
checks out two commits deep for this. Without it the check fails. Elsewhere the base is the merge base with
`origin/main`, and without that history the check reports that it did not run. A base with no baseline admits
the first one.

## Upstream checks

The guard always checks that an upstream counterpart names a pinned revision and is well formed. Where clones are
given (`--sources DIR` or `SCARAB_REFERENCE_SOURCES`), it reads each file at the revision with `git show`, never
from a working tree. It then checks that the file exists, that `lines` lie within it, and that `symbol` occurs
in those lines. The directory holds one clone per repository, named as the repository is (`ouroboros-network`,
`cardano-ledger`, `plutus`, ...). Given clones must hold every repository and revision the map names, or the check
fails. Without clones, as in CI, the guard prints that the check did not run, and passes.

## How to update the map

- **A new definition** under a root needs one of these in the same change:
  - an entry: find its counterpart at the area's pin and fill in `upstream`, `class`, `structure` and `digest`
    (`--show` prints the digest);
  - a glue line with its reason.

  Run the guard with `--sources` before you push.
- **A changed definition with an entry**: the guard names it and prints the new digest. Compare the definition
  with its counterpart again and correct the entry's class, structure and lines. Then record the digest. Copying
  the digest without the comparison defeats the check.
- **Mapping a baselined definition**: move it from the baseline into an entry, using its hint if it has one, and
  run `--write-baseline`. `--unmapped AREA` lists the baselined definitions by file.
- **A real difference found while mapping**: a Gap entry with its issue, or an Improvement or Deviation with its
  record, as [REF-7](../design-rules.md#ref-7) says. It is fixed in a change of its own.

## What the guard does not check

- Whether a class or a `same` structure is true. A reviewer checks that against the pinned source. `same` covers
  the whole definition: a definition that also does what its counterpart does not, or only part of it, is
  `differs`.
- Changes to a baselined or glue definition: neither has a digest.
- Whether an evidence suite compares with the reference. The guard checks only that the suite exists.
- Whether a glue reason is honest.
- Code outside the roots, including `node`.
- A persisted format: storage entries are data only, and the storage code is under [FMT-1](../design-rules.md#fmt-1).
