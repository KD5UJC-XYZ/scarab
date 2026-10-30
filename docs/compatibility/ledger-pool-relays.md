<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: pool relays decoded with the body, decoder versions 2 to 9 (#436)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-pool-relays` in [compatibility.json](../data/compatibility.json). The sections below record the implementation, evidence and remaining limits.

## Status as recorded

implemented: the reference decodes a registration's relays with the transaction body (`decodeStakePoolParamsFlat`, `instance DecCBOR StakePoolRelay`, `State/StakePool.hs:406-421, 578-589`, cardano-ledger `f649f975`). `Certificate.poolParams` now reads them with `PoolRelays.fromBody` at the body's decoder version: a `Word16` port, an address of exactly 4 or 16 bytes in the definite form the version reads, a DNS name of definite text of at most 64 bytes of UTF-8 below version 9 and 128 from it (`BaseTypes.hs:644-697`). A refusal is the typed `LedgerDecodeError.PoolRelay` with the `PoolRelayError` of the relay. The original relay bytes are retained and hashed as before.

## Evidence

`PoolRelaysReferenceSuite`: 65 original bodies (`fixtures/reference/pool-relays/cases.jsonl`, oracle `scripts/oracle/conway-decoding/pool_relay_cases.py`, harness unchanged): in each era from Shelley to Conway, accepted relays (an address, a name and an SRV name; an IPv6 address) and the same registration with one relay changed: an IPv4 address of five bytes, an IPv4 and an IPv6 address as one chunk, a port of 65536, a DNS name as one chunk, a DNS name at the era's bound and a DNS and an SRV name one byte over it (below version 9 also 128 bytes). 18 accepted, 47 refused, each refusal recorded with the fragment of the reference failure.

## Explicitly unchecked or unconfirmed

The relays of a registration the node holds (a stored ledger state, the ledger peers) are read by `PoolRelays.decode`, at the version-9 bound of the Conway states the node keeps; it still takes a chunked address as the builds before #436 stored it (ADR 0006). The pre-Conway parameter-update values read with the body are #470.
