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

object TickFailure:
  private given Eq[VocabError] = Eq.fromUniversalEquals
  private given Show[VocabError] = Show.fromToString
  private given Eq[TxIn] = Eq.fromUniversalEquals
  private given Show[TxIn] = Show.fromToString
