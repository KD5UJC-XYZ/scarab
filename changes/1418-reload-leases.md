### Fixed (the keys a reload supersedes, reads or refuses, #1418)

- A reload that replaces the credentials or disables forging, a reload that is refused, and a start
  with `--start-as-non-producing-node` erase the VRF key they supersede, read or hold; before, the VRF
  key was erased only when credentials failed to load, and the forger kept the start-up key for the
  node's lifetime. The credentials in force are held one generation at a time, and every use of the
  VRF key (a leader check, a chunk of the leader schedule, a proof) holds a lease on the generation
  it started with until it ends: a reload installs the next credentials at once, and the superseded
  ones are erased when the last evaluation under them ends, so an evaluation in flight at a reload
  ends with its key and a correct proof. A superseded KES key is erased with them, at that moment
  rather than at the reload, unless the new credentials keep it.
- The keys a reload reads belong to the read until the reload installs them, and the end of the read
  erases the others, on failure and cancellation too. No method of the forger's hot keys hands out the
  VRF signing key; the leader schedule's inputs name the key by its verification key.
