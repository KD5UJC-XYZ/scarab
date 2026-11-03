### Fixed (Byron software update registration, #748)

- A Byron software update proposal is registered with the reference's checks, in the reference's
  order:
  - each system tag of its installer metadata, which must have at most 10 characters and be ASCII;
  - no registered proposal of the same application;
  - the application name, at most 12 characters and ASCII;
  - the version's succession.
- Characters are counted in code points. Such a proposal, and the block that carries it, used to be
  accepted where the reference refuses it.
