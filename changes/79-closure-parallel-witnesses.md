### Changed (Leios voting, #79)

- The closure check a Leios voter runs before it votes verifies the witness signatures of the
  transactions it applies in full in parallel, while it reads their spent outputs, and then applies
  the transactions in order as before, taking each verdict from the node's verification cache. The
  verdicts are unchanged; on the recorded 323-transaction closure the check takes 75 ms instead of
  116 ms on 4 threads.
