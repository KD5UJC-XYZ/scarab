Local ChainSync carries opaque serialized block responses and outgoing intersection offers through
chunked logical messages. Streamed response decoding and bounded SDU packing remove the whole-array
ceiling at this protocol boundary while preserving native CBOR wrappers and receive policies.
Ledger validation and stored block representations remain separate boundaries.
