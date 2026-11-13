// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

/** A de Bruijn index, the reference's `DeBruijn` (a newtype over `Index`, itself a `Word64`,
  * `PlutusCore/DeBruijn/Internal.hs:102,141`, plutus 1.70.0.0 `507cab8c`). Index 1 names the
  * innermost enclosing `lam`; the binder itself carries none (its Flat encoding is empty and
  * decodes as `deBruijnInitIndex`, 0, `PlutusCore/FlatInstances.hs:375-378`), so 0 is never in
  * scope.
  */
opaque type DeBruijn = Word64

object DeBruijn:
  def apply(index: Word64): DeBruijn = index

  /** `index` when it is not negative. */
  def fromInt(index: Int): Option[DeBruijn] = Word64.fromInt(index)

  extension (d: DeBruijn) def index: Word64 = d

  given Ordering[DeBruijn] = summon[Ordering[Word64]]
