# Licence of curve25519-elisabeth

The Edwards-curve arithmetic behind `StrictEd25519`, `ByronEd25519` and `Draft03Prover` is the source of
curve25519-elisabeth, vendored in `crypto/src/main/java/com/weavechain` from weavechain/curve25519-elisabeth at
`81bd79dbd0b323e5fc5729bca883d124b95b4e54`, the revision published as `com.weavechain:curve25519-elisabeth:0.1.3`
(#1418). `Draft03Prover` multiplies points by its secret scalars with the scalar multiplications the library
documents as constant-time (`EdwardsBasepointTable.multiply`, `EdwardsPoint.multiply`); the two verifiers use its
variable-time double-scalar multiplication (`vartimeDoubleScalarMultiplyBasepoint`), whose inputs are public. This text is its licence, copied unchanged from that source:

| File | Source |
|---|---|
| `LICENSE-curve25519-elisabeth` | `LICENSE` of weavechain/curve25519-elisabeth at `81bd79dbd0b323e5fc5729bca883d124b95b4e54` (git blob `685a9b1291cddff06d38ab3d8520633822acb3fd`, the same blob as cryptography-cafe/curve25519-elisabeth at `26c014bda6cf87498b9180e8b2ec8024fdb95e80`) |

It holds three notices: the MIT licence of curve25519-elisabeth (Jack Grigg, 2019); the statement
that portions derive from eddsa-java (str4d/ed25519-java, CC0 1.0); and the BSD-3-Clause licence of
curve25519-dalek, from which `UnpackedScalar` was ported. The dalek text is the first 28 lines of the
`LICENSE` of dalek-cryptography/curve25519-dalek at tag `1.0.3` (`9cd4551b40a7328eed18717b527bc8ba9331fa27`),
the release current when the port was made. MIT asks that the notice go with every copy, and BSD-3-Clause that
it go with any binary distribution, which is why the file is kept here beside the module that holds the source
and why the assembly JAR carries it as `META-INF/scarab/licenses/crypto/LICENSE-curve25519-elisabeth`.

`fixtures/witness/licenses/elisabeth-LICENSE` is the same file, kept with the Ed25519 witness
corpus. The provenance of the source is in [docs/licenses.md](../../docs/licenses.md#curve25519-elisabeth-provenance).
