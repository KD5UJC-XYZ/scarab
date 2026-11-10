### Fixed (protocol parameters, #884)
- Protocol-parameter updates read key 17 in their era: per UTxO word in Alonzo, per byte in
  Babbage. The conversion is performed only when the ledger enters Babbage. Overlay accounting
  and both TPraos header views use Alonzo's decentralisation parameter even at version 7;
  Babbage has no overlay even at version 6. This fixes epoch-triggered chains whose era and
  protocol version differ.
- Resolve the separately recorded Alonzo-to-Babbage proposal-translation difference: both
  stored proposal maps drop the removed fields and convert key 17 from cost per word to cost per
  byte before UPEC reads them. A late proposal could otherwise predict the wrong cost even at
  the ordinary version-6-to-7 transition. The owner approved including this reference bug fix in
  #884 on 2026-11-10; the snapshot schema is unchanged. No occurrence on an actual public chain
  is established by the regression.
