// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Temporal
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.LocalTxMonitor
import xyz.kd5ujc.scarab.protocol.LocalTxMonitor.{Kind, Message, State}
import xyz.kd5ujc.scarab.protocol.ChainSync.Role
import scala.collection.immutable.SortedMap

import NetworkError.RuntimeViolation

/** Node-to-client LocalTxMonitor server: a client acquires a snapshot of the mempool and walks it.
  * The snapshot is fixed for as long as the client holds it, whatever the pool does meanwhile; a
  * client that wants the next one asks to `AwaitAcquire`, which waits until the pool differs.
  */
object LocalTxMonitorServer:
  /** What one transaction counts for, in each measure the reference reports. */
  final case class TxMeasure(
      bytes: Long,
      exUnitsMemory: BigInt,
      exUnitsSteps: BigInt,
      refScriptsBytes: Long
  ):
    def +(other: TxMeasure): TxMeasure =
      TxMeasure(
        bytes + other.bytes,
        exUnitsMemory + other.exUnitsMemory,
        exUnitsSteps + other.exUnitsSteps,
        refScriptsBytes + other.refScriptsBytes
      )

  object TxMeasure:
    val zero: TxMeasure = TxMeasure(0, 0, 0, 0)

  /** A held transaction: the item `ReplyNextTx` carries (the era-wrapped transaction) and its
    * measure.
    */
  final case class Held(tx: Bytes, measure: TxMeasure)

  /** A frozen view of the mempool. `has` answers membership for an era-wrapped transaction id item;
    * `identity` is equal for two snapshots exactly when the protocol cannot tell them apart (same
    * slot, same transactions in the same order).
    */
  final case class Snapshot(
      slot: BigInt,
      txs: Vector[Held],
      has: Bytes => Boolean,
      capacity: TxMeasure,
      identity: (BigInt, Vector[Bytes])
  ):
    /** `GetSizes`: the whole snapshot, whatever `NextTx` has handed out. */
    def sizes: LocalTxMonitor.Sizes =
      LocalTxMonitor.Sizes(capacity.bytes, txs.map(_.measure.bytes).sum, txs.size.toLong)

  trait Source[F[_]]:
    /** The mempool as it is now. */
    def acquire: F[Snapshot]

    /** The first snapshot that differs from `seen`, waiting for the pool to change; cancelable. */
    def awaitChange(seen: Snapshot): F[Snapshot]

  /** The reference's names of the measures. */
  val MeasureNames: List[String] =
    List("ex_units_memory", "ex_units_steps", "reference_scripts_bytes", "transaction_bytes")

  /** `GetMeasures` over the transactions `NextTx` has not handed out yet (as the reference does).
    */
  def measures(remaining: Vector[Held], capacity: TxMeasure): LocalTxMonitor.Measures =
    val total = remaining.foldLeft(TxMeasure.zero)(_ + _.measure)
    LocalTxMonitor.Measures(
      remaining.size.toLong,
      SortedMap(
        "ex_units_memory" -> LocalTxMonitor.Measure(total.exUnitsMemory, capacity.exUnitsMemory),
        "ex_units_steps" -> LocalTxMonitor.Measure(total.exUnitsSteps, capacity.exUnitsSteps),
        "reference_scripts_bytes" ->
          LocalTxMonitor.Measure(BigInt(total.refScriptsBytes), BigInt(capacity.refScriptsBytes)),
        "transaction_bytes" ->
          LocalTxMonitor.Measure(BigInt(total.bytes), BigInt(capacity.bytes))
      )
    )

  def run[F[_]: Temporal](
      channel: MuxChannel[F],
      source: Source[F]
  )(using Raise[F, NetworkError]): F[Unit] =
    val F = Temporal[F]
    val id = Protocols.LocalTxMonitor
    def illegal[A](detail: NetworkError.Violation): F[A] =
      NetworkError.IllegalTransition(id, detail).raise[F, A]
    def send(state: State, message: Message): F[Unit] =
      LocalTxMonitor.encode(state, Role.Server, message).fold(illegal, channel.send)
    def receive(state: State): F[Message] =
      channel.recv(LocalTxMonitor.MaxMessageBytes)(
        LocalTxMonitor.decodePrefix(state, Role.Client, _)
      )

    /** Acquiring: tell the client the slot and serve the snapshot until it releases. */
    def acquired(snapshot: Snapshot): F[Boolean] =
      send(State.Acquiring, Message.Acquired(snapshot.slot)) *> serving(snapshot, snapshot.txs)

    def serving(snapshot: Snapshot, remaining: Vector[Held]): F[Boolean] =
      receive(State.Acquired).flatMap {
        case Message.NextTx =>
          send(State.Busy(Kind.NextTx), Message.ReplyNextTx(remaining.headOption.map(_.tx))) *>
            serving(snapshot, remaining.drop(1))
        case Message.HasTx(txId) =>
          send(State.Busy(Kind.HasTx), Message.ReplyHasTx(snapshot.has(txId))) *>
            serving(snapshot, remaining)
        case Message.GetSizes =>
          send(State.Busy(Kind.GetSizes), Message.ReplyGetSizes(snapshot.sizes)) *>
            serving(snapshot, remaining)
        case Message.GetMeasures =>
          send(
            State.Busy(Kind.GetMeasures),
            Message.ReplyGetMeasures(measures(remaining, snapshot.capacity))
          ) *> serving(snapshot, remaining)
        case Message.AwaitAcquire => source.awaitChange(snapshot).flatMap(acquired)
        case Message.Release      => F.pure(true)
        case other                => illegal(RuntimeViolation.LocalTxMonitorUnexpected(other))
      }

    val idle: F[Boolean] =
      receive(State.Idle).flatMap {
        case Message.Acquire => source.acquire.flatMap(acquired)
        case Message.Done    => F.pure(false)
        case other           => illegal(RuntimeViolation.LocalTxMonitorUnexpected(other))
      }
    idle.iterateWhile(identity).void
