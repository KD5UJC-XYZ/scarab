// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import xyz.kd5ujc.scarab.ledger.state.Diff
import cats.Eq
import cats.Show
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.ledger.state.{BlockIssuer, KeyHash, Sorted, Word64, WithOrigin}
import xyz.kd5ujc.scarab.protocol.Era

/** What the node keeps beside the Haskell chain-dependent state, for the protocols that run on it:
  * both forget the epoch of the last tick (Haskell's `tickChainDepState` is a function of the slot,
  * and ours must not tick twice into one epoch), and TPraos has no previous epoch nonce
  * (`praosStatePreviousEpochNonce` is a field of Praos only).
  *
  * @param epoch
  *   the epoch the chain-dependent state was last ticked to
  * @param previousEpochNonce
  *   the epoch nonce of the epoch before, of a chain in a TPraos era; neutral in a Praos era, where
  *   the state has it (translating the state of TPraos to that of Praos moves it there)
  */
final case class ProtocolExtras(epoch: BigInt, previousEpochNonce: Nonce)

object ProtocolExtras:
  given Eq[ProtocolExtras] = Eq.fromUniversalEquals
  given Show[ProtocolExtras] = Show.fromToString

/** The chain-dependent state of the protocol the chain runs, with [[ProtocolExtras]]: what the
  * header rules (`Praos`, `TPraos`) take and give, and what header-first validation chains along a
  * candidate chain. The pair is always of one protocol; [[ProtocolState.view]] reads either through
  * the shape of Praos, which has the fields of both.
  */
final case class ProtocolState(dep: ChainDepState, extras: ProtocolExtras):
  def epoch: BigInt = extras.epoch

  /** The state through the eight fields of Praos: TPraos's (its tick state is the epoch nonce and
    * the hash nonce of the last block of the previous epoch, `lastEpochBlockNonce`) with the
    * previous epoch nonce of the extras. PBFT has no nonces.
    */
  def view: PraosState = dep match
    case ChainDepState.Praos(p) => p
    case ChainDepState.TPraos(t) =>
      PraosState(
        t.lastSlot,
        t.chainDep.protocol.ocertCounters,
        t.chainDep.protocol.evolvingNonce,
        t.chainDep.protocol.candidateNonce,
        t.chainDep.tickn.epochNonce,
        extras.previousEpochNonce,
        t.chainDep.labNonce,
        t.chainDep.tickn.prevHashNonce
      )
    case ChainDepState.PBft(_) => ProtocolState.noNonces

  def epochNonce: Nonce = view.epochNonce

  def counters: SortedMap[KeyHash[BlockIssuer], Word64] = view.ocertCounters

  /** The state in the protocol of `era`: TPraos's translated to Praos's when the era runs Praos
    * (`translateChainDepState` of `TranslateProto (TPraos c) (Praos c)`, which carries every field
    * over; the previous epoch nonce comes from the extras, where TPraos keeps it), the other
    * protocols' as they are (a state of a Praos era is not taken back to TPraos, nor PBFT's
    * translated: the header rules refuse the state of another protocol).
    */
  def in(era: Era): ProtocolState = dep match
    case ChainDepState.TPraos(_) if !era.usesTPraos && era != Era.Byron =>
      ProtocolState(
        ChainDepState.Praos(view),
        extras.copy(previousEpochNonce = Nonce.Neutral)
      )
    case _ => this

object ProtocolState:
  given Eq[ProtocolState] = Eq.instance((a, b) => a.dep === b.dep && a.extras === b.extras)
  given Diff[ProtocolState] = Diff.record(Diff.at("dep")(_.dep), Diff.at("extras")(_.extras))
  given Show[ProtocolState] = Show.show(p => s"ProtocolState(${p.dep.show}, ${p.extras.show})")

  private val noNonces: PraosState =
    PraosState(
      WithOrigin.Origin,
      Sorted.empty[KeyHash[BlockIssuer], Word64],
      Nonce.Neutral,
      Nonce.Neutral,
      Nonce.Neutral,
      Nonce.Neutral,
      Nonce.Neutral,
      Nonce.Neutral
    )

  /** The state of a chain that has applied no block, in the protocol of `era`: the evolving, the
    * candidate and the epoch nonce (and, in Praos, the previous epoch nonce) are the Shelley
    * genesis hash, the hash nonce of the last block of the previous epoch and the lab are neutral.
    * (Haskell's `translateChainDepStateByronToShelley` is the same, less the previous epoch nonce.)
    */
  def initial(era: Era, shelleyGenesisHash: Bytes): ProtocolState =
    val n = Nonce.Hash(shelleyGenesisHash)
    val counters = Sorted.empty[KeyHash[BlockIssuer], Word64]
    if era.usesTPraos || era == Era.Byron then
      ProtocolState(
        ChainDepState.TPraos(
          TPraosState(
            WithOrigin.Origin,
            TPraosLedgerState(
              PrtclState(counters, n, n),
              TicknState(n, Nonce.Neutral),
              Nonce.Neutral
            )
          )
        ),
        ProtocolExtras(0, n)
      )
    else
      ProtocolState(
        ChainDepState.Praos(
          PraosState(
            WithOrigin.Origin,
            counters,
            n,
            n,
            n,
            n,
            Nonce.Neutral,
            Nonce.Neutral
          )
        ),
        ProtocolExtras(0, Nonce.Neutral)
      )
