### Fixed (consensus, #826)

- Under `--reference-build leios-b004fe0c`, the era history's bounds are the Leios build's: the bound
  where an era entered after the chain's start begins keeps the next Peras round of the era that ends
  there (`mkUpperBound`, with its `Word64` arithmetic), a chain that starts in Byron starts from
  `initBound` in the build's layout with the build's Peras state (no epoch context, no certificate),
  and the node-to-client era history (`GetInterpreter`, `GetEraStart`) writes bounds of four items,
  Dijkstra's Peras round length of 90 slots and the rounds a bound closing Dijkstra adds. These
  answers are not reachable through `follow` yet: `follow --reference-build leios-b004fe0c` still
  refuses the build (its rules are not implemented, `ReferenceBuildNotYet.Rules`), and the node's
  maximum major protocol version of 11 keeps the transition to Dijkstra unknown (#54), so no answer
  has a Dijkstra era; the tests raise it to 12. The 11.1 build's states and `GetInterpreter` answers
  do not change.
- `GetEraStart` of the era after the current one answers its start when the current era's transition
  is known, as the reference does (`SituatedNext`, `mkUpperBound` of the current era to the
  transition), instead of nothing, in every build: for example Shelley's start while a chain whose
  Shelley begins at a configured epoch is still in Byron.
