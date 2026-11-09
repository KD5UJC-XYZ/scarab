<!-- SPDX-License-Identifier: Apache-2.0 -->
# Genesis pool parameters

These synthetic inputs exercise the native `StakePoolParams` JSON parser with otherwise valid
pool fields. [provenance.json](provenance.json) records the exact capture, pinned source and
package identities; [the harness](../../../scripts/oracle/genesis-pool-fields/README.md) replays it.

`cases.jsonl` contains each input pool as JSON text (`poolJson`, so numeric spellings reach the
reader unchanged), native acceptance/refusal, and, when accepted, every field the reader returns:
`poolId`, `vrf`, `pledge`, `cost`, the reduced margin, `accountAddress`, `owners`, the encoded relay
sequence and zero or one encoded metadata payload. This covers identity and account aliases and
their fall-through on any failure, script and key credentials in both spellings, hash lengths,
aeson `Integer` Coin spellings (signed, exponents, the 1024 bound and 64-bit exponent wrapping),
decimal and rational margins, all three relay forms, alternative-parser precedence, optional/null
fields, Word16 port spellings and boundaries, IP spelling and whitespace rules, unrestricted JSON
DNS/URL text and arbitrary-length ASCII hex metadata hashes. The JSON text types do not impose
their CBOR decoder's length limits. Released binary 1.9.1.0 encodes IPv4 in network order; the
provenance distinguishes that fix from the older pinned binary source.

`GenesisPoolFieldsSuite` reads every pool under a fixed genesis key and compares every acceptance
decision and every accepted field, but a pool with a number whose exponent the reference's machine
`Int` wraps, which Scarab refuses ([overflow improvements](../../../docs/compatibility/overflow-improvements.md#json-number-exponents)); an empty relay sequence (`80`) is held as `Bytes.empty`, the
form the snapshot codec reads it back as. `WalletPeerReferenceSuite` checks that Scarab's genesis
bootstrap of the [native peer-relays cluster](../ntc-wallet-peers-11.1.3/README.md) yields the
relay and metadata bytes of that cluster's own pool state. This corpus does not establish a
whole-node oracle result, nor the raw-ingress (malformed UTF-8 text) behaviour of the genesis file.
