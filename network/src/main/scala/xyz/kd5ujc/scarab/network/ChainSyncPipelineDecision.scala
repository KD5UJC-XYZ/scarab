// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import xyz.kd5ujc.scarab.protocol.ChainSync.UInt64

/** `pipelineDecisionLowHighMark`, ouroboros-network
  * `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`,
  * `Ouroboros/Network/Protocol/ChainSync/PipelineDecision.hs`.
  */
object ChainSyncPipelineDecision:
  enum Action:
    case Request, Pipeline, CollectOrPipeline, Collect

  enum Phase:
    case Low, High

  enum Error:
    case InvalidMarks(low: Int, high: Int)
    case NegativeOutstanding(value: Int)

  final class Window private (val low: Int, val high: Int)
  object Window:
    val Default: Window = new Window(200, 300)
    def checked(low: Int, high: Int): Either[Error, Window] =
      if low < 0 || high < low || high > 65535 then Left(Error.InvalidMarks(low, high))
      else Right(new Window(low, high))

  final case class Policy(window: Window = Window.Default, phase: Phase = Phase.Low):
    /** None is native Origin; addition at Origin starts at one and all additions wrap Word64. */
    def decide(
        outstanding: Int,
        client: Option[UInt64],
        server: Option[UInt64]
    ): Either[Error, (Action, Policy)] =
      if outstanding < 0 then Left(Error.NegativeOutstanding(outstanding))
      else if outstanding == 0 then
        Right((if client == server then Action.Request else Action.Pipeline, copy(phase = Phase.Low)))
      else
        phase match
          case Phase.High =>
            if outstanding > window.low then Right(Action.Collect -> this)
            else Right(Action.CollectOrPipeline -> copy(phase = Phase.Low))
          case Phase.Low =>
            val plus = (client.fold(BigInt(1))(_.value) + outstanding) & UInt64.Max
            val reachesServer = server.forall(_.value <= plus)
            if reachesServer then Right(Action.Collect -> this)
            else if outstanding >= window.high then Right(Action.Collect -> copy(phase = Phase.High))
            else Right(Action.CollectOrPipeline -> this)
