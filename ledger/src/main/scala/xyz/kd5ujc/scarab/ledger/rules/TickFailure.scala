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
  * the state leaving the range of a vocabulary type, which Haskell would wrap or abort on
  * (`compactCoinOrError`), and which the node refuses. A state of a real chain does not reach any
  * of them: the whole supply (4.5e16 lovelace) is far below 2^63.
  */
enum TickFailure derives Eq, Show:
  /** A quantity left the range of its vocabulary type (a balance below zero, a word above 2^64). */
  case Vocab(error: VocabError)

  /** The state holds a reward update that is still being pulsed (it came from a Haskell state): the
    * node computes its updates in one step and does not carry a pulser on (design D8, J3.5).
    */
  case PulsingRewardUpdate

  /** An output of the Byron UTxO has an address that does not decode (the hand-over to Shelley
    * carries every output).
    */
  case UndecodableAddress(in: TxIn)

object TickFailure:
  private given Eq[VocabError] = Eq.fromUniversalEquals
  private given Show[VocabError] = Show.fromToString
  private given Eq[TxIn] = Eq.fromUniversalEquals
  private given Show[TxIn] = Show.fromToString
