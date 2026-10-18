These five rows are full generated `POOL` judgments from the pinned cardano-ledger
Conway conformance executable. The upstream `PoolCert` wire constructor is retained
and decoded into Scarab's typed registration/retirement signal. The environment is the
actual `[epoch, PParams]`; the implicit globals come from the pinned Shelley ImpTest
genesis, whose `sgNetworkId` is `Testnet` (0). This is a bounded sample, not a complete
run of the upstream property.
