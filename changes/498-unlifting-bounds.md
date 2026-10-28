### Fixed (Plutus, #498)

- From protocol 11 (semantics variants D and E, PlutusV1 to PlutusV3), the 35 builtins that take an integer as `CInteger` or a byte string as `CByteString` refuse an argument outside `-2^262143 .. 2^262143 - 1` or longer than 65,536 bytes: the script fails and the application costs nothing, as in plutus 1.70.0.0 (`Default/Universe/Cardano.hs`, `Builtin/Meaning.hs` `toMonoF`). Scalus 1.4.0 evaluated them, so a script that hashed a byte string built past the bound succeeded where the reference fails it. Results stay unbounded.
