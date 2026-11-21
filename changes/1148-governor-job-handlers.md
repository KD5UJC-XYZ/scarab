### Fixed (network, #1148)

- A peer governor job that raises now ends with the completion the reference's handler for that job
  gives, instead of ending its fiber with no completion and leaving its peer in the state the job was
  started for. A connect that raises leaves the peer cold for the cold back-off (5 s, fuzzed by up to
  2 s, at the first failure), where the peer stayed connecting until a reload of the roots; an
  activation leaves it cold after the one-second pause, for the error delay; a deactivation or a
  close waits for the connection to be released, up to the demotion timeout, and leaves the peer
  cold, or cooling if it is still held; a request for public roots or big ledger peers is a failed
  request, one back-off step on; and a peer-share request or phase that raises reports each of its
  peers' requests failed. As for the other exceptions of the node's own code on a connection, the
  exception counts nothing against the peer. It is logged: a connect's as an establishment defect, an
  activation's, deactivation's or close's as that action's failure, and a peer-share request's in
  the share results. A cancelled job, at shutdown, still offers nothing.
- A failed connect whose log line cannot be written now completes all the same, where the failure of
  that write left the peer connecting.
