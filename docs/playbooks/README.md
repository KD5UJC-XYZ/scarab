<!-- SPDX-License-Identifier: Apache-2.0 -->
# Playbooks

**Status: Current.** Short recipes for recurring tasks. They set no policy: the rules are in
[AGENTS.md](../../AGENTS.md) and [CONTRIBUTING.md](../../CONTRIBUTING.md), including the change
level and gates of each task, and a recipe that disagrees with them is wrong. Builds run only in the
JDK image, as [CONTRIBUTING](../../CONTRIBUTING.md#build-docker-only) shows; the recipes write
`scripts/sbtw ...` for a command run that way.

| Task | Playbook |
|---|---|
| Add a ledger predicate with its reference citation | [add-a-ledger-predicate.md](add-a-ledger-predicate.md) |
| Confirm a decoding rule with the reference decoders | [confirm-with-the-decoding-oracle.md](confirm-with-the-decoding-oracle.md) |
| Add a fuzz target | [add-a-fuzz-target.md](add-a-fuzz-target.md) |
| Add a devnet scenario | [add-a-devnet-scenario.md](add-a-devnet-scenario.md) |

A new persisted-format version follows [storage-migrations.md](../storage-migrations.md), whose
worked example is the snapshot V5 to V7 change.
