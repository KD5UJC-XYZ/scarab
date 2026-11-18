### Added (era types, #85)

- The keys that must sign a Conway transaction are found on the era types (`EraUTxO`'s `getWitsVKeyNeeded`), and the era-typed missing-witnesses check reads them. Its verdict equals the existing rule's on every recorded Conway refusal, and where the reference refused a transaction for a missing witness alone, its refusal is the reference's byte for byte; the node still judges with the existing rule, so nothing changes in behaviour.
