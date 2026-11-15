### Fixed (reward set equality, #1057)

- Two reward sets that differ only in a reward's amount are no longer equal. `Eq[RewardSet]` compares the
  sizes and then the members in ascending order, each with all three fields, as the reference's
  `Eq (Set Reward)` does; it had used the sorted set's own equality, which finds members by the reward
  ordering and so ignored the amount. The `Eq` and `Diff` of the pending reward update, and of the states
  that hold it, now see a difference in a reward amount and name its account. No ledger rule compared reward
  sets, so no ledger verdict or computed state changes.
