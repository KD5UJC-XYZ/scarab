// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.Defer
import cats.effect.{Resource, Temporal}
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.NtcVersion
import xyz.kd5ujc.scarab.protocol.LocalStateQuery.{Failure, Target}

/** Node-to-client LocalStateQuery server. The interpreter of the ledger sits behind [[Source]]:
  * `acquire` fixes a state (a [[Session]]) for as long as the client keeps it, and each query is
  * answered against that one state, however far the chain has moved meanwhile.
  */
object LocalStateQueryServer:
  /** Why a query is not answered. The connection ends, as it does on the reference when its codec
    * cannot decode a query, and the reason is logged with the close.
    */
  type Refusal = xyz.kd5ujc.scarab.protocol.QueryRefusal[?]
  val Refusal = xyz.kd5ujc.scarab.protocol.QueryRefusal

  type Session[F[_], E] = LocalStateQueryRuntime.Session[F, E]

  /** Acquisition can fail through the existing wire failure, or report an internal view invariant
    * that the owning server must retain as a typed error without inventing a protocol tag.
    */
  enum AcquireFailure[+E]:
    case Rejected(failure: Failure)
    case Incoherent(
        point: xyz.kd5ujc.scarab.protocol.ChainSync.Point,
        flushed: Long,
        pinned: Long,
        selected: BigInt
    )
    case Backend(error: E)

  trait Source[F[_], E]:
    /** Bind subsequent acquisitions to the version agreed on this connection. Sources whose answers
      * do not depend on the version may keep their existing interpreter.
      */
    def forVersion(version: NtcVersion): Source[F, E] = this

    def acquire(target: Target): Resource[F, Either[AcquireFailure[E], Session[F, E]]]

  type Settings = LocalStateQueryRuntime.Settings
  val Settings: LocalStateQueryRuntime.Settings.type = LocalStateQueryRuntime.Settings

  def run[F[_]: Temporal: Defer, E](
      channel: MuxChannel[F],
      source: Source[F, E],
      settings: Settings = Settings()
  )(using Raise[F, NetworkError]): F[Either[E, Unit]] =
    val adapted =
      new LocalStateQueryRuntime.Source[F, E, xyz.kd5ujc.scarab.protocol.ChainSync.Point]:
        def acquire(target: Target) = source
          .acquire(target)
          .map(_.leftMap {
            case AcquireFailure.Rejected(failure) =>
              LocalStateQueryRuntime.AcquireFailure.Rejected(failure)
            case failure @ AcquireFailure.Incoherent(_, _, _, _) =>
              LocalStateQueryRuntime.AcquireFailure.Network(
                NetworkError.StateAcquireFailure(
                  failure.point,
                  failure.flushed,
                  failure.pinned,
                  failure.selected
                )
              )
            case AcquireFailure.Backend(error) =>
              LocalStateQueryRuntime.AcquireFailure.Backend(error)
          })
    LocalStateQueryRuntime.run(
      channel,
      adapted,
      xyz.kd5ujc.scarab.protocol.ChainSync.enginePointCodec,
      settings
    )
