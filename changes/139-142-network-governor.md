### Added (Genesis peer-selection hooks and live topology networking, #139, #142)
- **Genesis peer-selection inputs now follow the consensus GSM.** `ConsensusMode`, judgement-sensitive sync targets,
  trusted-state output, and exact immutable peer-snapshot verification are wired into the governor. Full Genesis
  density and ledger-dependent fork choice remain outside this change in #33; Praos defaults are unchanged.
- **Topology roots can be reloaded on SIGHUP.** Invalid replacement input leaves the active topology usable. Valid
  updates preserve CLI roots and install runtime/governor state through an acknowledged generation boundary; stale
  DNS, snapshot and connection completions cannot replace newer state.
- **Topology and ledger relay DNS support SRV and TTL refresh.** A/AAAA answers, SRV priority/weight and the pinned
  TTL/retry policy are honored, with per-source failure retention and provenance. DNSSEC is not claimed.
- **Configured duplex outbound roots serve over the negotiated bearer.** Connection registration, supersession and
  cleanup are fenced by attempt/link identity so a delayed old handshake cannot displace a newer connection.
- **Idle duplex TxSubmission2 now follows the pinned Init behavior.** A responder waits cancelably for the first `Init` without a local timeout, keeping the bearer usable while the protocol is on demand; active exchange deadlines and message bounds remain in force. A silent-duplex regression checks the timeout control, bearer usability, and owner cancellation. The 300-second private relay now adopts the local submission and includes 64 of 74 mempool arrivals.
