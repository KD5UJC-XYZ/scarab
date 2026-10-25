### Fixed (peer selection, #448)

- **The loss of an outbound connection always reaches the governor.** Both the connection's fiber and its hot
  session's fiber can report the loss, and the first to claim it reports. The hot session runs under the
  connection's own supervisor. When the session won the claim, the connection's fiber found the loss claimed,
  tore the connection down, and cancelled the session before it reported. The governor then kept a peer hot
  whose connection was gone, and did not connect to it again. The claim and the hand-off of the report are now
  one uncancelable step, and the report runs on the node's supervisor, which outlives the connection.
  `OutboundLossReportSuite` forces that order and fails on every run without the change.
