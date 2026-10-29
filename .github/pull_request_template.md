## What and why

<!-- Reference roadmap IDs (e.g. B2, F1) and issues. -->

## Level and gates (see CONTRIBUTING.md, "Gates and ceremony by level of change")

- Level: 0 docs / 1 small code / 2 behaviour in one area / 3 consensus-critical / 4 stage boundary
- [ ] `scripts/sbtw check` passes (level 1 and above)
- [ ] Level 0: relative links in the changed documents resolve (nothing else is required)
- [ ] One scenario per area touched (level 2 and above), with its result:
- [ ] Persisted format: version bump, migration, a test that reads the previous version (ADR 0006)

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
- [ ] Domain errors are closed ADTs through `Raise`; hashed bytes are the original bytes
