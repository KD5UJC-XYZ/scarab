### Added (era types, #85)

- Shelley's certificate rules (DELEGS, DELPL and DELEG) run on the era types for Babbage and Alonzo, with POOL's
  instances for both: registrations, delegations, pools, genesis delegations and the moves of instantaneous rewards,
  each checked and recorded as the reference does, the pointer of each registration narrowed as the reference's is,
  and any number of certificates judged in constant stack. On every Babbage and Alonzo state of the era-walking
  captures with the certificates each state makes, on the refusal corpora's Babbage and Alonzo cases with certificates
  and on crafted cases, they give the current rules' failures and states, but at three crafted boundaries no chain
  reaches, where they follow the reference: a MIR certificate in the last epoch before slot 2^64, which they refuse; a
  genesis delegation whose due slot passes 2^64 - 1, which they record wrapped; and MIR below protocol version 5,
  where a pot pays from itself alone. Of the twelve recorded cases, the nine refused by these rules alone give the
  reference's refusals byte for byte; the other three, which UTXOW or UTXO refuses too, are compared with the current
  rules. The node does not run them yet, so neither behaviour nor the persisted format changes.
