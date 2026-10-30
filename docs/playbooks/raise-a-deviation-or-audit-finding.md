<!-- SPDX-License-Identifier: Apache-2.0 -->
# Raise a deviation question or an audit finding

**Status: Current.** The rule is [ADR 0001](../adr/0001-haskell-node-as-reference.md#amendment-2026-10-25-no-deviation-without-prior-owner-approval):
behaviour is mirrored exactly, and a deviation needs the owner's explicit approval before it is
implemented. This is the order of work when a difference from the reference is found.

1. Pin the difference: the Scarab symbol, and the upstream file and line at the registered revision
   ([reference-pins.json](../data/reference-pins.json)). Mark each claim as confirmed from source, from
   a reproduction, or still to verify.
2. File it. A finding from an audit or review uses the
   [audit form](../../.github/ISSUE_TEMPLATE/audit.yml); a difference found while working uses the bug or
   feature form, or a comment on the audit issue that collects the area's rows (such as
   [#474](https://github.com/KD5UJC-XYZ/scarab/issues/474)). An exploitable problem goes to
   [SECURITY.md](../../SECURITY.md) instead.
3. The default is to align with the reference; that needs no question. If exact fidelity seems
   impossible, or a deviation seems worth asking for, stop and ask the owner with labelled options: the
   faithful change, and each deviation with its trade-offs and reasoning (what the reference does, what
   Scarab would do, who can observe the difference, the cost of each).
4. Until the owner answers, implement nothing that deviates. Earlier code, pull requests, review notes
   and design documents are not approval.
5. Once decided, record the decision where it shapes the design (an ADR amendment, or the networking
   ADR's approved-deviations section) and add its row to [decisions.md](../decisions.md) in the change
   that first relies on it. The wording is neutral: the decision, its date and issue, never a quotation.
