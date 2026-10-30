# Pool relays (decoder versions 2 to 9)

Status: Current. Evidence for [#436](https://github.com/KD5UJC-XYZ/scarab/issues/436).

The reference decodes a pool registration's relays with the transaction body
(`decodeStakePoolParamsFlat` and `instance DecCBOR StakePoolRelay`,
`libs/cardano-ledger-core/src/Cardano/Ledger/State/StakePool.hs:406-421, 578-589`, cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`):
- a port is a `Word16`;
- an IPv4 or IPv6 address is a byte string of exactly 4 or 16 bytes, read with `decodeBytes`, so
  it is definite below decoder version 12;
- a DNS name is a `DnsName`: definite text of at most 64 bytes of UTF-8 below version 9 and 128 from
  it (`BaseTypes.hs:644-697`).

`cases.jsonl` holds 65 lines, one case each, written by
`scripts/oracle/conway-decoding/pool_relay_cases.py`. For each era from Shelley to Conway, a body
with one pool registration whose relays the reference accepts (an address, a name and an SRV name;
an IPv6 address), and the same registration with one relay changed:
- an IPv4 address of five bytes;
- an IPv4 address and an IPv6 address as one indefinite chunk;
- a port of 65536;
- a DNS name as one chunk;
- a DNS name at the era's bound, and a DNS name and an SRV name one byte over it. Below version 9
  there is also a 128-byte name, which only version 9 takes.

The verdict (`accepted` and the complete `failure`) is what the unchanged released decoders did with
each: `decodeFullAnnotator` at the era's decoder version. In all, 18 accepted and 47 refused. Every
refusal names `rule`, a fragment of the reference failure, and `expect`, the Scarab error case
`PoolRelay`. `PoolRelaysReferenceSuite` requires the same verdict, and for an acceptance that the
retained bytes (and so the transaction id) are the original ones. `provenance.json` records the
package versions and the hashes of the harness, the generator, its input and output, and the corpus.

## Regenerating

Build `scarab-conway-decoding:local` as [the oracle README](../../../scripts/oracle/conway-decoding/README.md)
describes, then:

```sh
python3 scripts/oracle/conway-decoding/pool_relay_cases.py --inputs /tmp/pool-relays-inputs.json
docker run --rm --network none --read-only -v /tmp:/fixtures:ro \
  scarab-conway-decoding:local /fixtures/pool-relays-inputs.json > /tmp/pool-relays-reference.json
python3 scripts/oracle/conway-decoding/pool_relay_cases.py --inputs /tmp/pool-relays-inputs.json \
  --reference /tmp/pool-relays-reference.json --out fixtures/reference/pool-relays/cases.jsonl
```

## Not covered here

The relays of a registration the node already holds (a stored ledger state, the ledger peers) are
read by `PoolRelays.decode`. That reader uses the version-9 bound, the bound of the Conway states the
node keeps, and it still takes a chunked address as the builds before #436 stored it (ADR 0006).
