### Changed (#497)

- The strict raw JSON parser's refusals now render aeson 2.2.5.0's exact message texts as closed
  typed cases, with one invalid-UTF-8 case where three stood, and trailing input is a separate step
  after the parse, as `eitherDecodeStrict` orders it. `JsonValue` gains the reference's `Ord` and
  `Show`, `Scientific` its `Ord` and `Show`, and keys and strings are `Text`-like values that,
  as `T.pack` does, replace an unpaired surrogate in a hand-built string with U+FFFD.
