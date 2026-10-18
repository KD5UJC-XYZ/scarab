# Pinned Conway ENACT conformance sample

This fixture is one generated case from the exact `Conformance/Conway/Constrained Generators/Ticks transition graph/ENACT/` property. Both upstream implementation and Agda accept it. It retains context epoch, original Haskell environment (`()`), full EnactState, exact action signal, implementation poststate and result.

The Scala adapter maps the typed action to `Enact`, which applies the existing `Ratify.enact` semantics, and compares the entire typed EnactState. This proves one actual action, not the complete ENACT property family.
