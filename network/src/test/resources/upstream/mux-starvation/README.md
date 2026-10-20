# Native Mux starvation generator observations

Status: bounded native generator/property replay under [#49](https://github.com/KD5UJC-XYZ/scarab/issues/49)
and [#258](https://github.com/KD5UJC-XYZ/scarab/issues/258), not full scheduler or transport equivalence.

The corpus retains 32 consecutive original `Arbitrary Uneven` samples from ouroboros-network
`2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`, `network-mux/test/Test/Mux.hs:318–343`.
Seeds 512001–512032 and QuickCheck sizes 30–40 (`30 + ordinal mod 11`) are not filtered or resized.
Every sample is submitted to the original `prop_mux_starvation` at lines 1002–1142:
**22 native passes, ten implication discards (`GaveUp`), zero failures**. Original receive-header
prefixes are retained for all 22 executed conversations; each contains both protocols 2/3.

The [exporter](../../../../../../scripts/oracle/network-mux/starvation.py) archives the committed
source into a disposable directory and builds the original `network-mux:test:test` offline in Docker.
The observer wrapper and appended exporter in [observer.patch](observer.patch) leave the original
Uneven generator, property precondition, client/server wiring, serialization, acceptance and
normalization unchanged. The callback records the actual first 100 receive-header protocol numbers
used by the property. The manifest binds the original/patched source, patch, appended Haskell source,
full original project and dependency plan, executable, image, raw native export and normalized corpus.
Its plan SHA256 is the same as the prior full-project lifecycle build. Only the disposable source is
patched. The original code and this adapter are Apache-2.0.

## Exact scope and upstream quirks

The pinned property passes **response1 to both protocols**; response0 only participates in the
`> 2*1280` precondition. This apparent upstream typo is preserved, not silently repaired. Both original
generated responses and their native ReqResp CBOR encodings are retained. `UpstreamMuxStarvationSuite`
replays all 22 passing cases using the original response1/response1 bytes, 1280-byte SDUs,
queue capacity 10 and an explicit mock profile with 3 MB message and ingress bounds. The default
public Mux message bound remains 2500000 bytes.
Requests, responses and Done are compared byte-for-byte through real production Mux channels.

Four native passes have responses above the default profile. The original TSV retains their
`unsupported-message-limit` classification as historical export metadata, but the explicit mock
profile now executes them unchanged. Their original inputs, hashes, traces and native judgments
remain intact. Ten native precondition discards remain discards. This generic mock workload does
not advertise larger Cardano protocol messages.

Twelve separately labelled supplemental conversations use the original unequal response0/response1
pairs where both fit. They apply the same ordering law and exact bytes, but their verdicts are **local
controls**, not twelve more native property invocations or a correction to the upstream oracle.

The ordering port takes 100 headers, removes the initial protocol run, collapses the next initial run
and final run, then checks differing adjacent protocol numbers. The original normalization can pass
vacuously on wholly serial traffic. Scarab additionally gates the first write until both responses
have been admitted, and separately requires the normalized overlap to contain both protocols.
An interior-failure control and an explicitly vacuous serial trace check guard the port and this
extra observation. We compare the ordering assertion, not exact IO/thread schedules or raw trace
identity. No broader generator campaign or new transport-backend parity is claimed.

## Storage and schema

`genLargeByteString` repeats an original <=1024-byte random chunk. The exporter validates that the
complete native payload is exactly reconstructed before storing this lossless recipe. Full payload
and encoded-message SHA256 values are computed from the original native export; the Scala adapter
checks them whenever it materializes a response. This keeps the fixture 128 KB without shortening
multi-megabyte inputs. Native full hex remains outside Git, bound by `rawExportSha256`.

The 18 TSV columns are schema, seed, ordinal, QuickCheck size; response0 length/chunk hex/payload
SHA256; response1 length/chunk hex/payload SHA256; request CBOR hex; response0 CBOR prefix;
response1 CBOR prefix; response0 encoded SHA256; response1 encoded SHA256; original first 100
received protocol numbers; native verdict; local-domain disposition. Prefix concatenated with the
reconstructed payload yields the exact native encoded response.

Reproduce with a fresh probe directory and the pinned source/cache image:

```sh
python3 scripts/oracle/network-mux/starvation.py \
  --source /path/to/pinned/ouroboros-network \
  --probe-dir /tmp/scarab/mux-starvation-fresh \
  --output-dir network/src/test/resources/upstream/mux-starvation \
  --cache-build /path/to/previous/network-mux/builddir
python3 -m unittest discover -s scripts/oracle/network-mux -p 'test_starvation.py'
```

The recorded native execution is a real IO run; scheduling may change an independently reproduced
header prefix. The generator inputs remain deterministic. The exporter removes its container on
success and failure. Runtime cleanup and original-message behavior are covered separately by the
lifecycle corpus and local scheduler suites.
