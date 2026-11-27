// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{TxIn, Unsigned}
import xyz.kd5ujc.scarab.ledger.state.{AccountAddress, EraTag}

/** An Alonzo Plutus purpose in the form `F`, of the eras Alonzo and Babbage (`data
  * AlonzoPlutusPurpose f era`, cardano-ledger f649f975
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Scripts.hs:332-337`; `type PlutusPurpose BabbageEra =
  * AlonzoPlutusPurpose`, `eras/babbage/impl/src/Cardano/Ledger/Babbage/Scripts.hs:61`): what a
  * script is run for, the item a certifying purpose names being the era's certificate. The index is
  * a `Word32`. Only the purpose a redeemer is keyed by and a pointer's inverse are here; the
  * class's builders and readers come with Babbage's Plutus side (#85).
  */
enum AlonzoPlutusPurpose[F[_, _], E <: EraTag]:
  case AlonzoSpending(purpose: F[Unsigned[Unsigned.Width.W32], TxIn])
  case AlonzoMinting(purpose: F[Unsigned[Unsigned.Width.W32], Bytes])
  case AlonzoCertifying(purpose: F[Unsigned[Unsigned.Width.W32], TxCert[E]])
  case AlonzoWithdrawing(purpose: F[Unsigned[Unsigned.Width.W32], AccountAddress])

  /** The redeemer tag of the purpose: spend 0, mint 1, cert 2 and reward 3 (`EncCBORGroup
    * (AlonzoPlutusPurpose f era)`, `Alonzo/Scripts.hs:375-381`, cardano-ledger f649f975).
    */
  def tag: Int = this match
    case AlonzoSpending(_)    => 0
    case AlonzoMinting(_)     => 1
    case AlonzoCertifying(_)  => 2
    case AlonzoWithdrawing(_) => 3

object AlonzoPlutusPurpose:
  private type Ix = Unsigned[Unsigned.Width.W32]

  /** The derived `Ord (AlonzoPlutusPurpose AsIx era)` (`Alonzo/Scripts.hs:341`, cardano-ledger
    * f649f975): the constructor, then the index. A redeemer's key.
    */
  given ordering[E <: EraTag]: Ordering[AlonzoPlutusPurpose[AsIx, E]] = (a, b) =>
    val tags = Integer.compare(a.tag, b.tag)
    if tags != 0 then tags else a.ix.value.compare(b.ix.value)

  given canEqual[E <: EraTag]
      : CanEqual[AlonzoPlutusPurpose[AsIx, E], AlonzoPlutusPurpose[AsIx, E]] =
    CanEqual.derived

  extension [E <: EraTag](p: AlonzoPlutusPurpose[AsIx, E])
    /** The index of the item the purpose names. */
    def ix: Ix = p match
      case AlonzoSpending(AsIx(i))    => i
      case AlonzoMinting(AsIx(i))     => i
      case AlonzoCertifying(AsIx(i))  => i
      case AlonzoWithdrawing(AsIx(i)) => i

  /** The purpose a redeemer's key's tag names, of its index, when the tag is one of Alonzo's (0 to
    * 3, `decCBORGroup` of `AlonzoPlutusPurpose`, `Alonzo/Scripts.hs:383-397`, cardano-ledger
    * f649f975): a decoder reads the index only after the tag.
    */
  def ofTag[E <: EraTag](tag: Int): Option[Ix => AlonzoPlutusPurpose[AsIx, E]] = tag match
    case 0 => Some(i => AlonzoSpending(AsIx(i)))
    case 1 => Some(i => AlonzoMinting(AsIx(i)))
    case 2 => Some(i => AlonzoCertifying(AsIx(i)))
    case 3 => Some(i => AlonzoWithdrawing(AsIx(i)))
    case _ => None

  /** `alonzoRedeemerPointerInverse`
    * (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxBody.hs:531-544`, cardano-ledger f649f975): the
    * item at the pointer's index among the inputs, the policies minted (in their order), the
    * certificates or the withdrawals' accounts (in theirs).
    */
  def alonzoRedeemerPointerInverse[E <: EraTag, L <: TxLevel](
      txBody: TxBody[L, E],
      ptr: AlonzoPlutusPurpose[AsIx, E]
  )(using b: MaryEraTxBody[E], l: b.Level[L]): Option[AlonzoPlutusPurpose[AsIxItem, E]] =
    ptr match
      case AlonzoSpending(idx) =>
        Indexable.fromIndexSet(idx, b.inputs(txBody)).map(AlonzoSpending(_))
      case AlonzoMinting(idx) =>
        Indexable
          .fromIndexSet(idx, scala.collection.immutable.SortedSet.from(b.minted(txBody)))
          .map(AlonzoMinting(_))
      case AlonzoCertifying(idx) =>
        Indexable.fromIndexSeq(idx, b.certs(txBody)).map(AlonzoCertifying(_))
      case AlonzoWithdrawing(idx) =>
        Indexable
          .fromIndexMap(idx, b.withdrawals(txBody).unWithdrawals)
          .map(AlonzoWithdrawing(_))
