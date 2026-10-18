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
contacted by these probes. The follow-up below separately executes full-block header decoding
and an unchanged registration-predicate excerpt; it does not execute full reference registration.

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

## Complete-block header decoder and registration predicate

`headers/` retains the first existing public mainnet block from
`fixtures/mainnet/byron-main-blocks.hex` unchanged as a control, plus eight copies replacing only
the protocol-version item at CBOR path `[1,0,4,0]`. Its manifest binds the original block and every
complete variant to their SHA-256. No surrounding item is re-encoded. All four legal variants
(major 65535, minor 65535, alt 255, and all three maxima together), plus the unchanged block,
were accepted by the actual reference CLI block decoder. Major/minor 65536, alt 256, and major
4294967297 failed at the corresponding header field before any signature or ledger validation.
`ByronHeaderFixtureSuite` checks these same complete bytes in Scala.

The mutated blocks' signatures do not verify. A CLI message `Valid Byron block` means decoding
only; these fixtures are not claimed to be valid chain blocks. Public source-block provenance
and licensing are documented in [fixtures/mainnet](../mainnet/README.md).

`registration.json` records eleven actual executions of the unchanged pinned Haskell
`pvCanFollow` predicate with its original bounded type: six accepted transitions and five refused.
It distinguishes next major/minor at the maximum from wrapping and invalid succession. The
signed `ByronVersionSuite` now consumes these results through Scala's full rules. This is
predicate-excerpt evidence, **not full reference ledger-registration execution**; broader
reference execution remains [#171](https://github.com/KD5UJC-XYZ/scarab/issues/171).
The source/extraction hashes, exact scope and one-command offline reproduction are in
[scripts/oracle/byron](../../scripts/oracle/byron/README.md). No new network capture was needed.
