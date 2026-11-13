### Fixed (network, #949)

- A node-to-client socket path of exactly 108 characters is refused with the reference's `pokeSockAddr` error
  (network 3.2.8.0 refuses 108 characters or more), not with the JDK's.
- A socket path the JDK cannot encode (one the locale's encoding cannot represent, or one that holds NUL) is refused as
  a failure of the node-to-client socket's bind, rather than as an exception the bind did not handle.
