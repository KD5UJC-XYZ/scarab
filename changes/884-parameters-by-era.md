### Fixed (protocol parameters, #884)
- Protocol-parameter updates read key 17 in their era: per UTxO word in Alonzo, per byte in
  Babbage. The conversion is performed only when the ledger enters Babbage. Overlay accounting
  and both TPraos header views use Alonzo's decentralisation parameter even at version 7;
  Babbage has no overlay even at version 6. This fixes epoch-triggered chains whose era and
  protocol version differ. Mainnet, preprod, preview and the Leios testnet are unaffected.
