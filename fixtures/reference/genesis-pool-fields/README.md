<!-- SPDX-License-Identifier: Apache-2.0 -->
# Genesis pool relays and metadata

These synthetic inputs exercise the native `StakePoolParams` JSON parser with otherwise valid
pool fields. [provenance.json](provenance.json) records the exact capture, pinned source and
package identities; [the harness](../../../scripts/oracle/genesis-pool-fields/README.md) replays it.

`cases.jsonl` contains the input pool, native acceptance/refusal, and, when accepted, the encoded
relay sequence and zero or one encoded metadata payload. This covers all three relay forms,
alternative-parser precedence, optional/null fields, Word16 port boundaries, IP spelling and
whitespace rules, unrestricted JSON DNS/URL text and arbitrary-length ASCII hex metadata hashes.
The JSON text types do not impose their CBOR decoder's length limits. Released binary 1.9.1.0
encodes IPv4 in network order; the provenance distinguishes that fix from the older pinned binary source.

`GenesisPoolFieldsSuite` compares every acceptance decision and every accepted relay/metadata byte;
an empty relay sequence (`80`) is held as `Bytes.empty`, the form the snapshot codec reads it back
as. `WalletPeerReferenceSuite` checks that Scarab's genesis bootstrap of the
[native peer-relays cluster](../ntc-wallet-peers-11.1.3/README.md) yields the relay and metadata
bytes of that cluster's own pool state. This corpus does not establish conformance of the other
pool fields (identity aliases, Coin, margin and reward-account forms) or a whole-node oracle result.
