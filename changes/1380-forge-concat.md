### Changed (Leios forging, #1380)

- Forging writes an endorser block's transactions, and a ranking block's transaction sequences, by copying each part's bytes once into the result. Before, each byte was boxed on the way, which took most of a 3,834-transaction endorser block's forge time on the public testnet (16 of the forge's 19 samples). The bytes written are unchanged.
- The CBOR encoder writes a byte or text string's bytes in one write, checking the output limit once for all of them, instead of one write per byte. Serving an endorser block's transactions to a peer spent most of its encoding time growing the output buffer byte by byte. The bytes written, and the limit's error, are unchanged.
