### Added (era types, #85)

- Conway's UTXO checks run together on the era types, in the reference's order with every failure kept, and with the bootstrap address failure as each reference build reports it. Their verdict equals the existing rule's on every recorded Conway refusal, under every build and validation policy; the node still judges with the existing rule, so nothing changes in behaviour.
