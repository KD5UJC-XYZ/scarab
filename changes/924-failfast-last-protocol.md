### Fixed (node-to-client, #924)

- A node-to-client connection whose last running mini-protocol fails ends with that protocol's error. The error
  could be lost when no other protocol of the connection was still running, and the connection then ended as
  though the client had finished.
