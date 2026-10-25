# Byron Ed25519 reference verdicts

**Status: Current** — optional offline tool behind [`fixtures/witness-byron`](../../../fixtures/witness-byron/README.md) (#392).

`scarab-byron-donna` reads rows `id, public_key_hex, signature_hex, message_hex` on stdin and prints
`id, vkey, redeem`: the verdicts of `verifySignatureRaw` and `verifyRedeemSigRaw` from the
unchanged released cardano-crypto-wrapper 1.7.0.0, which reach the ed25519-donna verifier of
crypton 1.0.6 through cardano-crypto 1.3.0. Those are the versions the cardano-node 11.1.3 release
binary links (its embedded package IDs), resolved at the node's Hackage and CHaP index states.

The image extends the Byron registration oracle, whose frozen plan already built these packages
(see [`../byron/full/README.md`](../byron/full/README.md) for that image). The harness is built
offline and adds no dependency:

```sh
docker build -f scripts/oracle/plutus/Dockerfile -t scarab-plutus175-oracle:local .
docker build -f scripts/oracle/byron/full/Dockerfile -t scarab-byron171-full-reference:local .
docker build --network none -f scripts/oracle/byron-donna/Dockerfile -t scarab-392-byron-donna-reference:local .
python3 -I scripts/oracle/byron-donna/generate.py \
  | docker run --rm -i --network none --read-only scarab-392-byron-donna-reference:local
```

`generate.py` writes inputs only (no verdict), deterministically; the fixture README lists its
classes. Upstream sources read for the Scala verifier, all from the archives pinned in the fixture
provenance: crypton `cbits/ed25519/ed25519.c:87-110` (`ed25519_sign_open`),
`ed25519-donna-impl-base.h:191-239` (`ge25519_unpack_negative_vartime`), `:253-298`
(`ge25519_double_scalarmult_vartime`), `:180-189` (`ge25519_pack`),
`curve25519-donna-64bit.h:287-319` (`curve25519_expand`) and `modm-donna-64bit.h:153-187`
(`expand256_modm`). cardano-crypto 1.3.0 bundles its own donna copy, used only by its BIP32
functions; its `ed25519_sign_open` and point decoding are identical to crypton's, which differs
only in a fix to the last iteration of `ge25519_double_scalarmult_vartime` that does not affect
the packed result.
