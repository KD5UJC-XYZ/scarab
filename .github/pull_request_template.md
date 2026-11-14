## What and why

<!-- Reference roadmap IDs (e.g. B2, F1) and issues. -->

## Level and gates (see CONTRIBUTING.md, "Gates and ceremony by level of change")

- Level: 0 docs / 1 small code / 2 behaviour in one area / 3 consensus-critical / 4 stage boundary
- [ ] `scripts/sbtw check` passes (level 1 and above)
- [ ] Level 0: relative links in the changed documents resolve; a design document or ADR also has its reference claims checked against the pinned source (CONTRIBUTING.md, "Levels")
- [ ] One scenario per area touched (level 2 and above), with its result:
- [ ] Persisted format: version bump, migration, a test that reads the previous version (ADR 0006)

## Deviations from the reference

<!-- Level 1 and above: `None`, or one row per difference from the reference that is not a Mirror, with its
class and record in the approval cell (docs/design-rules.md, REF-7): `Improvement (e): [#474](link)` or
`Deviation: [decision](link)`. A Gap goes under Gaps instead. Scarab references the Haskell cardano-node in every
respect for production behaviour, so a deviation is the owner's decision, never a silent choice (ADR 0001;
docs/decisions.md). The PR policy check reads this section.
| Behaviour | Reference (pinned) | Scarab | Approval |
|---|---|---|---|
| what differs | repository revision `File.hs:line` | what Scarab does | Deviation: [decision](link) |
-->

## Gaps

<!-- Level 1 and above: `None`, or one bullet per remaining gap, each starting with its issue:
- #123: what remains -->

## Evidence and docs

- [ ] New behaviour has tests or properties; bugs found on real data have a regression fixture
- [ ] Changelog fragment in `changes/` (if a user could notice)
- [ ] Level 3: compatibility register entry and its page updated (docs/data/compatibility.json, docs/compatibility/<id>.md), including "explicitly unchecked", and docs/compatibility.md regenerated with `python3 scripts/render_compatibility.py`; which component: 
- [ ] Level 3: the design document's "as built" section updated, if the change belongs to a design
- [ ] Level 4: evidence-log entry in docs/compatibility/evidence-log-N.md with commit, date and command
- [ ] A new roadmap item, or a new issue on one, in docs/data/roadmap.json with `render_roadmap.py --refresh-states` run; IDs not renumbered (status follows the issues)
- [ ] Findings added if something was learned against real data (docs/findings.md)
- [ ] Relative links in changed docs resolve

## Hygiene

- [ ] No keys, secrets or run data committed; fixtures have provenance and licence recorded (NOTICE/README)
- [ ] The code follows the rules of `docs/design-rules.md` for the groups it touches (for example closed error types through `Raise`, ERR-1; hashed and signed bytes are the bytes the reference hashes and signs, BYT-1), and each kept exception is marked as its rule says

<!-- End with `Closes #N`, or `Related to #N; this PR leaves the issue open: <reason>`.
GitHub treats a closing keyword followed by an issue reference as a closing link even when the sentence negates it. -->
