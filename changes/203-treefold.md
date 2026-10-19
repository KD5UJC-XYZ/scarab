### Changed (codec tree folding, #203)

- `TreeFold` keeps a node's results in a typed `ArrayBuffer[A]` instead of an erased `Array[AnyRef]`, and `flatten` writes its output through a typed buffer, so the five `asInstanceOf` sites are gone and `flatten[A]` keeps its generic signature. Traversal order and allocation shape are unchanged.
