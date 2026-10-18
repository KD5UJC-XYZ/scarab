# Private Leios reference wire captures

Public throwaway-cluster data, captured 2026-10-18. No signing keys, private hosts or run logs.
The node/build/configuration pins and protocol differences are in
[docs/leios.md](../../../docs/leios.md#12-pinned-private-leios-reference-builds-2026-10-18).

Each `messages.tsv` has three columns: mini-protocol number, message kind, and the exact message
bytes in hex. It retains the first received announcement, block offer, transaction offer, vote,
endorser block and transaction reply of that profile's initiator run. These are replies generated
by the pinned Haskell node, not re-encodings from Scala. The input chain and transaction load come
from the upstream proto-devnet's public throwaway keys. Files are ASCII TSV; checksums cover the
whole file, including its comment and newline.

Regenerate using the Docker-only build and `leios-probe --profile all --capture <outside-checkout>`
in section 12.3, then select the six server message kinds from each `messages.tsv`. Profiles run
sequentially on an internal Docker network. The corpus is generated local test data under this
project's Apache-2.0 licence; upstream node/consensus/network implementations and public proto-devnet
configuration are Apache-2.0 (their upstream notices are retained under `fixtures/licenses`).
Binaries are downloaded and verified by the image-preparation tool, never redistributed here.

`LeiosReferenceCaptureSuite` preserves both successful decodes and the measured refusals. Its
renumbering check changes only the old message's tag and is a diagnostic, not an interoperable
adapter. In particular, an old vote can silently decode as a Blueprint announcement; the newer
transaction reply is intentionally refused by the current three-field codec (#174).

`manifest.json` records file hashes and exact build pins. The current codec is still the Blueprint
layout; the remaining production follower/mux/committee work is tracked in #73.
