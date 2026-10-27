// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.blst.Blst
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Rational, Value}
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorState}
import xyz.kd5ujc.scarab.ledger.{
  Credential,
  Ipv4,
  Ipv6,
  PoolParams,
  PoolRelay,
  PoolRelays,
  RelayPort,
  RewardAccount
}
import xyz.kd5ujc.scarab.ledger.state.{Credential as NativeCredential, *}
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era}

/** Published queryPoolParameters examples are built independently from their JSON fields in native
  * state, then answered by the node. The opaque relay payload is encoded from typed addresses, not
  * copied from the expected CBOR. Other published pool results are retained with explicit limits.
  */
class UpstreamPoolGoldenSuite extends FunSuite:
  private val root = Path.of("fixtures/reference/ledger-api-pool-goldens")
  private def hex(value: String): Bytes =
    Bytes.fromHex(value).fold(error => fail(error.message), identity)
  private def checked[A](value: Either[VocabError, A]): A =
    value.fold(error => fail(error.toString), identity)
  private def hash[R <: KeyRole](value: Bytes): KeyHash[R] = checked(KeyHash.fromBytes[R](value))
  private def read(path: String): Bytes = Bytes.fromArray(Files.readAllBytes(root.resolve(path)))
  private def decoded(value: Bytes): Node =
    Cbor.decode(value).fold(error => fail(error.message), identity)
  private def array(value: Node): Vector[Node] = value.value match
    case Value.Arr(items) => items
    case other            => fail(s"expected a published array, found $other")
  private def entries(value: Node): Vector[(Node, Node)] = value.value match
    case Value.Map(items) => items
    case other            => fail(s"expected a published map, found $other")

  private val owner = hex("0d6a577e9441ad8ed9663931906e4d43ece8f82c712b1d0235affb06")
  private val poolB = hex("b77908a84072eb9151782d64504f0f6975b7143d49514eaeb0991d00")
  private val poolC = hex("c7b31a062855f437a05caf5060582ea40e840ebe4b431ffdf84780db")
  private val vrf = hex("c5e21ab1c9f6022d81c3b25e3436cb7f1df77f9652ae3e1310c28e621dd87b4c")
  private def port(value: Int): Option[RelayPort] =
    Some(RelayPort.fromInt(value).getOrElse(fail(s"invalid published port $value")))
  private val commonRelays = Vector(
    PoolRelay
      .SingleHostAddr(port(3000), Some(Ipv4(0x7f000001)), Some(Ipv6(0x20010db800000000L, 1L))),
    PoolRelay.SingleHostName(port(3001), "relay.example.com"),
    PoolRelay.MultiHostName("relay.example.com")
  )
  private val metadata = CborOut.build(_.array(2).text("consensus.pool").bytes(hex("7b7d")))
  private val first = PoolParams(
    owner,
    vrf,
    1,
    5,
    Rational(1, 10),
    RewardAccount(0, Credential.Key(owner)),
    Set(owner),
    PoolRelays.encode(commonRelays),
    Some(metadata)
  )
  private val parameters = Vector(
    first,
    first.copy(
      operator = poolB,
      pledge = 100000000,
      cost = 340000000,
      relays = PoolRelays.encode(
        Vector(
          PoolRelay.SingleHostAddr(port(3001), Some(Ipv4(0x0a000001)), None),
          PoolRelay.MultiHostName("_relay._tcp.pool-99.example")
        )
      )
    ),
    first.copy(operator = poolC, pledge = 0, cost = 170000000, metadata = None)
  )
  private val base = NtcGolden.conwayStates.headOption
    .getOrElse(fail("missing native Conway capture"))
    ._2

  private def state(parameters: Vector[PoolParams]): MirrorState =
    val vrfHash = checked(VRFVerKeyHash.fromBytes(vrf))
    val registered = parameters.map { p =>
      val native = StakePoolState(
        vrfHash,
        Coin(p.pledge),
        Coin(p.cost),
        p.margin,
        NativeCredential.KeyHashObj(hash[Staking](owner)),
        Sorted.set(p.owners.map(hash[Staking])),
        p.relays,
        p.metadata,
        CompactCoin.fromUnsigned(0),
        Sorted.emptySet
      )
      hash[StakePool](p.operator) -> native
    }
    val counts =
      if parameters.isEmpty then Sorted.empty[VRFVerKeyHash, NonZero[Word64]]
      else
        Sorted.map(
          Vector(vrfHash -> checked(NonZero.from(Word64.fromUnsigned(parameters.size.toLong))))
        )
    val pools = PState(counts, Sorted.map(registered), Sorted.empty, Sorted.empty)
    base.ext.ledgerState.state match
      case current: EraLedgerState.InShelleyEra[e] =>
        given ShelleyBasedEra[e] = current.era
        val ledger = current.state.nes.es.ls
        val next = current.state.nes
          .copy(es = current.state.nes.es.copy(ls = LedgerState.pState[e].replace(pools)(ledger)))
        base.copy(ext =
          base.ext.copy(ledgerState =
            base.ext.ledgerState.withState(
              EraLedgerState.InShelleyEra(current.era, current.state.copy(nes = next))
            )
          )
        )
      case _ => fail("published Conway examples require native Conway state")

  private def answer(parameters: Vector[PoolParams]): Bytes =
    val result = QueryAnswers
      .answer(
        NtcGolden.queryEnv,
        QueryView(ChainSync.Point.Origin, None, state(parameters)),
        Map.empty,
        NtcQuery.IfCurrent(
          Era.Conway.index,
          LedgerQuery.GetStakePoolParams(parameters.map(_.operator).toSet)
        )
      )
      .getOrElse(fail("supported GetStakePoolParams was not answered"))
    array(decoded(result)) match
      case Vector(result) => result.original
      case other          => fail(s"unexpected IfCurrent envelope $other")

  test("both published pool-parameter results match complete production CBOR byte for byte") {
    assertEquals(NtcGolden.queryEnv.mirror.network, 0)
    val result =
      CborOut.array(Vector(answer(Vector.empty), answer(parameters)))((w, value) => w.raw(value))
    assertEquals(result, read("head/queryPoolParameters.cbor"))
    assertNotEquals(result, read("pin/queryPoolParameters.cbor"))
  }

  test("reversing only the independently encoded IPv4 words reproduces the old published golden") {
    val swapped = parameters.map { p =>
      val relays = PoolRelays.decode(p.relays).fold(error => fail(error.toString), identity)
      p.copy(relays = PoolRelays.encode(relays.map {
        case PoolRelay.SingleHostAddr(port, Some(ip), ipv6) =>
          PoolRelay.SingleHostAddr(port, Some(Ipv4(java.lang.Integer.reverseBytes(ip.word))), ipv6)
        case other => other
      }))
    }
    val result =
      CborOut.array(Vector(answer(Vector.empty), answer(swapped)))((w, value) => w.raw(value))
    assertEquals(result, read("pin/queryPoolParameters.cbor"))
    assertNotEquals(result, read("head/queryPoolParameters.cbor"))
  }

  private def publishedRelayIps(path: String): Set[String] =
    entries(array(decoded(read(path)))(1)).flatMap { case (_, row) =>
      PoolRelays
        .decode(array(row)(1).original)
        .fold(error => fail(error.toString), identity)
        .collect { case PoolRelay.SingleHostAddr(_, Some(ip), _) =>
          ip.render
        }
    }.toSet

  test("published relay-query IP octets decode correctly without claiming an unsupported query") {
    assertEquals(
      publishedRelayIps("head/queryStakePoolRelays.cbor"),
      Set("10.0.0.5", "192.168.1.1")
    )
    assertEquals(publishedRelayIps("pin/queryStakePoolRelays.cbor"), Set("5.0.0.10", "1.1.168.192"))
  }

  test("published originals retain their pinned content hashes") {
    val manifest = io.circe.parser
      .parse(Files.readString(root.resolve("manifest.json")))
      .fold(error => fail(error.message), identity)
    val hashes = manifest.hcursor
      .get[Map[String, String]]("files")
      .fold(error => fail(error.message), identity)
    hashes.foreach { case (path, expected) =>
      assertEquals(Blst.sha256Hex(read(path).toArray), expected, path)
    }
  }
