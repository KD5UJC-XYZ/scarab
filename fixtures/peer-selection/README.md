# Peer-selection reference observations

[Native policy decisions](policies/README.md) retain complete selected sets and optional
hot-demotion scores for 912 explicit pinned-native input cases. They compare the production
ranking functions with identical Word32 inputs, without claiming identical PRNG streams,
thread schedules or exhaustive governor-state coverage.

[Native churn decisions](churn/README.md) retain all startup and first-cycle outputs from
1,152 explicit pinned-native IOSim cases, including raw invalid proposals, signed achieved
changes, timeout outcomes and exact native wait arithmetic. Genesis and Praos, both ledger
judgements and changing startup inputs are included; no captured output is excluded.
