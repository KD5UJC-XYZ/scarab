### Fixed

- Alonzo and Babbage reject excessive collateral inputs even without redeemers and enforce the
  bootstrap-output attribute limit and network under either validity flag, including Babbage
  collateral returns. Output predicate order and failure payloads follow the pinned reference;
  collateral counting, fees and failed-phase-2 stake removal in Alonzo through Conway use
  decoded set semantics, while original signed bytes and stored formats are unchanged (#178).
