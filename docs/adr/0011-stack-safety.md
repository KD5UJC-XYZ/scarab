# 0011. Recursion over data from outside is stack safe, in a form the compiler or the library guarantees

- **Status**: Accepted
- **Date**: 2026-10-16

## Context

A follower restarted from a snapshot stopped with `java.lang.StackOverflowError` on preprod transaction
c70243bb (block 2,216,025, 158 redeemers; `docs/findings.md`). `Phase2` ran the redeemers by a
recursion inside a for-comprehension over `Either`, which is not a tail call: about a dozen frames
per redeemer, 3 to 6 KiB of stack each. A thread stack is 1 MiB by default, a transaction can carry
well over a hundred redeemers, and the JVM exits on the error without an error event.

B17 and B18 had already met the same failure for the depth of nested items (CBOR, Plutus data, native
scripts) and made those walks iterative (`TreeFold`). This record states the rule for both the width
(a count of elements) and the depth of anything that comes from a block, a peer, a file or a query.

The owner's words: *"I prefer to use tail recursive algorithms when possible, even so far as to
define a stack machine for use with Monad.tailRecM. We don't necessarily need to apply that if it is
not applicable, but it is an example that I don't want us to shy away from strictly typed, stack safe
code."*

## Decision

A recursion whose depth depends on outside data takes one of these forms, in this order of preference:

1. **A fold, traversal or `foldLeftM`** from the library (`traverse`, `traverse_`, `foldLeftM`,
   `flatTraverse` are stack safe for `Either`, `Option` and `IO`; `WideTxStackSuite` shows it on
   100,000 elements).
2. **`@tailrec`**, so the compiler refuses a call that is not in tail position.
3. **`Monad[F].tailRecM` over a small typed state** (a case class or enum of what remains), when the
   loop is effectful or short-circuits through `Either`/`Option`/`F`: `Hfc.tick` (over the epoch state,
   short-circuiting through `Either`, for an unbounded number of epochs) and `Migrate` (`steps`, over a `FormatVersion`). `Phase2` now
   uses `foldLeftM` (form 1).
4. **An explicit stack machine** (typed frames, a loop over them) for a tree walk where the recursion
   is not a tail call: `TreeFold`.

Not allowed on outside data: a recursion inside a for-comprehension or `flatMap` of `Either`/`Option`,
`foldRight` on a strict collection, and mutual recursion without one of the above.

Allowed without the above, each with a stated bound in the audit (`docs/findings.md`): a recursion
whose depth is a constant of the format (`Sum6Kes`, the 24 levels of a mini-protocol term), or
the depth of our own schema (a state-dump JSON). The effect loops of `IO` (`flatMap` in tail
position, `F.cede *> go(...)`) are stack safe because `IO` trampolines; they are written for an `F`
with a stack-safe `flatMap` and must not be run in `Id`.

Every such path has a test on a thread with a 256 KiB stack (`PreprodDeepTx.onSmallStack`) and, where
a real transaction caused it, the real transaction.

## Consequences

- A new recursion over outside data is reviewed against the list above; `@tailrec` is the minimum.
- The stack of the test thread is far below the default, so a regression shows in the suites, not
  after hours of sync.
- We do not rely on the JIT compiler or on a warmed-up JVM for the stack depth: a measurement of the
  old `Phase2` showed the same or a larger need warmed up than cold.
