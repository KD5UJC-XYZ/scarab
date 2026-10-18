### Fixed (network resource limits, #35)

- Bound partial mux SDUs and stalled writes by the reference deadlines; slow trickles cannot reset
  the read timer, and established idle connections keep their indefinite transport wait.
- Enforce state byte ceilings for fully decoded messages, cap TxSubmission outstanding credit at10,
  reject duplicate/excess requests and offers, and use the reference10-second body-reply deadline.
- Record the existing ingress/egress bounds and stricter local protocol profiles explicitly, with
  deterministic hostile-state and cancellation coverage and private reference interoperability.
