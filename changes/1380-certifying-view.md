### Changed (Leios forging, #1380)

- The node keeps the pool's certifying view, as the reference does: once the tip announces an endorser block whose closure the store holds, the pool is revalidated off the forging path on the tip's state with that closure applied, and kept up to date as transactions are added or removed. A certifying block's endorser block takes its transactions from the view when the view holds for the slot. Before, the forger revalidated the whole pool on the closure inside the slot it led.
