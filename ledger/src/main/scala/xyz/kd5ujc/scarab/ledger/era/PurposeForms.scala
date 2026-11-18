// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

/** The forms a Plutus purpose takes (cardano-ledger f649f975
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Scripts.hs:281-302`): the index of the item a
  * redeemer names (`AsIx`), the item itself (`AsItem`), or both (`AsIxItem`). A purpose is
  * `PlutusPurpose[F, E]` for one form `F`, so a redeemer's key and a script context's purpose are
  * different types.
  */
final case class AsIx[I, A](ix: I)
final case class AsItem[I, A](item: A)
final case class AsIxItem[I, A](ix: I, item: A)

object AsIxItem:
  /** `toAsIx` (`Alonzo/Scripts.hs:329-330`, cardano-ledger f649f975): the index alone. */
  def toAsIx[I, A](asIxItem: AsIxItem[I, A]): AsIx[I, A] = AsIx(asIxItem.ix)
