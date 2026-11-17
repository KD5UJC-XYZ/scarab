// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{RewardAccount, TxIn, Unsigned, Voter}
import xyz.kd5ujc.scarab.ledger.era.{AsIx, ProposalProcedure}
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayPlutusPurpose
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, ScriptHash}

/** A Dijkstra Plutus purpose in the form `F` (`data DijkstraPlutusPurpose f era`, cardano-ledger
  * 9df9f98c `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Scripts.hs:122-129`): Conway's purposes
  * and the guarding one, whose item is the hash of the guard's script. The index is a `Word32`.
  */
enum DijkstraPlutusPurpose[F[_, _]]:
  case DijkstraSpending(purpose: F[Unsigned[Unsigned.Width.W32], TxIn])
  case DijkstraMinting(purpose: F[Unsigned[Unsigned.Width.W32], Bytes])
  case DijkstraCertifying(purpose: F[Unsigned[Unsigned.Width.W32], DijkstraTxCert])
  case DijkstraWithdrawing(purpose: F[Unsigned[Unsigned.Width.W32], RewardAccount])
  case DijkstraVoting(purpose: F[Unsigned[Unsigned.Width.W32], Voter])
  case DijkstraProposing(purpose: F[Unsigned[Unsigned.Width.W32], ProposalProcedure[Dijkstra]])
  case DijkstraGuarding(purpose: F[Unsigned[Unsigned.Width.W32], ScriptHash])

  /** The redeemer tag of the purpose: spend 0, mint 1, cert 2, reward 3, vote 4, propose 5 and
    * guard 6 (`EncCBORGroup (DijkstraPlutusPurpose f era)`, `Scripts.hs:185-202`, cardano-ledger
    * 9df9f98c).
    */
  def tag: Int = this match
    case DijkstraSpending(_)    => 0
    case DijkstraMinting(_)     => 1
    case DijkstraCertifying(_)  => 2
    case DijkstraWithdrawing(_) => 3
    case DijkstraVoting(_)      => 4
    case DijkstraProposing(_)   => 5
    case DijkstraGuarding(_)    => 6

object DijkstraPlutusPurpose:
  /** The derived `Ord (DijkstraPlutusPurpose AsIx era)` (`Scripts.hs:260`, cardano-ledger
    * 9df9f98c): the constructor, then the index. A redeemer's key.
    */
  given ordering: Ordering[DijkstraPlutusPurpose[AsIx]] = (a, b) =>
    val tags = Integer.compare(a.tag, b.tag)
    if tags != 0 then tags else a.ix.value.compare(b.ix.value)

  extension (p: DijkstraPlutusPurpose[AsIx])
    /** The index of the item the purpose names. */
    def ix: Unsigned[Unsigned.Width.W32] = p match
      case DijkstraSpending(AsIx(i))    => i
      case DijkstraMinting(AsIx(i))     => i
      case DijkstraCertifying(AsIx(i))  => i
      case DijkstraWithdrawing(AsIx(i)) => i
      case DijkstraVoting(AsIx(i))      => i
      case DijkstraProposing(AsIx(i))   => i
      case DijkstraGuarding(AsIx(i))    => i

  /** A Conway redeemer's key carried into Dijkstra (`upgradePlutusPurposeAsIx`,
    * `Scripts.hs:528-534`, cardano-ledger 9df9f98c): the same purpose and index.
    */
  def translate(p: ConwayPlutusPurpose[AsIx]): DijkstraPlutusPurpose[AsIx] = p match
    case ConwayPlutusPurpose.ConwaySpending(AsIx(i))    => DijkstraSpending(AsIx(i))
    case ConwayPlutusPurpose.ConwayMinting(AsIx(i))     => DijkstraMinting(AsIx(i))
    case ConwayPlutusPurpose.ConwayCertifying(AsIx(i))  => DijkstraCertifying(AsIx(i))
    case ConwayPlutusPurpose.ConwayWithdrawing(AsIx(i)) => DijkstraWithdrawing(AsIx(i))
    case ConwayPlutusPurpose.ConwayVoting(AsIx(i))      => DijkstraVoting(AsIx(i))
    case ConwayPlutusPurpose.ConwayProposing(AsIx(i))   => DijkstraProposing(AsIx(i))
