// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.{Eq, Show}
import cats.derived.*
import xyz.kd5ujc.scarab.ledger.TxIn
import xyz.kd5ujc.scarab.ledger.state.VocabError

/** Why a tick can fail. Haskell's TICK, NEWEPOCH, EPOCH, SNAP, POOLREAP, MIR, RUPD and UPEC have no
  * predicate failures (`type PredicateFailure (TICK era) = Void`, `Tick.hs:112`; the same line in
  * each of the others): they are total over a state of the right shape. The node's values are
  * checked ones (`Coin` is never negative, a `CompactCoin` is a 64-bit word), so what remains is
  * the state leaving the range of a vocabulary type and a number taken from the block that is
  * outside its type. Where the reference converts with `compactCoinOrError` it aborts (an `error`);
  * where its type is wider than the node's (`Coin` is an `Integer`) or wraps (a `Word64`), it
  * carries on and the node stops instead (#474). A state of a real chain reaches neither: the whole
  * supply (4.5e16 lovelace) is far below 2^63.
  */
enum TickFailure derives Eq, Show:
  /** A quantity of the state left the range of its vocabulary type (a balance below zero, a word
    * above 2^64). This is not a verdict on the block: in the reference it is an exception on the
    * block path (`compactCoinOrError`; ouroboros-consensus 82ecba32 `Storage/ChainDB/Impl/
    * Background.hs:107-109,142`, `addBlockRunner` is a linked thread), so the node stops
    * ([[xyz.kd5ujc.scarab.ledger.ChainError.StateOutOfRange]]). The block is not recorded as
    * invalid and no peer is blamed.
    */
  case Vocab(error: VocabError)

  /** A number taken from the block (its slot, the hash of its issuer's key), not from the ledger
    * state, is outside its vocabulary type. A refusal of the block.
    */
  case Input(error: VocabError)

  /** An output of the Byron UTxO has an address that does not decode (the hand-over to Shelley
    * carries every output).
    */
  case UndecodableAddress(in: TxIn)

  /** A part of the Dijkstra era this build does not implement yet (its EPOCH rule). The hard-fork
    * configuration never lets the chain enter Dijkstra while the maximum major protocol version is
    * 11 (`xyz.kd5ujc.scarab.consensus.HardForkConfig.transition`).
    */
  case NotYet(part: xyz.kd5ujc.scarab.protocol.DijkstraNotYet)

  /** The chain would enter Dijkstra with no Dijkstra genesis, the context of the translation
    * (`TranslationContext DijkstraEra`, cardano-ledger 9df9f98c `Dijkstra/Translation.hs:51`). A
    * strict configuration that enables `ExperimentalHardForksEnabled` must name the
    * `DijkstraGenesisFile` (`NodeConfig`), and the node refuses that setting as unsupported for
    * now. The reference would translate with `emptyDijkstraGenesis` (cardano-node b004fe0c
    * `Protocol/Cardano.hs:137-144`) instead, so this refusal stands where Scarab has no genesis.
    * That difference is a deviation the owner approved on 2026-11-07 (#54, `docs/decisions.md`).
    */
  case NoDijkstraGenesis

  /** SNAP found no Leios committee for the mark snapshot it rotates into the set position: a state
    * the reference cannot hold, or a failure of the native BLS library while it checked a proof of
    * possession ([[xyz.kd5ujc.scarab.ledger.state.LeiosCommitteeError]]). The reference's selection
    * is total, and a library failure is an exception there, so this is no verdict on the block: the
    * node stops, for a state ([[xyz.kd5ujc.scarab.ledger.ChainError.LeiosCommittee]]) as for a
    * provider defect ([[xyz.kd5ujc.scarab.ledger.ValidationError.unverifiable]]).
    */
  case LeiosCommittee(cause: xyz.kd5ujc.scarab.ledger.state.LeiosCommitteeError)

  /** The hand-over to Shelley for a reference build whose `state` layout this build does not
    * implement yet ([[xyz.kd5ujc.scarab.protocol.ReferenceBuildNotYet.Layout]]). The setup refuses
    * such a build (`NodeSetup`), so the rules never meet it.
    */
  case LayoutNotYet(cause: xyz.kd5ujc.scarab.protocol.ReferenceBuildNotYet)

  /** The node's record of the governance state does not hold the era's state Dijkstra's tick works
    * on ([[xyz.kd5ujc.scarab.ledger.era.StoredGovError]]): a record that does not read into the
    * era's, which only a damaged record gives, or a state the record cannot hold, which the tick
    * never leaves. The reference holds the era's record itself, so this is no verdict on the block
    * ([[xyz.kd5ujc.scarab.ledger.ChainError.GovRecord]]): the block is not recorded as invalid and
    * no peer is blamed.
    */
  case GovRecord(cause: xyz.kd5ujc.scarab.ledger.era.StoredGovError)

object TickFailure:
  private given Eq[VocabError] = Eq.fromUniversalEquals
  private given Show[VocabError] = Show.fromToString
  private given Eq[TxIn] = Eq.fromUniversalEquals
  private given Show[TxIn] = Show.fromToString
  private given Eq[xyz.kd5ujc.scarab.protocol.DijkstraNotYet] = Eq.fromUniversalEquals
  private given Show[xyz.kd5ujc.scarab.protocol.DijkstraNotYet] = Show.fromToString
  private given Eq[xyz.kd5ujc.scarab.protocol.ReferenceBuildNotYet] = Eq.fromUniversalEquals
  private given Show[xyz.kd5ujc.scarab.protocol.ReferenceBuildNotYet] = Show.show(_.message)
  private given Eq[xyz.kd5ujc.scarab.ledger.state.LeiosCommitteeError] = Eq.fromUniversalEquals
  private given Show[xyz.kd5ujc.scarab.ledger.state.LeiosCommitteeError] = Show.fromToString
  private given Eq[xyz.kd5ujc.scarab.ledger.era.StoredGovError] = Eq.fromUniversalEquals
  private given Show[xyz.kd5ujc.scarab.ledger.era.StoredGovError] = Show.fromToString
