### Added (era types, #85)

- The output checks of Conway's UTXO rule run on the era types: the least lovelace of each output, the size of its value and the attributes of a bootstrap address. Their verdicts equal the existing rule's on every recorded Conway refusal and on a crafted value-size limit; the node still judges with the existing rule, so nothing changes in behaviour.
