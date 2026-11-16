### Fixed (conformance tooling)

- CBOR reports exercise the production bounded language reader in both eras, write empty Conway multiasset maps with the standalone map codec, and re-encode a Byron address's payload and checksum with the existing production writer. The legacy cost-model reader shares the language reader and retains its admission rules.
- Dijkstra reports write decoded values, voters, voting procedures, metadata and witnesses with the existing shared production encoders. Redeemers retain the exact bytes kept by their decoded witness set. Unsupported rules and differences between the corpus and the selected reference build remain visible failures.
