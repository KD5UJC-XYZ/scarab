# Licence of curve25519-elisabeth

The jar of `com.weavechain:curve25519-elisabeth:0.1.3` (the constant-time Edwards arithmetic behind
`StrictEd25519`, `ByronEd25519` and `Draft03Prover`) carries no licence file. This text is copied
unchanged from its source:

| File | Source |
|---|---|
| `LICENSE-curve25519-elisabeth` | `LICENSE` of weavechain/curve25519-elisabeth at `81bd79dbd0b323e5fc5729bca883d124b95b4e54` (git blob `685a9b1291cddff06d38ab3d8520633822acb3fd`, the same blob as cryptography-cafe/curve25519-elisabeth at `26c014bda6cf87498b9180e8b2ec8024fdb95e80`) |

It holds three notices: the MIT licence of curve25519-elisabeth (Jack Grigg, 2019); the statement
that portions derive from eddsa-java (str4d/ed25519-java, CC0 1.0); and the BSD-3-Clause licence of
curve25519-dalek, from which `UnpackedScalar` was ported. The dalek text is the first 28 lines of the
`LICENSE` of dalek-cryptography/curve25519-dalek at tag `1.0.3` (`9cd4551b40a7328eed18717b527bc8ba9331fa27`),
the release current when the port was made. BSD-3-Clause requires that notice in any binary
distribution, which is why the file is kept here beside the module that uses the library and why
the assembly JAR carries it as `META-INF/scarab/licenses/crypto/LICENSE-curve25519-elisabeth`.

`fixtures/witness/licenses/elisabeth-LICENSE` is the same file, kept with the Ed25519 witness
corpus. How the source and the jar were matched is in [docs/licenses.md](../../docs/licenses.md#curve25519-elisabeth-provenance).
