### Fixed (network, #389)

- A mini-protocol message that arrived just before the peer closed the connection is delivered before the closure is reported. Before, a receive that had just found its queue empty could see the closure first and report the peer's close, losing that last message (for example, the final message of a clean shutdown).
