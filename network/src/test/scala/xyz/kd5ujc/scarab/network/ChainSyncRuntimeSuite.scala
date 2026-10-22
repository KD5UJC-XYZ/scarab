// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSyncEngine
import scala.concurrent.duration.*

import Peers.*

class ChainSyncRuntimeSuite extends FailsOnUncaught:
  private final case class MockPoint(item: Bytes)
  private final case class MockTip(item: Bytes)
  private final case class MockHeader(item: Bytes)

  private val point = ChainSyncEngine.pointCodec[MockPoint](
    value => Right(value.item),
    item => Right(MockPoint(item))
  )
  private val tip = ChainSyncEngine.tipCodec[MockTip](
    value => Right(value.item),
    item => Right(MockTip(item))
  )
  private val payload = ChainSyncEngine.payloadCodec[MockHeader](
    value => Right(value.item),
    item => Right(MockHeader(item))
  )

  private def item(hex: String): Bytes =
    Bytes.fromHex(hex).fold(error => fail(error.toString), identity)

  test(
    "generic client and server runtime exchange non-Cardano typed point, tip, and header values"
  ):
    val chainPoint = MockPoint(item("82180040"))
    val chainTip = MockTip(item("82821800401801"))
    val header = MockHeader(item("a10102"))
    val source = new ChainSyncRuntime.Source[IO, MockPoint, MockTip, MockHeader]:
      def intersect(points: Vector[MockPoint]) =
        IO.pure(points.headOption.fold(Left(chainTip))(found => Right(found -> chainTip)))
      def poll = IO.pure(Some(ChainSyncRuntime.Update.RollForward(header, chainTip)))
      def await = IO.never[ChainSyncRuntime.Update[MockPoint, MockTip, MockHeader]]

    typed {
      linked(protocols = Set(Protocols.ChainSync)).use { (clientMux, serverMux) =>
        for
          clientChannel <- clientMux.channel(Protocols.ChainSync)
          serverChannel <- serverMux.channel(Protocols.ChainSync)
          server <- typed(
            ChainSyncRuntime.run(
              serverChannel,
              point,
              tip,
              payload,
              source,
              ChainSyncRuntime.ServerSettings(idleTimeout = 1.second)
            )
          ).start
          client <- ChainSyncRuntime.client(
            clientChannel,
            point,
            tip,
            payload,
            ChainSyncRuntime.ClientSettings(
              intersectTimeout = 1.second,
              canAwaitTimeout = 1.second,
              mustReply = ChainSyncTimeouts.Policy.Fixed(ProtocolDeadline.After(1.second))
            )
          )
          intersection <- client.findIntersect(Vector(chainPoint))
          update <- client.requestNext
          _ <- client.done
          serverResult <- server.joinWithNever
        yield
          assertEquals(intersection, Some(chainPoint -> chainTip))
          assertEquals(update, ChainSyncRuntime.Update.RollForward(header, chainTip))
          assertEquals(serverResult, Right(()))
      }
    }.map(result => assertEquals(result, Right(())))
