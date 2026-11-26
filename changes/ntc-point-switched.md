### Fixed (devnet `ntc` same-point rounds)

- A same-point round whose point, the Scala node's tip, the reference refuses as `PointNotOnChain` is asked
  again at the Scala node's tip then, at most four rounds, one second apart, as the `e2e` era walk tries a
  request. Such a tip is a block the reference had already rolled back, one the Scala node had not followed off
  yet when its tip was read. It no longer fails the scenario as `round:notAcquired` when a later tip is held.
  Each refusal is printed (`roundPointSwitched`) and counted in the summary (`queries.pointsSwitched`). A round
  whose four points are all refused still counts as `round:notAcquired`, as does any other refusal or error
  before its first query.
