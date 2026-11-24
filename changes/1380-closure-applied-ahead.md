### Changed (Leios forging, #1380)

- A certifying block's certified closure is applied off the forging path. The forger applies it as soon as the tip announces an endorser block whose closure the store holds, and takes the result when it certifies; before, it decoded and applied the closure inside the slot it led. The reference also computes this state off the forging path, in its mempool's certifying view.
