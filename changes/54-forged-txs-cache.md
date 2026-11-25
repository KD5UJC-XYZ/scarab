### Fixed (Leios voting, #54)

- A forged endorser block's transactions are recorded in the Leios transaction cache as already validated, under the parity of the protocol version of the state the forge revalidated them on, as the reference records them. Before, the node's closure check of its own endorser block applied in full every transaction not already cached from another endorser block: 3,233 of 3,834 on the public testnet, 2.7 s before its vote.
