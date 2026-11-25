### Added

- A pinned Nix development shell with JDK 25, sbt and Nix formatting, organized
  with flake-parts. Run the existing Scala build directly inside `nix develop`.

### Fixed

- Mithril subprocess tests resolve utilities through PATH so their lifecycle
  and output-limit checks also work in Nix environments.
