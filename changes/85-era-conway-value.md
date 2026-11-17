### Added (era types, #85)

- Conway's UTXO check that a transaction's value is conserved runs on the era types, with the deposits and refunds of a body's certificates on the certificate state's class. Its verdict equals the existing rule's on every recorded Conway refusal and on a fee one lovelace over and under the balance; the node still judges with the existing rule, so nothing changes in behaviour.
