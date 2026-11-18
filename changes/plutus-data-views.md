### Fixed (plutus)

- The own evaluator converts between `Data` and lists in constant time and memory, as the constant costs of
  `unListData`, `unMapData`, `unConstrData`, `listData`, `mapData` and `constrData` say, and as `case` on a `Data`
  constructor (from protocol 12) does in the reference: a list of `Data`, or of pairs of `Data`, keeps the values of
  the `Data` node it came from and shares them, as the reference shares the node's list. Each conversion copied and
  checked the whole list before, so a script that repeated one on a large `Data` value could take minutes, or exhaust
  the heap and get no verdict, where the reference judges it in milliseconds. Verdicts, budgets and errors are
  unchanged.
