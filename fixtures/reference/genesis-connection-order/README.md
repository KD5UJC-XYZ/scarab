# Native TCP connection ordering

These eight observations call the installed reference instances of
`Ord (Ouroboros.Network.ConnectionId.ConnectionId Network.Socket.SockAddr)` directly.
Every input, sorted identity and pairwise comparison is retained. Cases distinguish remote versus
local priority, IPv4 port versus raw host-word priority, every IPv6 field, address-family order,
duplicates and byte conversion. The observer also opens a private IPv6 loopback TCP connection and
retains both endpoints returned by `getSocketName` and `getPeerName`, including flow information,
scope and actual ports. Its ephemeral client port is an observed input and can change on refresh.

The native instance orders the remote endpoint before the local endpoint. Socket fields order as
IPv4 port then host word, or IPv6 port, flow information, four address words and scope. The recorded
byte roundtrips preserve the native raw word representation; they do not infer it from textual IPs.
Both endpoints of the actual IPv6 TCP pair report zero flow information and zero scope.

The manifest records the immutable image, exact native package unit identifiers and package
metadata, static-library digests, observer/exporter digests, executable digest and corpus digest.
The observer links directly to those already installed native units, without resolving a different
package graph or copying a comparison implementation. `ouroboros-network` is 1.2.0.0 and `network`
is 3.2.8.0 in this reference image.

Regenerate inside an isolated Docker container:

```sh
python3 scripts/oracle/genesis-connection-order/export.py \
  --probe-dir /tmp/scarab/genesis-connection-order \
  --output-dir fixtures/reference/genesis-connection-order
```

The observed upstream types retain the network [license](../../licenses/block-fetch-network-LICENSE)
and [notice](../../licenses/block-fetch-network-NOTICE).
