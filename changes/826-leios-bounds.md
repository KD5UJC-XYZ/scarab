### Fixed (consensus, #826)

- Under `--reference-build leios-b004fe0c`, the era history's bounds are the Leios build's: the bound
  where an era entered after the chain's start begins keeps the next Peras round of the era that ends
  there (`mkUpperBound`), a chain that starts in Byron starts from `initBound` in the build's layout
  with the build's Peras state (no epoch context, no certificate), and the node-to-client era history
  (`GetInterpreter`, `GetEraStart`) writes bounds of four items, Dijkstra's Peras round length of 90
  slots and the rounds a bound closing Dijkstra adds. The 11.1 build's states and answers do not
  change.
