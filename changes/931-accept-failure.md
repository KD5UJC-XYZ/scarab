### Fixed (network, #931)

- A failed accept on the node-to-node listening socket is no longer dropped and retried at once without a trace (as fs2's
  asynchronous-channel provider did). It is traced as `Net.RemoteAcceptError`, and any failure other than
  `ECONNABORTED` stops the node, traced as `Net.RemoteServerStopped`, `Net.RemoteServerError` and `diffusionErrored`, as the
  reference's accept loop does (`Server.hs` 188-254, ouroboros-network 2fb69829). The JDK retries `ECONNABORTED`
  itself, so that case is neither traced nor paused for as the reference's is.
- The listening socket and its accepted connections use Scarab's adaptation of fs2 3.14.0's asynchronous-channel socket
  (MIT, attributed in `NOTICE`), with the same reads and writes.
