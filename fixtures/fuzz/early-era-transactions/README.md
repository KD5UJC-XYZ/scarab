# Early-era whole-transaction decoder seeds

These three synthetic transactions were serialized by the Apache-2.0 reference CLI
11.2.3.0 bundled with cardano-node 11.1.3 on 2026-10-20. Each `.hex` is the unmodified
`cborHex` field of the resulting native text envelope, including the original three-field
transaction envelope. Shelley has its required TTL; Allegra and Mary omit the optional TTL.

Each transaction contains one input, one enterprise-address output of 1,000,000 lovelace,
a fee of 200,000 lovelace, no auxiliary data and an empty witness set. They exercise era-specific
decoding, including nonempty input/output paths. They do not claim ledger admission: the input
is synthetic and there are no signatures. No key or network connection was used.

Reproduce from the repository root with the pinned local reference image:

```sh
mkdir -p /tmp/scarab-early-era-seeds
docker run --rm --user "$(id -u):$(id -g)" --entrypoint sh \
  -v /tmp/scarab-early-era-seeds:/data cardano-reference-11.1.3:local -c '
  for era in shelley allegra mary; do
    cardano-cli compatible "$era" transaction signed-transaction \
      --tx-in 0000000000000000000000000000000000000000000000000000000000000000#0 \
      --tx-out addr_test1vqqqzqsrqszsvpcgpy9qkrqdpc83qygjzv2p29shrqv35xcftcpvd+1000000 \
      --fee 200000 --testnet-magic 42 --out-file "/data/$era.json"
  done'
```

Extract each JSON `cborHex` as a single hex line. The address is the testnet enterprise header
`60` followed by the 28 public bytes `00` through `1b`, with its Bech32 checksum. Image identity:
`sha256:63bb366ad306366653fd9dfb23e16cceadd1ab68de6556c1e761358a5364bb1e`.
The generated test inputs are distributed under this project's Apache-2.0 license;
reference notices remain in [the fixture licenses](../../licenses/).
