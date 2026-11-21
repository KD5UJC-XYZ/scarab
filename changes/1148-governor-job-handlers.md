### Fixed (network, #1148)

- A peer governor job that raises now ends with a completion, instead of ending its fiber with none
  and leaving its peer in the state the job was started for; a connect left the peer connecting,
  and a reload of the roots kept it so. A connect that raises leaves the peer cold for the cold
  back-off (5 s, fuzzed by up to 2 s, at the first failure); an activation closes its connection and
  leaves the peer cold after the one-second pause, for the error delay, or cooling while the
  connection is still held, the close's failure reported; a deactivation or a close
  waits for the connection's release, up to the demotion timeout; a request for public roots or big
  ledger peers is a failed request, one back-off step on; a peer-share request is that peer's failed
  request; and a peer-share phase reports its cause for its peers, which are then no longer in
  flight, and leaves the time each is next asked as it was. As for the node's other exceptions on a
  connection, the exception counts nothing against the peer. It is logged: a connect's as an
  establishment defect, an activation's, deactivation's or close's as that action's failure, a root
  request's in its failure line (`defect`), and a peer-share request's or phase's in the share
  results. A job cancelled at shutdown still offers nothing.
- A log line that cannot be written no longer changes how a job ends: a failed connect or
  activation keeps the class of its failure, where the failure of that write left the peer
  connecting.
- A deactivation or close whose connection is not seen released within the demotion timeout now
  leaves the peer where it was, hot, or warm with the time it may be activated from, and the
  connection's own report that came meanwhile then applies: cooling, the peer is cooling, as
  before; gone, the peer is cold, where a release reported while the job still waited left it
  cooling for good. With no report, the peer stays until one comes.
- A peer-share request that is cancelled is reported, as cancelled, when it ends, not at the
  batch's deadline as timed out; and a batch's second phase now starts once its first phase's
  answers are applied, so a late reply has the whole of its time.
