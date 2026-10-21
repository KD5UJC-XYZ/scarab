### Added (CI)

- `fuzz-gate.yml` runs the extended decoder fuzz gate (`fuzz 100000 1 4`) on pull requests that change a decoder, the fuzz project, the CDDL schemas, the fixtures or the build; the job always reports and skips the fuzz steps for other changes. The nightly `fuzz.yml` keeps its rotating seeds.
