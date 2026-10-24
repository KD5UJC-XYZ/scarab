### Changed (multiplexer, #399)

- Send SDUs of at most 12,288 payload bytes, the size the reference gives its socket bearers on node-to-node and node-to-client connections (`makeSocketBearer'`, `makeLocalBearer`). Frames up to the 16-bit header limit (65,535) are still accepted from a peer, as the reference's reader does.
