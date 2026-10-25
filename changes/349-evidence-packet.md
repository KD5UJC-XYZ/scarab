### Added (security review, #349)

- `scripts/evidence_packet.py` generates the reviewer evidence packet for a tag or commit: source and module map, gate commands with image pins, reference revisions, fixture/coverage inventories, optional-data requirements and, when supplied, the validated release-candidate SBOMs. Two runs on one revision give byte-identical archives, and a member that records a local path or the host name fails the run.
