### Fixed (networking, #33)

- Start the duplex KeepAlive responder's deadline on the first remote-initiated
  protocol traffic, matching upstream `StartOnDemandAny`. An unused reverse
  direction no longer causes a healthy connection to close after 97 seconds.
