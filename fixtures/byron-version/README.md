# Byron protocol-version field widths (REF-07, #171)

**Status: Historical** — executable reference proposal-decoder evidence, recorded 2026-10-18.

`valid.hex` is a raw, signed Byron update proposal produced by the official release's
`cardano-cli 11.2.3.0`, with version `(65535, 65535, 255)`. The key was generated for this fixture
and deleted; only its public verification key and signature occur in the proposal. The four other
files replace just one integer in the first protocol-version array. Their signatures consequently
do not verify, but the reference rejects their field widths before signature verification or any
socket connection. The original maximum-width signed proposal decodes and reaches the deliberately
absent socket. A socket failure is evidence of decoding, not ledger acceptance.

The CLI is bundled with node release **11.1.3**; these are different component versions.
`manifest.json` records the CLI identity, binary and release-archive SHA-256, local image ID, raw
fixture hashes, command, exit codes, decoder error offsets and stderr categories. No node was
contacted by these probes. This does not execute reference header decoding or proposal-registration
rules; those remain distinct reference work under [#175](https://github.com/KD5UJC-XYZ/scarab/issues/175).

Creation used `byron key keygen --secret <temporary-key>` and `byron governance
create-update-proposal --testnet-magic 4242 --signing-key <temporary-key>
--protocol-version-major 65535 --protocol-version-minor 65535 --protocol-version-alt 255
--application-name cardano-sl --software-version-num 1 --system-tag linux
--installer-hash <32 zero bytes as hex> --filepath <output>`. The original starts
`87 83 19ffff 19ffff 18ff`; the mutations replace major/minor with `1a00010000`, alt with
`190100`, or major with `1b0000000100000001`. All remaining bytes are unchanged.

To reproduce the raw decoder verdicts, first prepare the pinned [reference image](../../docs/reference-image.md),
then convert the committed hex files to a temporary directory:

```sh
python3 - <<'PY'
from pathlib import Path
out = Path('/tmp/scarab-byron-version-probes')
out.mkdir(exist_ok=True)
for source in Path('fixtures/byron-version').glob('*.hex'):
    (out / (source.stem + '.proposal')).write_bytes(bytes.fromhex(source.read_text()))
PY
```

For each case, run this command with the corresponding filename:

```sh
docker run --rm -v /tmp/scarab-byron-version-probes:/oracle:ro \
  cardano-reference-11.1.3:local cardano-cli byron governance submit-update-proposal \
  --testnet-magic 4242 --socket-path /oracle/no-such-node.socket --filepath /oracle/valid.proposal
```

All five commands exit 1: the valid proposal fails at `Network.Socket.connect`; the malformed
proposals report `DecoderErrorDeserialiseFailure` with `expected word16` or `expected word8`.
`ByronProposalFixtureSuite` checks the same retained bytes in Scala, including the valid proposal's
signature and original bytes. `ByronVersionSuite` separately exercises Scala's signed complete-block,
transaction and registration paths. Source cross-check: ledger f649f97,
`Cardano/Chain/Update/ProtocolVersion.hs:27-32,65-68`,
`Validation/Registration.hs:489-499`, `byron.cddl:126`.

These synthetic test fixtures are Apache-2.0, like this repository. The reference ledger's
Apache-2.0 notices are retained in [fixtures/licenses](../licenses); no CLI implementation source
is vendored. No signing key is included.
