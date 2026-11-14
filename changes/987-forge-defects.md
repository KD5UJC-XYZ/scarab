### Changed (forging, #987)

- The forger's two checks of the block it has just built (a body over the maximum size, a block that does not decode) are now forge defects, apart from the ordinary `forgeFailed` refusals. Such a slot ends without a block, the block's transactions leave the mempool, and the node goes on. The defect is logged as `forgeDefect` (Critical), counted in `defects` (`forgeEpoch`, `GET /forging`) and in `scarab_forge_defects_total`, and the `PoolForgeFailed` alert fires on it.
