### Changed (JSON, #497)

- The strict native JSON reader returns Scarab's own `JsonValue`, a copy of aeson's `Value`, instead of circe's `Json`. Numbers keep the reference's `Scientific` coefficient and exponent as parsed and compare after normalization, as `Scientific` does; object keys keep aeson's `Text` order. Equality, hashing and rendering walk an explicit stack, so a deeply nested value cannot overflow it.
