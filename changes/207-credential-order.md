### Changed (credential ordering, #207)

- The order of `Credential[R]` is a stable `SortOrder` instance built for each role, instead of one upcast instance widened to every role. The comparison (script before key, then the hash bytes) is unchanged; the per-role instance restores the `ordering` identity that `TreeMap.from`'s linear copy and the epoch-boundary tests rely on.
