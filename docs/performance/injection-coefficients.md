<!-- SPDX-License-Identifier: Apache-2.0 -->
# Incremental injection numeric coefficients

Part of the [performance log](../performance.md).

## 43. Incremental injection numeric coefficients (#499)

On 2026-10-26, the unchanged four-seed fuzz run at `798daf559` completed locally in 2,342
seconds, including 1,786 seconds in the incremental injection reader. The CI job reached its
60-minute limit without completing this target or printing a counterexample. Its result is
incomplete: the retained output cannot distinguish aggregate cost from shrinking after a
per-input time-guard failure. No cases, seeds, mutation distribution, resource guards or workflow
limits were changed to address it.

A local thread sample identifies `java.math.BigInteger.destructiveMulAdd` under the reader's coefficient
conversion during ordinary property evaluation. The original native corpus includes a 262,145-digit
coefficient. Inspection of the installed ScalaCheck 1.19 bytecode confirmed that successful-case
Pretty wrappers are lazy; the sampled cost was parsing, not eager formatting of successful test
arguments.

The reader constructs large coefficients by splitting their decimal digits at the midpoint,
parsing leaves of at most 10,240 digits with the existing BigInt constructor, and combining
`left * 10^rightWidth + right`. The width includes leading zeros. Typed explicit frames and a
tail-recursive loop keep outside-data width stack safe; the power cache is immutable and local
to one conversion. Smaller inputs keep direct construction. No mutable or global cache is added.

On 2026-10-27, the CI re-run at `881ef506a` exceeded the unchanged two-second per-input limit
(2,012 ms) on a 281,554-byte input holding one numeric literal (`fuzz 100000 2 1 injection-json`).
Separate measurements of each step showed that most of the remaining allocation was not the
conversion: circe 0.14.16 `Json.fromBigInt` stores `JsonBiggerDecimal(BiggerDecimal.fromBigInteger(v),
v.toString)`, and rendering a 327,679-digit coefficient back to decimal allocated 274 MB in 62 ms
(223 MB for 281,550 digits). That rendering left no allocation headroom for smaller leaves. The
reader now returns `JsonValue`, Scarab's copy of aeson's `Value`, whose numbers are scientific's
coefficient and exponent pair (`Scientific`), so nothing renders the coefficient back to decimal.
The 274 MB of rendering is gone without a replacement, which leaves room for the smaller leaves.

This changes the exact Integer construction algorithm and the value type the reader returns. Native json-stream 0.4.6.1 `Data/JsonStream/CLexer.hs:129–172` constructs an
arbitrary Integer coefficient and then Scientific with a separate exponent; scientific 0.3.8.1
stores that pair without normalization. Grammar, signs, fraction width, wrapped exponent,
chunk-sensitive acceptance and native partial-number limits remain unchanged. The algorithm does
not impose a new digit limit. The returned `Scientific` holds the same coefficient and exponent the
reader computed before; equality compares normalized values, as scientific's `Eq` does.

Measurements used the same Docker JDK 21.0.11 image, a two-CPU quota, `-Xss8m` and a 12-GiB
container memory limit. Each row measures the unchanged target's complete parse and existing
resource checks, after earlier cases had initialized the JVM. These are individual observations
under comparable host load, not a throughput claim or an average across repeated fuzz runs.
Allocation is total allocated bytes, not live heap; MB is decimal. The first two value columns
were measured with `-XX:ActiveProcessorCount=2` at 163,840-digit leaves; the last uses the
container's two-CPU quota and 10,240-digit leaves with `JsonValue`, measured on 2026-10-27 while
four faults devnets ran on the same 24-core host.

<!-- register:table perf-49.t1 -->
| Coefficient digits and shape | Original target (ms / MB) | Midpoint target (ms / MB) | `JsonValue` target (ms / MB) | Existing allocation bound (MB) |
|---|---:|---:|---:|---:|
| 262,145, object value (original native coefficient) | 700 / 191.30 | 395 / 228.90 | 50 / 100.83 | 287.32 |
| 327,674, object value | 1,048 / 279.79 | 612 / 337.46 | 59 / 141.98 | 352.85 |
| 327,679, root number | 1,049 / 279.71 | 640 / 336.42 | 69 / 137.99 | 352.85 |
<!-- /register -->

The midpoint stage alone traded allocation for CPU time and left 3,029,584 bytes (0.86%) of
margin on a cold 327,679-digit root. With `JsonValue`, the cold 327,679-digit root measured
139,440,352 allocated bytes in 154 ms and the cold 327,674-digit object value 142,348,232 bytes in
143 ms, against the unchanged 352,845,824-byte bound and two-second limit. Among leaves of
163,840, 40,960, 10,240, 2,560 and 640 digits, conversion of 327,679 digits measured 493, 144, 55,
34 and 29 ms with 59, 113, 135, 146 and 148 MB; 10,240 digits takes most of the time reduction with
less allocation than the smaller leaves. The CI reproducer `fuzz 100000 2 1 injection-json`
completed its 100,000 cases in 33 seconds at two CPUs.

Uneven 2:1 and 3:1 splits increased allocation and time in the earlier bounded arithmetic
comparison. A zero-scale BigDecimal representation was rejected because its returned MathContext
differed from the original constructor. None of those alternatives changes the selected product
path.

The target uses 65,536-byte feeds. Its largest terminating coefficient is 327,679 digits at the
beginning of the input or 327,674 after the object-key prefix; signs and other number syntax consume
space within that bound. Another all-number chunk still encounters the reference's existing
partial-number refusal. This is distinct from the largest generated byte input: the largest corpus
seed is 327,686 bytes, and four existing duplication mutations reach 5,242,976 bytes. Other mutation
steps cannot exceed that bound because their additions are at most the largest seed or 200,000
bytes. The maximum byte-input control exercises the unchanged refusal/resource boundary, not
conversion of a five-million-digit coefficient. Larger single-chunk API inputs remain covered by
exact-value and stack-safety tests.

`InjectionJsonSuite` retains all 73 native traces and their complete coefficient/exponent events.
`InjectionDecimalSuite` checks the returned coefficient and exponent field by field: decimal-width
boundaries, signs, zeros, leading zeros and fractions, exponents at the ends of the machine Int,
wrapped exponents and small-stack traversal, and 14 integers from zero past `Long` to 327,674
digits, each its own coefficient with exponent zero.

`InjectionDecimalGuardSuite` runs the original target's unchanged guards over every native seed,
width boundaries, cold maximum coefficients and the maximum generated byte size. Additional
repeated-coefficient semantic tests verify that every array element is constructed; they do not
claim arbitrary aggregate resource conformance. The original parser itself exceeded the two-second
limit on a separate four-coefficient aggregate stress input. Focused results do not replace the
mandatory complete four-seed fuzz run or CI.
