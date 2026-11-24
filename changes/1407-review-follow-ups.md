### Fixed (node: misplaced blocks, pool tooling; review of #1407)

- A block that fails to extend the chain by its number or its slot is refused as invalid only if its header names
  the tip as its predecessor. `VolatileChain.extend` checks the number and the slot before the previous hash, so a
  header that did not link to the tip at all was refused too, and its sender punished, where that is the node's own
  inconsistency and stops it.
- The KES rotation driver sets the modes of the staged key and certificate on the files themselves; a link put in
  their place in the staging directory no longer has the mode set on what it points at.
- A VRF signing key that has been forgotten refuses to prove: its nonce key is zero, and a proof made with it would
  give the secret scalar away.
