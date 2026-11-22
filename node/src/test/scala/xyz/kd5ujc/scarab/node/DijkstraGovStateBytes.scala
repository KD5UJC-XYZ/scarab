// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{GovActionId, LedgerDecodeError}
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraGovActionId
import xyz.kd5ujc.scarab.ledger.era.ConwayGovState
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.models.runtime.{ConstraintViolation, DecodeError}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, LeiosStateCodecs}

/** Why [[DijkstraGovStateBytes]] cannot read or write a state, each with its typed cause. */
enum GovStateBytesError:
  case Cbor(error: xyz.kd5ujc.scarab.codec.Cbor.Error)
  case NotAnArray(value: Value)
  case NoItem(index: Int)
  case ActionId(error: LedgerDecodeError)
  case State(error: DecodeError)
  case Written(error: ConstraintViolation)

/** A Dijkstra `NewEpochState` as the Leios build writes it, read and written by the node's codec,
  * whose governance state is the era's record (`LeiosStateCodecs.dijkstraGovState`), with the
  * original proposal item of each action it holds (the fifth item of an action's state, in the
  * proposals, the pulser's snapshot and its ratification's enacted actions), by the action's id.
  */
object DijkstraGovStateBytes:
  private given ShelleyBasedEra[Dijkstra] = ShelleyBasedEra.SDijkstra
  private val codec = LeiosStateCodecs.newEpochState[Dijkstra]

  /** A state read: the node's state, its governance state, and the original proposal item of each
    * action by its id.
    */
  final case class Read(
      nes: NewEpochState[Dijkstra],
      gov: ConwayGovState[Dijkstra],
      proposals: Map[GovActionId, Node]
  ):
    def state: EraDijkstraTick.State = EraDijkstraTick.State(nes, gov)

  private def items(n: Node): Either[GovStateBytesError, Vector[Node]] = n.value match
    case Value.Arr(xs) => Right(xs)
    case other         => Left(GovStateBytesError.NotAnArray(other))

  private def at(n: Node, path: Int*): Either[GovStateBytesError, Node] =
    path.foldLeft[Either[GovStateBytesError, Node]](Right(n))((m, i) =>
      m.flatMap(items).flatMap(_.lift(i).toRight(GovStateBytesError.NoItem(i)))
    )

  private def decode(bytes: Bytes): Either[GovStateBytesError, Node] =
    Cbor.decode(bytes, HaskellState.limits).leftMap(GovStateBytesError.Cbor(_))

  /** The action states of the governance state of `nes`: the proposals' (`gov[0][1]`), the pulser's
    * snapshot's (`gov[6][0][0]`) and its ratification's enacted actions (`gov[6][1][1]`), the
    * governance state at `[3, 1, 1, 3]` of the `NewEpochState` (`esLState`, `lsUTxOState`,
    * `utxosGovState`).
    */
  private def actionStates(nes: Node): Either[GovStateBytesError, Vector[Node]] =
    for
      gov <- at(nes, 3, 1, 1, 3)
      lists <- Vector(at(gov, 0, 1), at(gov, 6, 0, 0), at(gov, 6, 1, 1)).sequence
      states <- lists.traverse(items)
    yield states.flatten

  private def id(gas: Node): Either[GovStateBytesError, GovActionId] =
    at(gas, 0)
      .flatMap(n => DijkstraGovActionId.decode(n).leftMap(GovStateBytesError.ActionId(_)))
      .map(d => GovActionId(d.txId, BigInt(d.index)))

  /** A `NewEpochState`'s bytes read ([[DijkstraGovStateBytes]]). */
  def read(bytes: Bytes): Either[GovStateBytesError, Read] =
    for
      node <- decode(bytes)
      nes <- codec.decode(node).leftMap(GovStateBytesError.State(_))
      states <- actionStates(node)
      originals <- states.traverse(gas => (id(gas), at(gas, 4)).tupled)
    yield Read(nes, nes.es.ls.utxo.gov, originals.toMap)

  /** The state `s` written ([[DijkstraGovStateBytes]]), its governance state the one beside it. */
  def write(s: EraDijkstraTick.State): Either[GovStateBytesError, Bytes] =
    codec.write(EraDijkstraTick.stored(s)).leftMap(GovStateBytesError.Written(_))

  /** The `NewEpochState` of a state file the build wrote: the extended state's ledger telescope at
    * its eighth era, `[start, [2, [tip, NewEpochState, transition, bytes]]]`.
    */
  def newEpochStateOfFile(bytes: Bytes): Either[GovStateBytesError, Bytes] =
    decode(bytes).flatMap(root => at(root, 1, 0, 7, 1, 1, 1)).map(_.original)
