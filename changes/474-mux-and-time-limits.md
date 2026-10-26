### Changed (#474 rows 31 and 34: mux SDU size and state time limits follow the reference)
- **SDUs are sent at 12,288 bytes and accepted up to 65,535.** The multiplexer cut messages at the largest
  accepted payload, 65,535 bytes; the reference socket bearer, node-to-client sockets included, sends
  12,288 (`Network/Mux/Bearer.hs:83-86`) and accepts any length that fits the header. `Mux.Config.sduSize` is the
  send size, `maxSduPayload` stays the acceptance limit. The handshake keeps its 10 s SDU timeout, which is
  the reference's (`ConnectionHandler.hs:98-102`), the other protocols 30 s.
