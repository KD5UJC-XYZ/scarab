# Native LocalStateQuery capture

**Status: Current** — focused reference-fixture tooling for the Conway wallet and peer queries.
It calls the running native server through the existing LocalStateQuery client; it contains no
reward or peer-selection implementation.

Against an existing **owned** reference cluster:

```sh
python3 scripts/oracle/ntc-queries/capture.py --container my-reference-ref \
  --out /tmp/scarab/my-query-capture
```

The default socket is `/work/env/socket/node1/sock` and network magic is `1082026`; both have
explicit overrides. One point is acquired for all requests, including versioned sessions. Original
requests, full replies, unwrapped values and hashes are retained. This command is read-only and
captures no keys; preserve the cluster's original public genesis/configuration separately.

A self-contained peer fixture with real relay registrations:

```sh
python3 scripts/oracle/ntc-queries/peers.py --name my-query-peers \
  --out /tmp/scarab/my-peer-fixture
```

This creates a fresh reference-only cluster, changes only its generated pools' relay input before
startup, captures IPv4/DNS/IPv6/SRV results and removes its own cluster in guaranteed cleanup.
The output records the changed input and original hashes, safe genesis whitelist, original native
answers, runtime image identity and version output. Choose a unique name and output directory;
coordinate the repository's limit of two simultaneous devnets. It requires the prepared reference
image from [reference-image.md](../../../docs/reference-image.md), not a host JDK or Haskell compiler.

A new recording is a fixture candidate requiring review against the old bytes and the pinned
source. Do not overwrite expected replies as a way to make a comparison pass.
