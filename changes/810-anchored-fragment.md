### Fixed (ChainSync candidates, #810)
- **A peer's candidate is kept as the reference keeps it, an anchored fragment, so a header's work no longer
  grows with the candidate's length.** Each new header of the header gate's candidate rebuilt a set of every hash on
  it, and the Genesis runtime rebuilt vectors of every point for each header it published or offered for a jump.
  The candidate's points now sit in an indexed fragment that appending a header, rolling back, trimming to our
  chain's anchor and looking up a point change or read in amortised constant time. The lead peer's header rate,
  which caps a Genesis sync from origin, no longer falls as its candidate grows.
