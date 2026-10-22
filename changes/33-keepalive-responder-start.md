### Fixed (networking, #33)

- Start the duplex KeepAlive responder's deadline on the first remote-initiated
  protocol traffic, matching upstream `StartOnDemandAny`. An unused reverse
  direction no longer causes a healthy connection to close after 97 seconds.
- Install only negotiated application protocols and directions before responder
  activation, rejecting disabled PeerSharing and late handshake traffic. Preserve
  the reference raw handshake SDU boundary, ignored final decoder trailing bytes,
  and distinct handshake/application SDU deadlines.
