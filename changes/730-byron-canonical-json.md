### Fixed (ledger, #730)

- The Byron genesis is parsed as the reference's `parseCanonicalJSON` parses it (strings with the escapes `\"` and `\\` only, integers of at most 15 digits, nothing read after the value), whichever era the chain starts in, and its hash is taken over `renderCanonicalJSON` of what was parsed; `nonAvvmBalances` and `avvmDistr` are required, and every entry of each is read.
