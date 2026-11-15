### Changed (build, #201)

- The compiler refuses more, as errors under `-Werror`: a type argument inferred as a union (`-Winfer-union`),
  so that a union of error types is declared where it is meant; an unused implicit parameter; a name that
  shadows another; a comment that an enum's cases share and the compiler drops; an unused `@nowarn`; a pattern
  comparison that looks as if it always fails; a method that calls itself with a default argument; and `=>`
  where a context function literal `?=>` was meant.
- WartRemover also refuses, in the main sources of every module but `tools`: `==` on arrays,
  `scala.Enumeration`, a descendant of a sealed type that is neither final nor sealed, a lock on a value-based
  class, a String built with `+` from a value that is not one, an inferred `Product`, a conversion to the type a
  value already has, and a size compared where `sizeIs` would do. `DisallowedWartsSuite` shows that each wart
  the build names fires on a probe, so a wart that loads but finds nothing cannot pass unnoticed; `ThreadSleep`
  is the exception, since `scripts/check_test_sleeps.py` would count its probe as a sleep.
- No behaviour changes. Each site is fixed with a type annotation, a rename, the removal of an implicit parameter
  nothing reads or of a conversion to the type a value already has, `sizeIs` for `size`, an explicit
  `toString`, or a comment's form; three `toSet` calls on sorted sets are written `Set.from`, which is what
  `toSet` calls.
