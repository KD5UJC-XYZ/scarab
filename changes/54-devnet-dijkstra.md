### Added (reference Dijkstra devnet, #54)

- `devnet.py up-dijkstra` starts a reference-only cluster of the Leios testnet image (`rc-2026w44`, run by its pinned digest). The cluster begins in Conway, forks into Dijkstra at epoch 1 and includes one Dijkstra transaction. `devnet.py capture-dijkstra` saves its chain from Origin, together with the transaction and the genesis files, as `fixtures/dijkstra-devnet`. No Scala node takes part, because Scarab cannot follow a Dijkstra chain yet.
