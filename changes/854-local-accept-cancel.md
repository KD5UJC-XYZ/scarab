### Fixed (network, #854)

- A local client accepted just as the node-to-client server is cancelled is closed, not left open with no owner: the accept joins the blocking call instead of dropping its result (`LocalSockets.acquiring`, as the node-to-node listener has done since #898), and a client whose stream is not made is closed.
