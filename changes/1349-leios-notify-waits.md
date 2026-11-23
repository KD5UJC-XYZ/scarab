### Changed (Leios notify, #1349)

- A LeiosNotify client and server wait for a reply, a credit or the stop without building a stream for each
  message, and a server answers a request with a reply already queued without racing the client's quit. Where
  a client waits for its replies, as one that checks each vote's signature does, its messages take less CPU.
