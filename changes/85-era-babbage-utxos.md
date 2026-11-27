### Added (era types, #85)

- Babbage has PPUP and UTXOS on the era types, and its certificate-state instance:
  - PPUP checks an update proposal's proposers, protocol versions and epoch as the reference does, and records the
    proposals with the bytes the node stores them by;
  - UTXOS runs PPUP before a valid transaction's scripts, and Babbage's change of the UTxO state follows on Babbage's
    classes.

  On the recorded Babbage submissions, the refusal corpora's cases with their states, the era-walking capture's
  proposals and crafted cases, they give the flat rules' verdicts, failures and states, and the reference's verdicts.
  The one exception is a minor version that wraps at `Word32`, where they follow the reference and the flat check does
  not (#1514). The node does not run them yet, so neither behaviour nor the persisted format changes.
