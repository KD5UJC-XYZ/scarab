### Fixed (generated CDDL decoders)

- Generated decoders now enforce the entry bounds of `{ + k => v }` maps: an empty Conway or Dijkstra `withdrawals`, `mint`, `voting_procedures`, `redeemers` or inner asset map, and Dijkstra's empty `required_top_level_guards`, `direct_deposits` and account balance interval maps, are refused as the reference ledger decoders refuse them. Mary, Alonzo and Babbage keep accepting an empty inner asset map, as the reference does below decoder version 9 (new `lenient-maps` directive in `models.conf`).
