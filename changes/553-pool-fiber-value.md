### Fixed (node, #553)

- A block or transaction refused by rules run on the CPU pool (`--cpu-threads`) no longer prints a bare `cats.mtl.Handle$Submarine` line now and then. The pool's fiber now ends with its failure as a value, and the caller raises it again; before, a fiber that failed before its caller joined it was also reported by the runtime as a failure nobody had observed. The node's decisions were not affected.
