### Fixed (peer selection, #810)

- A new roots generation (a refresh of a local root's DNS name, even one that only answered the same
  addresses in another order, or a SIGHUP reload) no longer turns every big ledger and ledger peer into
  an ordinary peer. Before, the governor then saw no big ledger peer, so in Genesis mode the Honest
  Availability Assumption lapsed and the node fell back to PreSyncing for about a second, while the
  ordinary families forgot the cold ones and closed or deactivated the established ones as surplus. As
  in the reference, a reload now takes away only the local, public and bootstrap roots it no longer
  names; big ledger and ledger peers stay what they are unless the reload makes one a local root,
  and otherwise change only by their own requests and by forgetting.
