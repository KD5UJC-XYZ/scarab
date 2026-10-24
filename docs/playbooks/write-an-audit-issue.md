<!-- SPDX-License-Identifier: Apache-2.0 -->
# Write an audit-shaped issue

Every issue uses the shape of `.github/ISSUE_TEMPLATE/aud.yml`: boundary, finding, evidence, acceptance, removal condition. It is the default template. State lives in labels, never in the text.

| Field | Content |
|---|---|
| Boundary | The module, symbol or interface concerned (find it in [codemap.md](../codemap.md)), and the trusted assumptions |
| Finding | What is wrong or missing, one paragraph; mark each claim confirmed from source, from an earlier reproduction, or still to verify |
| Evidence | The source symbol and the pinned upstream file, commit, fixture, captured block or log excerpt; no key material |
| Acceptance | Checkable criteria, the change level and the gates that close it ([run the gates](run-the-gates.md)) |
| Removal condition | For a deferral, exclusion or accepted limitation: the condition that ends it |

1. Check the claim against the source before filing: open the symbol, read the upstream file at the pinned revision (`docs/data/reference-pins.json`), and run the test that would show it.
2. Title with the audit ID when it belongs to an audit round (`[AUD3-22] ...`; terms in [glossary.md](../glossary.md)); otherwise a plain imperative title.
3. Describe a missing property and what the reference does. Never describe how to trigger a problem on a public ticket; [SECURITY.md](../../SECURITY.md) says where that goes.
4. File it with labels (`audit`, one `priority:P0..P2`, one `area:*`) and the milestone:

```sh
gh issue create --title "[AUD3-22] Short imperative title" --label audit --label priority:P1 \
  --label area:verification --body-file finding.md
```

5. Add the roadmap line or extend the existing item ([process.md](../process.md#findings-from-an-independent-audit)); a finding that strengthens an existing item is a comment there, not a duplicate.
6. The fix keeps the reproduction as a regression test and updates the matching row of [compatibility.md](../compatibility.md); the closing comment states what was done, the commit and the evidence.

Repository settings (branch protection, reviews) are the maintainer's, never an agent's.
