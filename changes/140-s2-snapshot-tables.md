### Added (snapshot table streaming, #140)

- Streaming Haskell Mem-backend snapshot table framing, original-byte CRC32, metadata encoding and verification, and an ordered UTxO store stream. Export converts stored outputs to MemPack at the interchange boundary; V5 and stored CBOR values are unchanged.
- Era-checked insertion uses an explicit semantic sink so imports preserve reference acceptance of unsorted unique maps and enforce duplicate-key rules without collecting the table in memory.
