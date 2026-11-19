### Fixed (peer selection)

- Of the addresses a drawn ledger relay domain resolves to, the node keeps one, drawn at random, as the reference's
  `pickDomainAddrs` does: a ledger or big-ledger request for n peers yields at most n addresses, however many records
  a domain's DNS answer holds. The drawn relays of one domain share one draw, the domains keyed as the ledger spells
  them; an SRV domain gives one address of the target its lookup chose, every SRV lookup of a request choosing its
  target alike; and IP relays are taken as they are. A ledger SRV domain is always looked up under `_cardano._tcp.`,
  as the reference looks it up, even one that already starts with it. Topology roots still keep every address of
  their answers, as the reference's do.
