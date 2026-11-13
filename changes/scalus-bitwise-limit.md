### Fixed (Plutus builtins)

- `writeBits` fails the script when its input byte string is longer than 4,096 bytes in builtin semantics variants D to G (every Plutus language from protocol 11), as plutus 1.70.0.0 and 1.71.0.0 do (`maximumInputLength`), after its cost is charged and with the reference's two log lines. Scalus 1.4.0 has no such limit. Variants A to C are unchanged. Checked against the reference's verdicts and budgets in `fixtures/reference/plutus-writebits-limit`.
