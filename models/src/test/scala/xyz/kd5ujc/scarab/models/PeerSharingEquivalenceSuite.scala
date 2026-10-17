// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import java.nio.file.Path
import scala.util.Random
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.models.gen.{CddlSampler, Generator, QName}
import xyz.kd5ujc.scarab.models.peersharing.{PeerAddress, PeerSharingMessage}
import xyz.kd5ujc.scarab.protocol.PeerSharing

/** The hand-written PeerSharing codec against the one generated from `peer-sharing-v14.cddl`:
  * messages sampled from the CDDL decode to the same value in both, and the hand-written encoding
  * of every value decodes in the generated codec to that value (the reference writes the list of
  * peers indefinite, the sampler writes it definite; both are valid CDDL).
  */
class PeerSharingEquivalenceSuite extends munit.FunSuite:
  private val cddlDir = Path.of("models/src/main/cddl")
  private val sampler = new CddlSampler(
    Generator
      .configFrom(cddlDir)
      .flatMap(Generator.load(cddlDir, _))
      .fold(e => throw new IllegalStateException(e.message), identity)
  )

  private def sampled(count: Int, seed: Long): Vector[Bytes] =
    val rnd = new Random(seed)
    Vector.fill(count)(
      Bytes.fromArray(sampler.sample(QName("peer-sharing-v14", "peerSharingMessage"), rnd))
    )

  private def address(a: PeerAddress): PeerSharing.Address = a match
    case PeerAddress.Alt0(w, p) =>
      PeerSharing.Address.V4(PeerSharing.Word32.ofBits(w.value.toInt), port(p.value))
    case PeerAddress.Alt1(a, b, c, d, p) =>
      def word(w: Long) = PeerSharing.Word32.ofBits(w.toInt)
      PeerSharing.Address.V6(
        word(a.value),
        word(b.value),
        word(c.value),
        word(d.value),
        port(p.value)
      )

  private def port(p: Int) = PeerSharing.Port.from(p).getOrElse(fail(s"port $p"))

  /** The generated value as the hand-written message, and the state it is sent in. */
  private def hand(
      m: PeerSharingMessage
  ): (PeerSharing.State, PeerSharing.Role, PeerSharing.Message) = m match
    case PeerSharingMessage.MsgShareRequest(w) =>
      (
        PeerSharing.State.Idle,
        PeerSharing.Role.Client,
        PeerSharing.Message.ShareRequest(
          PeerSharing.Amount.from(w.value).getOrElse(fail(s"amount ${w.value}"))
        )
      )
    case PeerSharingMessage.MsgSharePeers(peers) =>
      (
        PeerSharing.State.Busy,
        PeerSharing.Role.Server,
        PeerSharing.Message.SharePeers(peers.map(address))
      )
    case PeerSharingMessage.MsgDone =>
      (PeerSharing.State.Idle, PeerSharing.Role.Client, PeerSharing.Message.Done)

  test("300 messages sampled from the CDDL: both decoders agree, and re-encoded they still do") {
    sampled(300, seed = 138L).foreach { bytes =>
      val generated = PeerSharingMessage
        .decodeBytes(bytes)
        .fold(e => fail(s"${e.render} for ${bytes.hex}"), identity)
      val (state, role, message) = hand(generated)
      assertEquals(PeerSharing.decode(state, role, bytes), Right(message), bytes.hex)
      val written = PeerSharing
        .encode(state, role, message)
        .fold(e => fail(s"${e.message} for ${bytes.hex}"), identity)
      assertEquals(PeerSharingMessage.decodeBytes(written), Right(generated), written.hex)
    }
  }

  test("the sample covers every message and both address families") {
    val all = sampled(300, seed = 138L).map(b => PeerSharingMessage.decodeBytes(b).toOption.get)
    assert(all.exists(_.isInstanceOf[PeerSharingMessage.MsgShareRequest]))
    assert(all.exists(_ == PeerSharingMessage.MsgDone))
    val addresses = all.collect { case PeerSharingMessage.MsgSharePeers(p) => p }.flatten
    assert(addresses.exists(_.isInstanceOf[PeerAddress.Alt0]))
    assert(addresses.exists(_.isInstanceOf[PeerAddress.Alt1]))
  }
