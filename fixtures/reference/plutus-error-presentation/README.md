# Native diagnostic observations

The dated `manifest.json` binds every input, complete native output, observer source, frozen plan,
executable, immutable image and toolchain. The native observer alone generates expected text.
`requests.json` contains constructor/helper controls, actual native evaluation requests and compile
requests. `observations.json` preserves whole Show, Pretty, custom-width layoutPretty/layoutSmart
and readable-term results where applicable. Decimal strings preserve native integer/index bounds.
`builtin-names.json` is the native Bounded/Enum catalog, rather than a Scala provider enumeration.

All five outer EvaluationError constructors and all nested machine/user/codec/free-variable cases
have direct native controls. Runtime cases include budget failure, force/application failures,
actual unlifting failures, builtin failures, case/branch errors and invalid return values across
all historical legal profiles present in the costing corpus. Direct helpers additionally expose
otherwise unavailable data-case and bypassed-type-error payloads. Constructed errors establish
printer behavior; they do not assert that the corresponding error is reachable in a transaction.

The files in `programs/` preserve the original 39-program snapshot and a noncolliding, versioned
delta. Each contains complete input definitions or native compiled output bytes. They are immutable
source material for signed recording provenance; later additions use a new filename and identities.

`unicode-isprint.json` records the complete official-compiler-source/API-compiler comparison and
printable ranges. Its sources include the original GHC Bits helper; the reproduction procedure is
in the [observer documentation](../../../scripts/oracle/plutus/error-presentation/README.md).

This corpus is API evidence. Official `LocalTxSubmission` and debug-query recordings are separate
native wire evidence. In particular, API decode failures for malformed Flat bytes may contain
allocation-dependent pointer text. Those observations cannot become deterministic byte-for-byte
goldens through normalization. Babbage and Conway reject malformed witnesses/reference outputs at
an earlier guard; Alonzo has a distinct call chain and must be checked independently. No absence
of reachability, exemption or complete wire parity is inferred from this API corpus.
