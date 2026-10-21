### Changed (build version identity, #161)
- Derive release artifact versions from an explicitly selected and verified SemVer tag; refuse mismatched source, channel, tag identity or dirty release inputs. Ordinary Docker builds now use an unreleased commit-bearing version instead of the fixed `0.1.0`.
- Freeze version and source identity before immutable export, verify it in both clean builds, and cross-check artifact names, packaged metadata and compiled BuildInfo. Release retries retain the approved source cut after branch advancement; no tags or releases are published by the verifier.
