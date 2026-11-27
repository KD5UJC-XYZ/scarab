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
  * a `Word32`. The script class's members of an era whose purpose this is are the functions of the
  * companion, which Babbage's instance gives ([[babbage.BabbageScript]]).
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

  /** `hoistPlutusPurpose` of Babbage's instance (`Babbage/Scripts.hs:74-78`, cardano-ledger
    * f649f975; Alonzo's, `Alonzo/Scripts.hs:580-584`, is the same): the purpose with its item's
    * form changed by `f`.
    */
  def hoistPlutusPurpose[G[_, _], F[_, _], E <: EraTag](f: [ix, it] => G[ix, it] => F[ix, it])(
      purpose: AlonzoPlutusPurpose[G, E]
  ): AlonzoPlutusPurpose[F, E] = purpose match
    case AlonzoSpending(x)    => AlonzoSpending(f(x))
    case AlonzoMinting(x)     => AlonzoMinting(f(x))
    case AlonzoCertifying(x)  => AlonzoCertifying(f(x))
    case AlonzoWithdrawing(x) => AlonzoWithdrawing(f(x))

  // The readers of Babbage's instance (`toSpendingPurpose` to `toWithdrawingPurpose`,
  // `Babbage/Scripts.hs:80-98`, cardano-ledger f649f975; Alonzo's, `Alonzo/Scripts.hs:586-604`,
  // are the same): the item of the reader's own purpose, none for another. The builders are the
  // constructors.

  /** `toSpendingPurpose`. */
  def toSpending[F[_, _], E <: EraTag](
      purpose: AlonzoPlutusPurpose[F, E]
  ): Option[F[Ix, TxIn]] = purpose match
    case AlonzoSpending(i)                                             => Some(i)
    case AlonzoMinting(_) | AlonzoCertifying(_) | AlonzoWithdrawing(_) => None

  /** `toMintingPurpose`. */
  def toMinting[F[_, _], E <: EraTag](
      purpose: AlonzoPlutusPurpose[F, E]
  ): Option[F[Ix, Bytes]] = purpose match
    case AlonzoMinting(i)                                               => Some(i)
    case AlonzoSpending(_) | AlonzoCertifying(_) | AlonzoWithdrawing(_) => None

  /** `toCertifyingPurpose`. */
  def toCertifying[F[_, _], E <: EraTag](
      purpose: AlonzoPlutusPurpose[F, E]
  ): Option[F[Ix, TxCert[E]]] = purpose match
    case AlonzoCertifying(i)                                         => Some(i)
    case AlonzoSpending(_) | AlonzoMinting(_) | AlonzoWithdrawing(_) => None

  /** `toWithdrawingPurpose`. */
  def toWithdrawing[F[_, _], E <: EraTag](
      purpose: AlonzoPlutusPurpose[F, E]
  ): Option[F[Ix, AccountAddress]] = purpose match
    case AlonzoWithdrawing(i)                                       => Some(i)
    case AlonzoSpending(_) | AlonzoMinting(_) | AlonzoCertifying(_) => None

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
