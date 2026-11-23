// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Ipv4Address, Port, SocketAddress}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.{Ipv4, LedgerRelay, RelayPort}
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.network.selection.Rng

/** The ledger relays the worker drew, resolved as the reference resolves them: one address drawn at
  * random from each drawn domain's addresses (`pickDomainAddrs`, `LedgerPeers.hs` 359-368 at
  * ouroboros-network 4646039f), the domains keyed as the ledger spells them, an SRV domain always
  * prefixed, one target generator for every SRV lookup of a request, and every IP relay as it is.
  */
class LedgerRelayResolutionSuite extends FailsOnUncaught:
  private type Addr = NodeRootProvider.Addr

  private def name(value: String): DnsName = DnsName.from(value).toOption.get
  private def port(n: Int): RelayPort = RelayPort.fromInt(n).get
  private def ip(i: Int): Ipv4Address =
    Ipv4Address.fromBytes(10, (i >> 16) & 255, (i >> 8) & 255, i & 255)
  private def at(i: Int, p: Int): Addr = SocketAddress[Host](ip(i), Port.fromInt(p).get)

  private def records(from: Int, count: Int, ttl: FiniteDuration = 900.seconds) =
    Vector.tabulate(count)(i => DnsAddressRecord(ip(from + i), ttl))

  private def dns(
      answers: Map[DnsName, Vector[DnsAddressRecord]],
      serviceAnswers: Map[DnsName, Vector[DnsServiceRecord]] = Map.empty
  ): PeerDns[IO] = new PeerDns[IO]:
    def addresses(query: DnsName) =
      IO.pure(DnsAddressAnswer(answers.getOrElse(query, Vector.empty), Vector.empty))
    def services(query: DnsName) =
      IO.pure(serviceAnswers.get(query).toRight(PeerDnsError.Lookup(Vector.empty)))

  private def resolve(relays: Vector[LedgerRelay], peers: PeerDns[IO], seed: Long = 1L) =
    Ref.of[IO, Rng](Rng(seed)).flatMap(NodeRootProvider.resolveLedger[IO](relays, peers, _))

  private def domain(value: String, p: Int): LedgerRelay = LedgerRelay.Domain(value, port(p))

  private def service(target: String, p: Int, weight: Int = 1) =
    DnsServiceRecord(priority = 0, weight = weight, port = p, name(target), ttl = 300.seconds)

  private val failure =
    DnsLookupFailure(name("relay.example"), DnsRecordType.A, new java.io.IOException("no answer"))

  /** A resolver whose answers to each name are given in turn, the last one repeated, and that
    * records the names it is asked: the address queries and the SRV queries.
    */
  private def scripted(
      answers: Map[DnsName, Vector[DnsAddressAnswer]],
      serviceAnswers: Map[DnsName, Vector[DnsServiceRecord]] = Map.empty
  ): IO[(PeerDns[IO], IO[Vector[DnsName]], IO[Vector[DnsName]])] =
    (Ref.of[IO, Vector[DnsName]](Vector.empty), Ref.of[IO, Vector[DnsName]](Vector.empty)).mapN {
      (asked, askedServices) =>
        val peers = new PeerDns[IO]:
          def addresses(query: DnsName) =
            asked.modify { before =>
              val turn = before.count(_ == query)
              val script = answers.getOrElse(query, Vector.empty)
              (before :+ query) -> script
                .lift(turn)
                .orElse(script.lastOption)
                .getOrElse(DnsAddressAnswer(Vector.empty, Vector.empty))
            }
          def services(query: DnsName) =
            askedServices
              .update(_ :+ query)
              .as(serviceAnswers.get(query).toRight(PeerDnsError.Lookup(Vector.empty)))
        (peers, asked.get, askedServices.get)
    }

  test("a drawn name gives one of its addresses, however many its answer holds"):
    val all = records(0, 4000)
    resolve(Vector(domain("relay.example", 3001)), dns(Map(name("relay.example") -> all)))
      .map { addresses =>
        assertEquals(addresses.size, 1, addresses.toString)
        assert(addresses.subsetOf((0 until 4000).map(at(_, 3001)).toSet), addresses.toString)
      }

  test("every drawn relay of one name shares one draw, whatever its port"):
    val answer = records(0, 3)
    val candidates = (0 until 3).flatMap(i => Vector(at(i, 3001), at(i, 3002))).toSet
    resolve(
      Vector(domain("relay.example", 3001), domain("relay.example", 3002)),
      dns(Map(name("relay.example") -> answer))
    ).map { addresses =>
      assertEquals(addresses.size, 1, addresses.toString)
      assert(addresses.subsetOf(candidates), addresses.toString)
    }

  test("each distinct drawn name gives one address, and an IP relay is taken as it is"):
    val ipRelays = Vector(
      LedgerRelay.Address(Ipv4(0x0a630001), port(3001)),
      LedgerRelay.Address(Ipv4(0x0a630002), port(3001))
    )
    resolve(
      Vector(domain("a.example", 3001), domain("b.example", 3001)) ++ ipRelays,
      dns(Map(name("a.example") -> records(0, 10), name("b.example") -> records(100, 10)))
    ).map { addresses =>
      val fromA = (0 until 10).map(at(_, 3001)).toSet
      val fromB = (100 until 110).map(at(_, 3001)).toSet
      val fixed = Set(
        SocketAddress[Host](Ipv4Address.fromBytes(10, 99, 0, 1), Port.fromInt(3001).get),
        SocketAddress[Host](Ipv4Address.fromBytes(10, 99, 0, 2), Port.fromInt(3001).get)
      )
      assertEquals(addresses.intersect(fromA).size, 1, addresses.toString)
      assertEquals(addresses.intersect(fromB).size, 1, addresses.toString)
      assertEquals(addresses.intersect(fixed), fixed)
      assertEquals(addresses.size, 4, addresses.toString)
    }

  test("an SRV name gives one address of the target its lookup chose"):
    val target = name("relay1.pool.example")
    val service = DnsServiceRecord(priority = 0, weight = 1, port = 4000, target, ttl = 300.seconds)
    resolve(
      Vector(LedgerRelay.Srv("pool.example")),
      dns(
        Map(target -> records(0, 50)),
        Map(name("_cardano._tcp.pool.example") -> Vector(service))
      )
    ).map { addresses =>
      assertEquals(addresses.size, 1, addresses.toString)
      assert(addresses.subsetOf((0 until 50).map(at(_, 4000)).toSet), addresses.toString)
    }

  test("a drawn name whose lookup gives nothing adds no address"):
    resolve(
      Vector(domain("gone.example", 3001), domain("relay.example", 3001)),
      dns(Map(name("relay.example") -> records(0, 5)))
    ).map(addresses => assertEquals(addresses.size, 1, addresses.toString))

  test("the address is drawn uniformly from the distinct addresses of the answer"):
    // nine copies of one record and one of another: the second is drawn about half the time
    val answer = Vector.fill(9)(DnsAddressRecord(ip(1), 60.seconds)) :+
      DnsAddressRecord(ip(2), 60.seconds)
    val peers = dns(Map(name("relay.example") -> answer))
    (0L until 200L).toList
      .traverse(seed => resolve(Vector(domain("relay.example", 3001)), peers, seed))
      .map { draws =>
        val others = draws.count(_.size != 1)
        assertEquals(others, 0, s"$others of 200 resolutions gave other than one address")
        val second = draws.count(_ == Set(at(2, 3001)))
        assert(second >= 70 && second <= 130, s"the second address was drawn $second times of 200")
      }

  test("two spellings of one host name, with and without the final dot, are two drawn domains"):
    // both spellings ask for the same name, which answers with one address
    resolve(
      Vector(domain("relay.example", 3001), domain("relay.example.", 3002)),
      dns(Map(name("relay.example") -> records(0, 1)))
    ).map(addresses => assertEquals(addresses, Set(at(0, 3001), at(0, 3002))))

  test("two spellings of one SRV domain, with and without the final dot, are two drawn domains"):
    val target = "relay1.pool.example"
    val answer = Map(name(target) -> records(0, 1000))
    val services = Map(name("_cardano._tcp.pool.example") -> Vector(service(target, 4000)))
    for
      once <- resolve(Vector(LedgerRelay.Srv("pool.example")), dns(answer, services), seed = 3L)
      twice <- resolve(
        Vector(LedgerRelay.Srv("pool.example"), LedgerRelay.Srv("pool.example.")),
        dns(answer, services),
        seed = 3L
      )
    yield
      assertEquals(once.size, 1, once.toString)
      assertEquals(twice.size, 2, twice.toString)
      assert(twice.subsetOf((0 until 1000).map(at(_, 4000)).toSet), twice.toString)

  test("a ledger SRV domain is always prefixed, even one that starts with the prefix"):
    scripted(Map.empty)
      .flatMap { (peers, _, askedServices) =>
        resolve(Vector(LedgerRelay.Srv("_cardano._tcp.pool.example")), peers) *> askedServices
      }
      .map(asked => assertEquals(asked, Vector(name("_cardano._tcp._cardano._tcp.pool.example"))))

  test("every SRV lookup of one request chooses its target with the same generator"):
    // two targets of equal weight, one of one address and one of nine: the domain drawn three
    // times asks for one target three times, whatever the seed
    val services = Map(
      name("_cardano._tcp.pool.example") ->
        Vector(service("one.pool.example", 4000), service("nine.pool.example", 4001))
    )
    val answers = Map(
      name("one.pool.example") -> Vector(DnsAddressAnswer(records(0, 1), Vector.empty)),
      name("nine.pool.example") -> Vector(DnsAddressAnswer(records(100, 9), Vector.empty))
    )
    (0L until 20L).toList
      .traverse { seed =>
        scripted(answers, services).flatMap { (peers, asked, _) =>
          resolve(Vector.fill(3)(LedgerRelay.Srv("pool.example")), peers, seed)
            .flatMap(result => asked.map(seed -> (result, _)))
        }
      }
      .map { runs =>
        val mixed = runs.filter { case (_, (_, targets)) => targets.distinct.size != 1 }
        assertEquals(mixed, Nil)
        assert(runs.forall { case (_, (addresses, _)) => addresses.size == 1 }, runs.toString)
      }

  test("a drawn host name's failed lookup adds nothing to its successful one, in either order"):
    val good = DnsAddressAnswer(records(0, 3), Vector.empty)
    val bad = DnsAddressAnswer(Vector.empty, Vector(failure))
    val candidates = (0 until 3).map(at(_, 3001)).toSet
    val twice = Vector.fill(2)(domain("relay.example", 3001))
    for
      failedFirst <- scripted(Map(name("relay.example") -> Vector(bad, good)))
        .flatMap((peers, _, _) => resolve(twice, peers))
      failedLast <- scripted(Map(name("relay.example") -> Vector(good, bad)))
        .flatMap((peers, _, _) => resolve(twice, peers))
      failedBoth <- scripted(Map(name("relay.example") -> Vector(bad)))
        .flatMap((peers, _, _) => resolve(twice, peers))
    yield
      assertEquals(failedFirst.size, 1, failedFirst.toString)
      assert(failedFirst.subsetOf(candidates), failedFirst.toString)
      assertEquals(failedLast.size, 1, failedLast.toString)
      assert(failedLast.subsetOf(candidates), failedLast.toString)
      assertEquals(failedBoth, Set.empty[Addr])

  test("a drawn SRV domain's failed lookup adds nothing to its successful one"):
    val target = name("relay1.pool.example")
    val query = name("_cardano._tcp.pool.example")
    val failing = Ref.of[IO, Int](0).map { calls =>
      new PeerDns[IO]:
        def addresses(q: DnsName) =
          IO.pure(
            DnsAddressAnswer(if q == target then records(0, 4) else Vector.empty, Vector.empty)
          )
        def services(q: DnsName) =
          calls.getAndUpdate(_ + 1).map { n =>
            if n == 0 then Left(PeerDnsError.Lookup(Vector(failure)))
            else Right(Vector(service("relay1.pool.example", 4000)).filter(_ => q == query))
          }
    }
    failing
      .flatMap(peers => resolve(Vector.fill(2)(LedgerRelay.Srv("pool.example")), peers))
      .map { addresses =>
        assertEquals(addresses.size, 1, addresses.toString)
        assert(addresses.subsetOf((0 until 4).map(at(_, 4000)).toSet), addresses.toString)
      }
