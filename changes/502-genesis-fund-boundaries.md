### Fixed (#502)

- Guard initial-fund injection before compacting outputs, retain signed reserve arithmetic, and require canonical typed addresses for pseudo-input hashing. Parse legacy and extra sources independently, including unused Byron inputs, and resolve them at the reference transition boundaries.
