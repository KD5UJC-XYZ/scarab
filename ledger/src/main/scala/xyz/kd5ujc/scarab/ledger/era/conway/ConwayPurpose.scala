// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{RewardAccount, TxIn, Unsigned, Voter}
import xyz.kd5ujc.scarab.ledger.era.{AsIx, ProposalProcedure}

/** A Conway Plutus purpose in the form `F` (`data ConwayPlutusPurpose f era`, cardano-ledger
  * f649f975 `eras/conway/impl/src/Cardano/Ledger/Conway/Scripts.hs:202-208`): what a script is run
  * for, with the index of the item a redeemer names (`AsIx`), the item (`AsItem`) or both
  * (`AsIxItem`). The index is a `Word32`.
  */
enum ConwayPlutusPurpose[F[_, _]]:
  case ConwaySpending(purpose: F[Unsigned[Unsigned.Width.W32], TxIn])
  case ConwayMinting(purpose: F[Unsigned[Unsigned.Width.W32], Bytes])
  case ConwayCertifying(purpose: F[Unsigned[Unsigned.Width.W32], ConwayTxCert])
  case ConwayWithdrawing(purpose: F[Unsigned[Unsigned.Width.W32], RewardAccount])
  case ConwayVoting(purpose: F[Unsigned[Unsigned.Width.W32], Voter])
  case ConwayProposing(purpose: F[Unsigned[Unsigned.Width.W32], ProposalProcedure])

  /** The redeemer tag of the purpose: spend 0, mint 1, cert 2, reward 3, vote 4 and propose 5
    * (`EncCBORGroup (ConwayPlutusPurpose AsIx era)`, `Conway/Scripts.hs:264-278`, cardano-ledger
    * f649f975).
    */
  def tag: Int = this match
    case ConwaySpending(_)    => 0
    case ConwayMinting(_)     => 1
    case ConwayCertifying(_)  => 2
    case ConwayWithdrawing(_) => 3
    case ConwayVoting(_)      => 4
    case ConwayProposing(_)   => 5

object ConwayPlutusPurpose:
  /** The derived `Ord (ConwayPlutusPurpose AsIx era)` (`Conway/Scripts.hs:213`, cardano-ledger
    * f649f975): the constructor, then the index. A redeemer's key.
    */
  given ordering: Ordering[ConwayPlutusPurpose[AsIx]] = (a, b) =>
    val tags = Integer.compare(a.tag, b.tag)
    if tags != 0 then tags else a.ix.value.compare(b.ix.value)

  extension (p: ConwayPlutusPurpose[AsIx])
    /** The index of the item the purpose names. */
    def ix: Unsigned[Unsigned.Width.W32] = p match
      case ConwaySpending(AsIx(i))    => i
      case ConwayMinting(AsIx(i))     => i
      case ConwayCertifying(AsIx(i))  => i
      case ConwayWithdrawing(AsIx(i)) => i
      case ConwayVoting(AsIx(i))      => i
      case ConwayProposing(AsIx(i))   => i

  /** The purpose of a redeemer's key `[tag, index]`, of a tag Conway's decoder read (0 to 5). */
  private[conway] def asIx(
      tag: Int,
      index: Unsigned[Unsigned.Width.W32]
  ): Option[ConwayPlutusPurpose[AsIx]] = tag match
    case 0 => Some(ConwaySpending(AsIx(index)))
    case 1 => Some(ConwayMinting(AsIx(index)))
    case 2 => Some(ConwayCertifying(AsIx(index)))
    case 3 => Some(ConwayWithdrawing(AsIx(index)))
    case 4 => Some(ConwayVoting(AsIx(index)))
    case 5 => Some(ConwayProposing(AsIx(index)))
    case _ => None
