### Fixed (network, #1148)

- A peer governor job that raises now ends with a completion, instead of ending its fiber with none
  and leaving its peer in the state the job was started for (a connect left the peer connecting,
  and a reload of the roots kept it so). The completion takes effect while the peer is still in that
  job's state, as a typed completion does. A connect that raises leaves the peer cold for the cold
  back-off; an activation closes its connection, waits for its release up to the demotion timeout
  and leaves the peer cold for the error delay after the one-second pause, or cooling if the close
  raises or the release does not come; a deactivation or a close waits for the release up to the
  demotion timeout and leaves the peer cold, or cooling if it is still held, as a typed failure does;
  a request for public roots or big ledger peers is a failed request, one back-off step on; a
  peer-share request is that peer's failed request; and a peer-share phase reports its cause for its
  peers, which are then no longer in flight, and leaves the time each is next asked as it was. The
  exception counts nothing against the peer, as for the node's other exceptions on a connection. It
  is logged: a connect's as an establishment defect, an activation's, deactivation's or close's as
  that action's failure, a root request's in its failure line (`defect`), and a peer-share request's
  or phase's in the share results. A job cancelled at shutdown still offers nothing.
- A log line that cannot be written no longer changes how a job ends: a failed connect or
  activation keeps the class of its failure, where the failure of that write left the peer
  connecting.
- A peer-share request that is cancelled is reported as cancelled when the phase collects the
  results, not at the batch's deadline as timed out; a batch's second phase starts once its first
  phase's answers are applied, so a late reply has the whole of its time.
- An activation cleanup whose close times out reports the close's timeout, as the close job does.
- A peer left cooling by an activation's cleanup or a demotion's timeout is moved only by its
  connection's own report; when no report comes it stays cooling (#1307).
