# Native Mux lifecycle observations

`lifecycle.tsv` retains 24 close experiments and 24 trailing-byte experiments from
ouroboros-network `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`,
`network-mux/test/Test/Mux.hs`. Every experiment passed its original IO and IOSim
property: 96 native property observations. This is bounded coverage, not a full
Mux-suite or scheduling-equivalence claim.

The [exporter](../../../../../../scripts/oracle/network-mux/lifecycle.py) copies the
committed upstream source into a disposable directory, checks its original SHA-256,
and builds the original `network-mux:test:test` target offline in Docker. The
retained [observer patch](observer.patch) adds an exporter and observes the actual
client result after both sides join. The original `close_experiment` body and
acceptance branches are unchanged apart from the observer callback; ordinary
property calls install a no-op callback. The adjacent manifest binds the patch,
executed sources, original project, Cabal plan, executable, image and output.
The original source and property assertions are Apache-2.0, as is this repository.

Seeds are 511001–511024 and QuickCheck sizes cycle through 8–16. Original arbitrary
integer lists, accumulators, positive SDU sizes and byte strings are retained.
All three original fault constructors occur eight times each; there are three
empty and 21 nonempty request lists. The bounded lawful request function is
`(accumulator + request, accumulator - request)`. This is an explicit choice of
function, not a claim to cover all generated functions. `CloseOnWrite` exits before
the final request; `CloseOnRead` exits after its final response. The rows retain
the actual IOSim client response prefix, clean/early outcome and original native
request/response CBOR. Each same-input IO property independently checks the native
response prefix and permitted connection endings. IO scheduler-specific exception
text is not compared with Scala typed errors.

Close columns are schema, seed, ordinal, size, fault, SDU size, comma-separated
original integer requests, initial accumulator, observed clean/early result,
observed integer responses, request CBOR, response CBOR, IOSim verdict, IO verdict.
Trailing-byte columns are schema, seed, ordinal, size, original trailing bytes,
original newly received bytes, expected ordered bytes, IOSim verdict, IO verdict.
Empty fields preserve empty lists or byte strings.

`UpstreamMuxLifecycleSuite` replays the native request/response bytes through real
Scarab Mux channels with the original SDU sizes. It compares complete observed
response prefixes and closure, and tests that ending and resuming a consumer
preserves buffered trailing bytes before new input. Scarab explicitly flushes its
final Done message before closing. This strengthens its local successful-shutdown
observation; the upstream property also permits some connection-level server
endings after correct responses. The shared functional contract does not require
identical shutdown exception constructors or thread schedules.

Additional controlled local tests exercise blocked send/flush/receive release,
first typed failure retention, reader/writer finalizers and partial-receive
cancellation. These are local cleanup controls, not falsely labelled upstream
property invocations. Partial-receive scheduling uses virtual time.

The exporter also executes the original start/stop and restart properties (24
cases each) and the two original pure-exception properties (one case each).
These remain native evidence only. Scarab owns protocol tasks through resources
and fibers and has no equivalent of Haskell's `runMiniProtocol` activation API;
introducing that API solely to duplicate internal activation tests is outside
functional equivalence. Exact activation scheduling, starvation, broader transport
backends, timer laws and other remaining coverage stay tracked by
[#49](https://github.com/KD5UJC-XYZ/scarab/issues/49).

Reproduce with a pinned source checkout, the locally built oracle dependency image
and an optional previous network-mux build cache:

```sh
python3 scripts/oracle/network-mux/lifecycle.py \
  --source /path/to/pinned/ouroboros-network \
  --probe-dir /tmp/scarab/mux-lifecycle-fresh \
  --output-dir network/src/test/resources/upstream/mux-lifecycle \
  --cache-build /path/to/previous/network-mux/builddir
```

The probe directory must be fresh. Original inputs are read-only; the exporter
removes its own container on success and failure.
