# Dynamic diagnostic likelihood dependency

This is an explicit local native validation dependency for a restricted epoch research
profile. It is not an independent Scala consensus claim and does not enable existing
epoch or nonempty-go runtime guards by itself.

`ConwayNativeLikelihood.request` consumes a genuine captured `ConwayEpochBoundary.Frozen`
and its exact identity. For every registered go pool, including zero-stake pools, it
uses go pool coin / (maxSupply - reserves), previous block count (missing means zero),
and the checked frozen parameter/global projections. It never uses normalized pool
leadership share or post-boundary reserves in place of captured pre-tick calculation
inputs. Geometry is explicitly f=1/20, d=0, epoch size 1000, at most 64 pools and each
count at most 1000; stake/circulation values remain exact bounded integers.

The ASCII request is `conway-native-likelihood-v1`, frozen ID, `1000 1 20 0 1`, then
sorted rows `poolHex stake circulation blocks`, each on its own newline-terminated line.
The native helper takes one request-file argument, reads at most 16385 bytes and refuses
oversize input. It calls pinned native `leaderProbability` and `likelihood` directly,
then echoes the exact request followed by `--native--` and sorted rows containing pool
hex, the 16-digit probability raw word, and 100 concatenated 8-digit Float raw words.
Echo equality binds the response; it does not authenticate who produced it.

`NativeLikelihoodOracle.resource` therefore explicitly trusts a configured executable
SHA-256 and trusted local filesystem/toolchain environment. It checks executable size
and hash before resource use and each launch. Operators must also pin native source,
dynamic libraries and image through the recorded build/runtime manifest and immutable
mounts; a binary hash alone is not a sandbox or dynamic-library authentication. The
helper sources and synthetic golden are public; actual binaries/caches/logs stay private.

Default `CheckedJvm` computes probability and every stored Float word on the JVM, compares
all raw words exactly to the native reply, and returns JVM values only if every comparison
matches. There is no tolerance, normalized comparison, or silent substitution. Explicit
`AssistedNative` instead returns native words and marks `nativeValuesAuthoritative=true`.
Both depend on native validation; only the former uses independently computed JVM values.
Receipts record mode, binary/input/output hashes and raw32/raw64 comparison/mismatch counts,
including before a CheckedJvm mismatch refusal. Invalid output does not become a capability.

`Generated.forFrozen` requires the exact same frozen object and ID. The coordinator must
retain that object through the effectful call, then verify its whole unpublished candidate
under the existing publication fence. A result cannot establish freshness of a branch or
authorize an epoch crossing. Late successor freezes must retain pre-tick calculation
inputs and their separate successor application binding. Native generation does not
alter monetary pulser timing, reward application ordering, or replace frozen history.

Each oracle serializes calls and permits at most 128 invocations. Process wait is bounded
to at most 30 seconds (default 5); cancellation/timeout kills and reaps the owned child.
Resource close rejects new calls and awaits the bounded in-flight call through the gate;
it does not immediately cancel an independently running caller. Filesystem/hash I/O has
cooperative cancellation. Output/stderr size checks are polled, not hard disk-write caps.
Configured helpers must be trusted and must not spawn descendants. Request/response/error
files are retained in a fresh evidence directory, never interpreted as transaction ingress.

Actual offline native execution used GHC9.6.7/-O0, the frozen 235-unit closure, network none,
2 CPUs/2 GiB and a fresh private cache. Ten supplied arithmetic cases include the prior
eight fractions/counts, changed circulation with count 7, and a zero-stake pool. All 1000
raw32 and 10 raw64 comparisons matched. These are finite observations, not universal
pow/log equivalence. A separate retained test invokes the actual pinned native binary
through the app adapter for three genuine synthetic Frozen values.
