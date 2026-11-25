### Fixed (ChainSync candidates, #810)
- **A peer's candidate is kept as the reference keeps it, an anchored fragment, so the header gate's work for a
  header no longer depends on the candidate's length.** Each new header rebuilt a set of every hash on the candidate,
  and the Genesis runtime rebuilt vectors of every point for each header it published or offered for a jump, and
  searched the candidate from its tip back to our chain. The candidate's points now sit in an indexed fragment that
  appending a header and looking up a point read in constant time, and that rolling back or trimming to our chain's
  anchor changes at the cost of what it drops; the search for our chain on the candidate starts from our newest
  block.
