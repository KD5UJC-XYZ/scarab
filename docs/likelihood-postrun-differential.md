# Post-run exact likelihood differential

`scripts/verify_likelihood_postrun.py` is a standalone diagnostic verifier. Run it only after the service result confirms resource finalization and the controller has finished cleanup. It is not called by the JVM service and cannot change transition values, supply a runtime fallback, or turn a native-assisted run into an independent JVM run.

```
python3 -B scripts/verify_likelihood_postrun.py \
  --service-output /absolute/stopped-service \
  --generation-directory /absolute/stopped-service/jvm-likelihood \
  --native-config /absolute/native-config.json \
  --native-config-sha256 CONFIG_SHA256 \
  --output /absolute/fresh-differential-directory
```

The equivalent callable is `verify(service, generations, config_path, config_sha256, output)`. It returns the report and retains `result.json`. CLI exits zero only for an exact match; mismatches return one and failures remain failures. A duration-limit stop is not itself a transaction or ledger success claim. The caller remains responsible for the broader soak acceptance criteria.

The verifier requires the stopped `repeated-jvm-v1` result, finalized transport counts, bootstrap readiness, active owner pin, and every result-referenced publication original. It checks the original hashes, source join and initial manifest bindings, profile and owner, full pin/point fields, contiguous publication indexes/generations and block numbers, increasing slots, and final pin. Selected generation metadata must bind the repeated component's Frozen ID, application epoch, observed slot, exact request/output hashes, and computed word counts. Duplicate references to the same arithmetic retain identical captured metadata. These checks anchor the recorded Frozen metadata; the request does not contain the entire frozen state and cannot independently reconstruct its authority.

For every published generation, canonical request geometry, sorted full pool domain, exact echoed request, finite raw64/raw32 words, and the six-line JVM execution receipt are checked. A complete matching record is mandatory. Unreferenced complete records and partial unpublished drafts are listed separately and never promoted by directory number. Every actual native response must echo the exact captured request and full ordered pool domain. Comparison uses raw hexadecimal words with no numeric tolerance. All raw words are retained; mismatch totals cover every word. At most 256 example differences per generation are listed, with an explicit omitted count.

The pinned native configuration has exactly these fields:

```json
{
  "schema": "likelihood-postrun-native-config-v1",
  "image": "sha256:<immutable local Docker image ID>",
  "executable": {"path": "/absolute/regular/helper", "sha256": "<64 lowercase hex>"},
  "libraries": {
    "libblst.so": {"path": "/absolute/regular/libblst.so", "sha256": "<64 lowercase hex>"}
  },
  "sources": {
    "PoolRank.hs": {"path": "/absolute/regular/PoolRank.hs", "sha256": "<64 lowercase hex>"}
  },
  "timeoutSeconds": 10
}
```

Include the helper source, Cabal description, source-pin manifest, actual pinned ledger sources, and every custom loader library required by the executable. Loader aliases map to verified regular target files, not symlinks. Configuration, executable, library and source originals are hash checked. The verified executable/library bytes are copied into the private evidence directory and mounted read-only. System libraries are bound by the explicit image ID. These pins are provenance of a trusted configured native oracle; the script does not prove that an arbitrary binary was built from the supplied sources.

Each helper invocation is serial, in Docker with one CPU, two GiB memory/swap cap, no network, no capabilities, no new privileges, read-only root and mounts, no image pull, and a small temporary filesystem. Timeout is 1..30 seconds; at most 128 generation records and 512 publications are accepted. Publication originals are capped at 128 KiB and retained service originals at 16 MiB. Native stdout/stderr are monitored at 128 KiB/8 KiB with 20 ms polling; these are monitored limits, not hard disk quotas. The helper is trusted not to spawn detached descendants. Cleanup checks the exact owned container label and never touches unrelated containers; local Docker CLI termination/wait is unconditional even if inspection fails. Cleanup failures prevent a successful report. Filesystem and Docker control-plane operations retain cooperative liveness limits.

Evidence preserves original service metadata, each request and JVM result, native response, command, configuration and all explicit pins. Finite exact agreement proves only these compared generations. It does not establish universal floating-point equivalence, full ledger validation, restart authority, or productive repeated-epoch correctness outside the separately validated runtime profile. Public unit tests exercise malformed bindings and one-bit mismatches. A private offline smoke can wrap actual retained JVM originals in synthetic publication metadata to test native execution; that smoke must be labeled as synthetic and must not be reported as a live service acceptance.
