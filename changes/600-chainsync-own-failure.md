### Fixed (network, #600)

- A ChainSync operation's own failure is reported as the protocol's end with its cause: an
  exception of a callback the collector runs as a defect of the connection, a typed failure as
  itself. Before, both were reported as the cancellation `Aborted`, which since #597 could then
  carry another protocol's failure. Only a cancellation is reported as `Aborted`.
