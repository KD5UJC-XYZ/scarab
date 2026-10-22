### Fixed

- Preserve the selected chain already validated during restart while retaining native startup
  candidate ranking and LoE bounds; avoid temporary rollback and duplicate ledger replay.
- Serialize BlockFetch observations, reservations and GSM transitions, and suppress unchanged
  decision broadcasts. Wake Genesis header forecasts through versioned chain publications.
- Sample each header's arrival separately, share era-history parameter and transition logic,
  and repair or discard inconsistent received-cache entries without losing their typed diagnostic causes.
- Refuse snapshot imports over an existing received journal. Validate every selected-journal
  metadata field before publishing a resumed block, retaining valid-prefix recovery.
