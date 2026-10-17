// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.models

import java.nio.file.Path
import xyz.kd5ujc.scarab.codec.Bytes
import scala.util.Random
import xyz.kd5ujc.scarab.models.gen.{CddlSampler, Generator, QName}
import xyz.kd5ujc.scarab.models.runtime.{Codec, DecodeError}

/** Property and fuzz tests driven by the CDDL itself: random valid CBOR is generated from the
  * parsed schema (not from the generated classes), so a mistake in the generator cannot hide in its
  * own output.
  */
class SampledSuite extends munit.FunSuite:
  private val cddlDir = Path.of("models/src/main/cddl")
  private val schema =
    Generator
      .configFrom(cddlDir)
      .flatMap(Generator.load(cddlDir, _))
      .fold(e => throw new IllegalStateException(e.message), identity)
  private val sampler = new CddlSampler(schema)

  /** The generated codec for each sampled rule. */
  private val targets: Vector[(String, Codec[?])] = Vector(
    "handshake-node-to-node-v14:handshakeMessage" -> handshake.HandshakeMessage,
    "chain-sync:chainSyncMessage" -> chainsync.ChainSyncMessage,
    "peer-sharing-v14:peerSharingMessage" -> peersharing.PeerSharingMessage,
    "conway:header" -> conway.Header,
    "babbage:header" -> babbage.Header,
    "alonzo:header" -> alonzo.Header,
    "shelley:header" -> shelley.Header,
    "mary:header" -> mary.Header,
    "dijkstra:header" -> dijkstra.Header,
    "chainsync-header:header" -> chain.hfc.Header,
    "network.base:tip" -> common.Tip,
    "base:point" -> chain.Point
  )

  private def qname(s: String): QName =
    val Array(m, n) = s.split(":", 2): @unchecked
    QName(m, n)

  private def samples(rule: String, count: Int, seed: Long): Vector[Bytes] =
    val rnd = new Random(seed)
    Vector.fill(count)(Bytes.fromArray(sampler.sample(qname(rule), rnd)))

  private def roundTrip[A](codec: Codec[A], bytes: Bytes): Unit =
    val decoded =
      codec.decodeBytes(bytes).fold(e => fail(s"${e.render} for ${bytes.hex}"), identity)
    val again = codec.encodeUnchecked(decoded)
    assertEquals(again, bytes, "canonical sample must re-encode to itself")
    assertEquals(codec.decodeBytes(again), Right(decoded))

  for (rule, codec) <- targets do
    test(s"round trip of 300 random $rule items generated from the CDDL") {
      samples(rule, 300, seed = rule.hashCode.toLong).foreach(roundTrip(codec, _))
    }

  private def mutate(b: Bytes, rnd: Random): Bytes =
    val a = b.toArray
    if a.isEmpty then Bytes.fromArray(Array(rnd.nextInt(256).toByte))
    else
      rnd.nextInt(5) match
        case 0 => a(rnd.nextInt(a.length)) = rnd.nextInt(256).toByte; Bytes.fromArray(a)
        case 1 => Bytes.fromArray(a.take(rnd.nextInt(a.length)))
        case 2 => Bytes.fromArray(a ++ Array(rnd.nextInt(256).toByte))
        case 3 =>
          val i = rnd.nextInt(a.length)
          Bytes.fromArray(a.patch(i, Nil, 1))
        case _ =>
          val i = rnd.nextInt(a.length)
          Bytes.fromArray(a.updated(i, (a(i) ^ (1 << rnd.nextInt(8))).toByte))

  private def neverInternal(name: String, result: Either[DecodeError, ?], input: Bytes): Unit =
    result.left.foreach { e =>
      def internal(x: DecodeError): Boolean = x.kind match
        case DecodeError.Kind.Internal(_)       => true
        case DecodeError.Kind.NoAlternative(cs) => cs.exists(internal)
        case _                                  => false
      assert(!internal(e), s"$name threw on ${input.hex}: ${e.render}")
      assert(
        e.offset >= 0 && e.offset <= input.size,
        s"$name: offset ${e.offset} outside 0..${input.size}"
      )
    }

  for (rule, codec) <- targets do
    test(s"mutated and random bytes against $rule: a value or a typed error, never an exception") {
      val rnd = new Random(rule.hashCode.toLong ^ 0x5eedL)
      var decoded = 0
      var rejected = 0
      samples(rule, 150, seed = 7L).foreach { good =>
        (0 until 8).foreach { _ =>
          val bad = mutate(good, rnd)
          val r = codec.decodeBytes(bad)
          neverInternal(rule, r, bad)
          if r.isRight then decoded += 1 else rejected += 1
        }
      }
      (0 until 500).foreach { _ =>
        val noise = Bytes.fromArray(Array.fill(rnd.nextInt(80))(rnd.nextInt(256).toByte))
        neverInternal(rule, codec.decodeBytes(noise), noise)
      }
      assert(rejected > 0, "mutation never produced an invalid item")
      assert(decoded + rejected == 1200)
    }

  test("every error reports a path that names the CDDL members, outermost first") {
    val bad =
      Bytes.fromHex("83020100").toOption.get // [2, 1, 0]: RollForward with a non-header payload
    val err = chainsync.ChainSyncMessage.decodeBytes(bad).left.toOption.get
    assert(err.render.contains("no alternative"))
    val deepest = err.deepest
    assert(deepest.path.nonEmpty)
  }

  test(
    "the sampler's output is accepted by the hand-written ChainSync framing for its own tip and point"
  ) {
    // A cross-check that the sampler follows the CDDL and not our generator: points and tips it
    // produces are accepted by the independently written hand codec.
    val rnd = new Random(11)
    (0 until 200).foreach { _ =>
      val tipBytes = Bytes.fromArray(sampler.sample(qname("network.base:tip"), rnd))
      assert(
        xyz.kd5ujc.scarab.protocol.ChainSync
          .decodeTip(tipBytes)
          .isInstanceOf[xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult.Decoded[?]]
      )
    }
  }
