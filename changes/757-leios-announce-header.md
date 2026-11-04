### Fixed (LeiosNotify announced headers, #757)

- A LeiosNotify announcement's header is read by the decoder of its era, as the build the public
  Leios testnet runs reads it (`Header blk` with the node-to-node decoder of the era,
  ouroboros-consensus `2416493b`): a Dijkstra envelope must hold a Leios header, an earlier era's
  envelope that era's header, and a header the era's decoder refuses fails the message with that
  refusal (`LeiosWireError.UndecodableAnnouncement`). The node supplies the era decoders to the
  protocol codec (`LeiosNotify.AnnouncementReader`, `AnnouncedHeaders`).
