### Added (era types, #85)

- Shelley's certificate rules (DELEGS, DELPL and DELEG) run on the era types for Babbage, with POOL's Babbage
  instances: registrations, delegations, pools, genesis delegations and the moves of instantaneous rewards, each
  checked and recorded as the reference does, the pointer of each registration narrowed as the reference's is, and
  any number of certificates judged in constant stack. On every Babbage state of the era-walking captures with the
  certificates each state makes, on the refusal corpora's Babbage cases with certificates and on crafted cases,
  they give the current rules' failures and states, and the reference's refusals byte for byte. The node does not
  run them yet, so neither behaviour nor the persisted format changes.
