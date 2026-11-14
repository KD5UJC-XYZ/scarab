# Licence of the musl port

`MuslMath` and `MuslData` (`ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/rules`) are a port of musl's
double-precision `log`, `pow` and `exp` and their tables (`src/math/log.c`, `pow.c`, `exp.c`, `log_data.c`,
`pow_data.c` and `exp_data.c`), which come from Arm's optimized-routines (Copyright (c) 2018, Arm Limited) and
are MIT-licensed, as musl as a whole is. This text is copied unchanged from the pinned upstream source:

| File | Source |
|---|---|
| `LICENSE-musl` | `COPYRIGHT` of the musl 1.2.5 release tarball (`musl-1.2.5.tar.gz`, SHA-256 `a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4`, signed by the musl release key `836489290BB6B70F99FFDA0556BCDB593020450F`) |

The MIT licence asks that its notice go with every copy, so the assembly JAR carries this file as
`META-INF/scarab/licenses/ledger/LICENSE-musl`. Why the port exists, and how it was matched with the reference
node, is in [`fixtures/reference/release-libm`](../../fixtures/reference/release-libm/README.md).
