### Changed (the Leios build, #826)

- **Storage format (V10, in place):** a Dijkstra ledger state's governance state is now held as the era's
  own record and read and written as the Leios build writes it: its parameters and the parameter updates of
  its proposals in Dijkstra's encoding, so a proposal that updates a parameter Dijkstra adds (update keys 34
  to 56) is stored. V10 refused such a state before. Every state the reference wrote, and every Conway
  state, keeps its bytes, and nothing migrates.
- A Dijkstra block whose transactions propose such an update is applied, where the node withheld it before
  as no verdict. The public Leios testnet's window of epochs 69 to 74, with its change of the endorser-block
  limits proposed, ratified and enacted, now replays to the reference's states.
