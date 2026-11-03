# Address and reward account headers (decoder versions 2 to 9)

Status: Current. Evidence for [#723](https://github.com/KD5UJC-XYZ/scarab/issues/723).

An address's first byte is its header: the high four bits name its type, the low four its network.
The reference reads only bit 0 as the network (`headerNetworkId`,
`libs/cardano-ledger-core/src/Cardano/Ledger/Address.hs:557-561`, cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`) and refuses the rest:
- a Shelley address whose header sets a bit of `headerNonShelleyBits = 0b10001110` is refused,
  "Invalid header. Unused bits are not suppose to be set" (`decodeAddrStateLenientT`, `:655-666`).
  So of the sixteen nibbles of types 0 to 7, only 0 and 1 are taken, and no header of types 8 to
  15 is taken except the bootstrap address's `0b10000010` (`isByronAddress`, `:548-552`);
- a reward account's header must be `0b111x000y` (`headerIsAccountAddress`, `:930-932`), so only
  `0xe0`, `0xe1`, `0xf0` and `0xf1` are taken.

Every era reads an output's address with `fromCborBothAddr` (`:459-477`), through the backwards
reader below version 7 and the rigorous one from it. Both make this check. A reward account is
read with `fromCborAccountAddress` (`:913-916`) in a withdrawal, a pool registration, a proposal and
a treasury withdrawal.

`cases.jsonl` holds 260 lines written by `scripts/oracle/conway-decoding/address_cases.py`, in two
kinds:
- for each era from Shelley to Conway, an address (`kind` "address", `DecCBOR Addr`) and a reward
  account (`kind` "account", `DecCBOR AccountAddress`) read alone. There is one line for each of
  the sixteen types, holding the sixteen low nibbles over the payload of the type: a base address's
  two hashes, a pointer address's hash and pointer `1 2 3`, an enterprise address's hash, a
  bootstrap address's CBOR after its first byte, or one hash for an account or any other type.
  `bytes` is the CBOR byte string of nibble 0 (a case is it with its header changed); `accepted`
  holds the sixteen verdicts in order; `failure` is the failure of a refused nibble with
  `{header}` in place of the header the reference shows (`show (Header w)`, `0b` and its binary
  digits). Every refused nibble's complete failure is that one with its header (a failure that
  differed would be kept whole in `failures`; none does). That is 3072 cases, 126 accepted;
- bodies (`kind` "body", `decodeFullAnnotator` of the era's `TxBody`) holding an address or an
  account of network 1 and 2 in each place a body reads one: an enterprise, a base and a pointer
  output, a withdrawal and a pool registration's reward account in every era, a collateral return
  from Babbage, and a proposal's return account and a treasury withdrawal in Conway. That is 68
  bodies, 34 accepted, every network-2 body refused.

`AddressHeadersReferenceSuite` requires the same verdict from `Address.decode` at the era's mode
and from `RewardAccount.decode` for every case alone, and from `TxBody.decode` for every body. It
also checks that a stored output still reads with any network nibble (`Address.Mode.Stored`,
ADR 0006). `provenance.json` records the package versions, the hash of the released `Address.hs`
against the pin, the hashes of the harness, the generator, its input and output, and the corpus.

## Regenerating

Build `scarab-conway-decoding:local` as [the oracle README](../../../scripts/oracle/conway-decoding/README.md)
describes, then:

```sh
python3 scripts/oracle/conway-decoding/address_cases.py --inputs /tmp/address-inputs.json
docker run --rm --network none --read-only -v /tmp:/fixtures:ro \
  scarab-conway-decoding:local /fixtures/address-inputs.json > /tmp/address-reference.json
python3 scripts/oracle/conway-decoding/address_cases.py --inputs /tmp/address-inputs.json \
  --reference /tmp/address-reference.json --out fixtures/reference/address-headers/cases.jsonl
```

## Not covered here

- The bootstrap address's own payload: here it is one valid address. Its checksum and envelope are
  cases of [the Conway decoding corpus](../conway-decoding/README.md) (`cases.py`, "Byron address").
- A node-to-client query's addresses (`GetUTxOByAddress`), read as the era reads an output's
  address once the query readers of #709 use `Address.decode`.
