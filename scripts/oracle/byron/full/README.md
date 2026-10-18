# Full Byron reference registration oracle

**Status: Current** — optional offline tool for the original REF-07 (#171) acceptance.

Build the existing pinned Plutus compiler/dependency base, then this image from the repository root:

```sh
docker build -f scripts/oracle/plutus/Dockerfile -t scarab-plutus175-oracle:local .
docker build -f scripts/oracle/byron/full/Dockerfile -t scarab-byron171-full-reference:local .
python3 scripts/oracle/byron/full/run.py --output /tmp/byron-registration-replay.json
```

Ordinary build downloads are needed. Executions use `--network none --read-only`; no node,
public-network connection or socket is involved. `run.py` verifies fixture hashes, required coverage,
original-byte mutations, exact typed categories, independent header crypto and accepted transaction
state effects before comparing the complete actual JSON report. It never substitutes the earlier
predicate excerpt for registration. The normal Python tests include negative controls for those
assertions; Scala replays the original signed bytes in `ByronRegistrationFixtureSuite`.

The image builds **unchanged released** `cardano-ledger-byron 1.3.0.0`, crypto-wrapper 1.7.0.0,
ledger-binary 1.9.1.0 and their frozen dependencies. The project uses node 11.1.3's Hackage/CHaP
index states; all queried versions match the actual executable's embedded package IDs. The
[fixture provenance](../../../../fixtures/byron-version/full-registration/provenance.json) retains
those IDs, complete package plan, source comparison and hashes. The build uses GHC 9.10.3 rather
than the release's GHC 9.6; it is not a bit-identical release build. Native libsodium uses the exact
`flake.lock` revision, with the supported no-update autotools option so `autogen.sh` does not fetch
moving GNU config scripts. No upstream validation or crypto source is edited, shimmed or omitted.

`Main.hs` decodes with original-byte annotations. It invokes full registration, proposal/ordinary
transaction mempool admission and complete-block ledger validation, composing the latter with
unchanged original-byte header and certificate signature primitives. The bad-header control
keeps a successful ledger result separate from the failed composite. Setup changes only the
adopted version of a normally initialized synthetic genesis state. This proves the tested APIs and
their composition, not PBFT scheduling/history or protocol adoption. Exact scope and all 22 cases
are described in the [fixture README](../../../../fixtures/byron-version/full-registration/README.md).
Source entry points at pinned ledger `f649f97`:
`Update/Validation/Registration.hs:331–357`, `Update/Validation/Interface.hs:259–310`,
`Byron/API/Mempool.hs:76–102,211–229`, `Byron/API/Validation.hs:153–173` and
`Block/Header.hs:707–722`. All 91 released Byron source modules match that pin.
Consensus `Byron/Ledger/Integrity.hs:22–59` at `82ecba3` identifies the separate signature gate;
this harness does not claim to run that entire consensus layer.

To make a **new** synthetic fixture set, use a disposable directory mounted writable. The keys
exist only during `generate`; they are never written. Regeneration intentionally uses fresh keys
and therefore fresh bytes. Replay of the retained bytes is exact and requires no key.

```sh
mkdir -p /tmp/new-byron-fixtures
docker run --rm --network none --user "$(id -u):$(id -g)" \
  -v /tmp/new-byron-fixtures:/fixtures scarab-byron171-full-reference:local generate /fixtures
```

Then call `boundaries.augment(directory)` once to add the eight original-span width variants and
run the image's `replay /fixtures` command. Capture the complete stdout as `reference.json` and
retain new hashes/provenance before adopting a new corpus. New output does not silently replace
the committed golden fixtures.
