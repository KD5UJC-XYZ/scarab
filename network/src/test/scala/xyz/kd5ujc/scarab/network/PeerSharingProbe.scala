// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{ExitCode, IO, IOApp}
import cats.mtl.Handle
import cats.syntax.all.*
import com.comcast.ip4s.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSyncFixtures, PeerSharing}

/** Asks a node for peers over PeerSharing, as a client. It connects (node-to-node, version 14),
  * offers peer sharing, and when the peer agrees asks `count` times in a row for `amount` peers and
  * prints the answers. Its output is the live evidence that the Haskell node speaks the protocol
  * to us.
  *
  * Run, inside the build container:
  * `network/Test/runMain xyz.kd5ujc.scarab.network.PeerSharingProbe <host> <port> <magic> <amount> <count>`.
  * Throwaway tool: not part of the node, not run by `check`.
  */
object PeerSharingProbe extends IOApp:
  def run(args: List[String]): IO[ExitCode] = args match
    case host :: port :: magic :: amount :: count :: _ =>
      (for
        h <- IO.fromOption(Host.fromString(host))(new IllegalArgumentException("host"))
        p <- IO.fromOption(Port.fromInt(port.toInt))(new IllegalArgumentException("port"))
        n <- IO.fromOption(PeerSharing.Amount.from(amount.toInt))(new IllegalArgumentException("amount"))
        outcome <- probe(SocketAddress(h, p), magic.toLong, n, count.toInt)
      yield outcome).as(ExitCode.Success)
    case _ =>
      IO.println("usage: PeerSharingProbe host port magic amount count").as(ExitCode.Error)

  private def probe(
      address: SocketAddress[Host],
      magic: Long,
      amount: PeerSharing.Amount,
      count: Int
  ): IO[Unit] =
    Handle
      .allow[NetworkError] {
        NodeToNode
          .connectWarm[IO, Bytes](
            address,
            magic,
            ChainSyncFixtures.rawItem,
            NodeToNode.Settings(offerPeerSharing = true)
          )
          .use { warm =>
            for
              _ <- IO.println(s"connected: version ${warm.version}, peer sharing ${warm.peerSharing.isDefined}")
              _ <- warm.peerSharing match
                case None => IO.println("peer sharing not agreed: the peer has it off")
                case Some(client) =>
                  (1 to count).toList.traverse_ { i =>
                    client.request(amount).flatMap { peers =>
                      IO.println(s"request $i for ${amount.value}: ${peers.size} peers") *>
                        peers.traverse_(a => IO.println(s"  ${show(a)}"))
                    }
                  } *> client.done
            yield ()
          }
      }
      .rescue(error => IO.println(s"failed: ${error.message}"))

  private def show(address: PeerSharing.Address): String = address match
    case PeerSharing.Address.V4(a, p) =>
      val w = a.value
      s"${(w >> 24) & 255}.${(w >> 16) & 255}.${(w >> 8) & 255}.${w & 255}:${p.value}"
    case PeerSharing.Address.V6(a, b, c, d, p) =>
      s"[${List(a, b, c, d).map(w => f"${w.value}%08x").mkString(":")}]:${p.value}"
end PeerSharingProbe
