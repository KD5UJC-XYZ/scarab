### Changed (plutus evaluator allocation, #1411)

- The own UPLC evaluator allocates far less per step, with every result, log, budget and failure as before. A
  saturated builtin application reads its arguments as the machine holds them, with no argument vectors, `Either`,
  closure or `Option` made for it; a builtin whose measures are each one cost has its cost computed and spent as
  two longs, while a measure the reference streams (a text, a list, a `Data` value) is still spent element by
  element; a saturated application continues the machine's loop with its result; and an operand that is a variable,
  a constant, a `lam`, a `delay` or a builtin is computed without a frame for it, its step counted and charged as
  before.
- The machine is checked against the machine before the change, which runs with the reading, denotations and costing
  of then, on generated builtin applications and terms, with every builtin, the default cost models of variants B, C,
  E and G and cost models of every shape (`CekApplicationSuite`). A builtin whose unlifting, costing or denotation
  reads its arguments only as a vector is applied as before, the costing and the denotation reading what the
  unlifting made.
