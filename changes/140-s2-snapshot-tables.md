### Added
- Streaming Haskell Mem-backend snapshot table framing, original-byte CRC32, metadata encoding and verification, and an ordered UTxO store stream. Export converts stored outputs to MemPack at the interchange boundary; V5 and stored CBOR values are unchanged. These are the building blocks for Step D import/export commands (#140, stages S3/S4).
- Era-checked table insertion uses an explicit semantic sink so imports can preserve the reference's acceptance of unsorted unique maps and enforce its duplicate-key rule without collecting the table in memory.
