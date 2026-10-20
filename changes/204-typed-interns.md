### Changed (decode interning, #204)

- Keep each kind's decode table and entries typed, without unchecked recovery or runtime generic-role tests. Preserve first-instance identity, collision/growth behavior and decode isolation; weak associations retain no completed decode or decoded values.
- Verify current and legacy-derived V5 snapshot decoding, allocation and retained memory against the old implementation; representative timing remains within the 10% limit.
