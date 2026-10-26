### Changed (certificates, governance and PPUP, #412)

- DELEG, POOL and GOVCERT of Conway, the DELEG of the earlier eras with the MIR certificates, GOV and
  PPUP record every failing check and go on to the state the reference makes, so a later
  certificate of the same transaction sees a registration whatever failed in it. GOV processes only
  the votes of the voters that exist and reports one unknown action per vote, as the reference does;
  PPUP reports only the first proposal whose protocol version cannot follow.
- No rule of a transaction raises a refusal any longer, so the judgment's transitional sub-rule
  boundary is removed.
