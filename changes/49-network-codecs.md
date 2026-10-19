### Added (K2 / #49)

- Retain original generic Handshake negotiation/refusal and ordinary ChainSync/BlockFetch
  codec observations, replaying their wire contracts through shared production codecs.
  Cardano version policies remain unchanged; fixture inventories describe the bounded
  samples and the remaining runtime, payload and scheduling limits.
- Keep generic Handshake versions, messages, refusals and selection failures indexed by
  their version domain, with contextual wire/data codecs and ordering. Preserve existing
  Cardano facade behavior and the original mock/asymmetric version values.
