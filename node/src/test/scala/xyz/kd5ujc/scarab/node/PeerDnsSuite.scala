// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Clock, Deferred, IO, Ref, Resource}
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import com.comcast.ip4s.{Host, SocketAddress, host, ipv4, port}
import java.net.InetAddress
import java.util.concurrent.{CompletableFuture, CompletionStage, Executor, Executors}
import java.util.concurrent.atomic.AtomicInteger
import org.xbill.DNS.{ARecord, DClass, EDNSOption, Flags, Message, Resolver, Section, TSIG, Type}
import org.xbill.DNS.lookup.LookupSession
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.{GovernorFault, StorageError}
import xyz.kd5ujc.scarab.network.selection.*

class PeerDnsSuite extends munit.CatsEffectSuite:
  private def name(value: String): IO[DnsName] =
    DnsName.from(value).fold(error => IO.raiseError(error.cause), IO.pure)

  private def refreshLocalCache(
      cache: LocalRootDnsCache.State[NodeRootProvider.LocalDnsKey, NodeRootProvider.Addr],
      keys: Vector[NodeRootProvider.LocalDnsKey],
      entries: Vector[NodeRootProvider.LocalDnsEntry],
      dns: PeerDns[IO],
      rng: Ref[IO, Rng],
      now: FiniteDuration
  ): IO[LocalRootDnsCache.State[NodeRootProvider.LocalDnsKey, NodeRootProvider.Addr]] =
    val due = LocalRootDnsCache.due(cache, keys, now)
    due
      .traverse { request =>
        entries.find(_.key == request.key).traverse { entry =>
          NodeRootProvider.resolveLocalDns(entry, dns, rng).map(request -> _)
        }
      }
      .map(_.flatten)
      .flatMap(_.foldLeftM(cache) { case (current, (request, result)) =>
        LocalRootDnsCache
          .complete(current, request, now, result)
          .fold(error => fail(s"unexpected stale cache request: $error"), IO.pure)
      })

  test("A and AAAA retain successful addresses and each record's clipped TTL") {
    for
      domain <- name("peer.example")
      address <- IO.fromOption(Some(ipv4"192.0.2.10"))(new AssertionError("test address"))
      combined = PeerDns.combineAddresses(
        Right(Vector(DnsAddressRecord(address, 75.seconds))),
        Left(DnsLookupFailure(domain, DnsRecordType.AAAA, new java.io.IOException("no AAAA")))
      )
      _ <- IO(assertEquals(combined.records, Vector(DnsAddressRecord(address, 75.seconds))))
      _ <- IO(assertEquals(combined.failures.map(_.recordType), Vector(DnsRecordType.AAAA)))
    yield ()
  }

  test("positive DNS TTLs are clipped to the reference interval") {
    IO {
      assertEquals(PeerDns.clippedTtl(0L), 60.seconds)
      assertEquals(PeerDns.clippedTtl(60L), 60.seconds)
      assertEquals(PeerDns.clippedTtl(901L), 900.seconds)
    }
  }

  test("a resolver failure stays typed and a decoder defect completes the async boundary") {
    for
      domain <- name("lookup.example")
      resolverFailure = new java.io.IOException("resolver unavailable")
      decodedFailure = new IllegalStateException("record decoder defect")
      failed = PeerDns.lookupCompletion(
        domain,
        DnsRecordType.A,
        Some(resolverFailure),
        Vector.empty[Int]
      )
      defect = PeerDns.lookupCompletion(
        domain,
        DnsRecordType.A,
        None,
        throw decodedFailure
      )
      _ <- IO(
        assertEquals(
          failed,
          Right(Left(DnsLookupFailure(domain, DnsRecordType.A, resolverFailure)))
        )
      )
      _ <- IO(assertEquals(defect, Left(decodedFailure)))
    yield ()
  }

  test("a resolver stage that never completes times out and is canceled") {
    for
      domain <- name("never.example")
      stage = new CompletableFuture[Vector[Int]]()
      result <- TestControl.executeEmbed(
        PeerDns.awaitLookup[IO, Vector[Int], Int](domain, DnsRecordType.A, stage, identity)
      )
      _ <- IO(
        assert(result.swap.exists(_.cause.isInstanceOf[java.util.concurrent.TimeoutException]))
      )
      _ <- IO(assert(stage.isCancelled))
    yield ()
  }

  // issue 567: the reference traces a domain monitor that throws (`TraceLocalRootError`) and
  // starts its monitors again (LocalRootPeers.hs 146-168 of ouroboros-network 2fb69829)
  test("a DNS lookup that throws is traced with its domain, not a stop") {
    for
      dnsCause <- IO.delay(new IllegalStateException("injected DNS defect"))
      good <- name("good-root.example")
      config = RootConfig(
        advertise = true,
        DiffusionMode.InitiatorOnly,
        xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance.Outbound,
        trustable = true
      )
      groups = List(
        RootGroupSpec(
          1,
          1,
          List(
            SocketAddress[Host](host"defect-root.example", port"3100") -> config,
            SocketAddress[Host](host"good-root.example", port"3100") -> config
          )
        )
      )
      roots = PeerNetwork.Roots(groups, Set.empty, None, UseLedgerPeers.Disabled)
      dns = new PeerDns[IO]:
        def addresses(query: DnsName) =
          if query == good then
            IO.pure(
              DnsAddressAnswer(Vector(DnsAddressRecord(ipv4"192.0.2.30", 60.seconds)), Vector.empty)
            )
          else IO.raiseError[DnsAddressAnswer](dnsCause)
        def services(_query: DnsName) = IO.pure(Left(PeerDnsError.Lookup(Vector.empty)))
      cache = LocalRootDnsCache.empty[NodeRootProvider.LocalDnsKey, NodeRootProvider.Addr]
      rng <- Ref.of[IO, Rng](Rng(18L))
      refreshed <- PeerNetwork.refreshLocalDnsCache(roots, cache, dns, rng)
      (kept, dnsError) = refreshed
      _ <- IO(
        assert(dnsError.exists { case LocalRootsTrace.Error(domain, cause) =>
          domain.value == "defect-root.example." && (cause eq dnsCause)
        })
      )
      // the answer of the other name is kept
      _ <- IO(
        assertEquals(
          NodeRootProvider
            .localGroupsFromCache(groups, List.empty, kept)
            .flatMap(_.members.map(_._1))
            .toSet,
          Set(SocketAddress[Host](ipv4"192.0.2.30", port"3100"))
        )
      )
    yield ()
  }

  // issue 586: `jobVerifyPeerSnapshot` is `Job job (const (completion False))` (ouroboros-network
  // 2fb69829 Governor/Monitor.hs 689-712): any failure of the verification is no match
  test(
    "a snapshot verification that throws, or whose store fails, answers no match; never a stop"
  ) {
    val storage = StorageError.Io("immutable", new java.io.IOException("injected read failure"))
    for
      told <- Ref.of[IO, Vector[StorageError]](Vector.empty)
      tell = (error: StorageError) => told.update(_ :+ error)
      matched <- PeerNetwork.snapshotMatches[IO](IO.pure(true), tell)
      thrown <- PeerNetwork.snapshotMatches[IO](
        IO.raiseError[Boolean](new IllegalArgumentException("injected snapshot defect")),
        tell
      )
      afterThrown <- told.get
      failed <- PeerNetwork.snapshotMatches[IO](
        cats.mtl.Raise[IO, StorageError].raise[StorageError, Boolean](storage),
        tell
      )
      afterFailed <- told.get
      queue <- Queue.unbounded[IO, Event[PeerNetwork.Addr]]
      _ <- PeerNetwork.runSnapshotCheck(
        PeerNetwork.snapshotMatches[IO](cats.mtl.Raise[IO, StorageError].raise(storage), tell),
        IO.never,
        BigInt(4),
        queue
      )
      completed <- queue.take
    yield
      assertEquals(matched, true)
      assertEquals(thrown, false)
      // only a failed store is told, with its cause
      assertEquals(afterThrown, Vector.empty)
      assertEquals(failed, false)
      assertEquals(afterFailed, Vector(storage))
      assertEquals(completed, Event.SnapshotVerificationCompleted(BigInt(4), false))
  }

  test("a defect of the local roots' refresh outside its lookups stops the node, cause unchanged") {
    val cause = new java.io.IOException("snapshot index unavailable")
    val defectFatal = PeerNetwork.fatalForGovernor(GovernorFault.LocalDnsRefreshDefect(cause))
    assert(defectFatal.exists {
      case Fatal.Defect(found) => found eq cause
      case _                   => false
    })
  }

  test("refresh interval is the maximum positive TTL, and an SRV TTL replaces address TTLs") {
    for
      domain <- name("service.example")
      ip <- IO.fromOption(Some(ipv4"192.0.2.20"))(new AssertionError("test address"))
      target <- name("target.example")
      service = DnsServiceRecord(0, 1, 3001, target, 75.seconds)
      _ <- IO(
        assertEquals(
          PeerDns.ttlForResults(Vector(60.seconds, 240.seconds, 90.seconds)),
          240.seconds
        )
      )
      _ <- IO(assertEquals(PeerDns.ttlForResults(Vector.empty), 60.seconds))
      _ <- IO(
        assertEquals(
          PeerDns.addressesWithServiceTtl(service, Vector(DnsAddressRecord(ip, 900.seconds))),
          Vector(DnsAddressRecord(ip, 75.seconds))
        )
      )
      _ <- IO(assertEquals(domain.value, "service.example."))
    yield ()
  }

  test("SRV selection filters root targets and only chooses the lowest priority group") {
    for
      dot <- name(".")
      low <- name("low.example")
      other <- name("other.example")
      high <- name("high.example")
      records = Vector(
        DnsServiceRecord(10, 1, 3001, low, 120.seconds),
        DnsServiceRecord(10, 1, 3002, other, 90.seconds),
        DnsServiceRecord(0, 1, 3003, dot, 60.seconds),
        DnsServiceRecord(20, 100, 3004, high, 900.seconds)
      )
      selected <- PeerDns
        .selectService(records, Rng(7L))
        .fold(
          _ => IO.raiseError(new AssertionError("expected a usable SRV service")),
          IO.pure
        )
      (service, next) = selected
      _ <- IO(assert(Set(low, other)(service.target)))
      _ <- IO(assertEquals(service.priority, 10))
      _ <- IO(assert(next != Rng(7L)))
    yield ()
  }

  test("SRV selection uses advertised weights within the preferred priority") {
    for
      light <- name("light.example")
      heavy <- name("heavy.example")
      records = Vector(
        DnsServiceRecord(3, 1, 3001, light, 60.seconds),
        DnsServiceRecord(3, 9, 3002, heavy, 60.seconds)
      )
      selected = (0L until 100L).toVector.map { seed =>
        PeerDns.selectService(records, Rng(seed)).map(_._1.target)
      }
      heavyCount = selected.count(_.contains(heavy))
      _ <- IO(assert(heavyCount >= 70 && heavyCount < 100))
    yield ()
  }

  // `randomR (0, upperBound)` on a `Word16` (`DNSActions.hs` 470, ouroboros-network `2fb69829`) is
  // random-1.3.1's `unbiasedWordMult32RM`: weights 1 and 2 draw from [0, 3], the top two bits of
  // the word's low half (2^32 mod 4 is 0, so none is rejected); a draw of 0 or 1 is the first
  test("SRV selection draws the weight as random draws a Word16: the word's low half, multiplied") {
    for
      first <- name("first.example")
      second <- name("second.example")
      records = Vector(
        DnsServiceRecord(1, 1, 3001, first, 60.seconds),
        DnsServiceRecord(1, 2, 3002, second, 60.seconds)
      )
      _ <- IO((0L until 64L).foreach { seed =>
        val (word, next) = Rng(seed).nextLong
        val draw = ((word & 0xffffffffL) * 4L) >>> 32
        assertEquals(
          PeerDns.selectService(records, Rng(seed)).map((s, g) => (s.target, g)),
          Right((if draw <= 1L then first else second, next)),
          s"seed $seed"
        )
      })
    yield ()
  }

  test("SRV lookup with only root targets has no usable service") {
    name(".").flatMap { dot =>
      IO(
        assertEquals(
          PeerDns.selectService(Vector(DnsServiceRecord(0, 0, 3001, dot, 60.seconds)), Rng(0L)),
          Left(DnsServiceSelectionError.NoUsableService)
        )
      )
    }
  }

  test("root resolution retains every address and maps advertise from the source hostname") {
    for
      domain <- name("relay.example")
      alias <- name("alias.example")
      first <- IO.fromOption(Some(ipv4"192.0.2.30"))(new AssertionError("first address"))
      second <- IO.fromOption(Some(ipv4"192.0.2.31"))(new AssertionError("second address"))
      dns = new PeerDns[IO]:
        def addresses(query: DnsName) = IO.pure(
          DnsAddressAnswer(
            if query == domain || query == alias then
              Vector(
                DnsAddressRecord(first, 60.seconds),
                DnsAddressRecord(second, 240.seconds)
              )
            else Vector.empty,
            Vector.empty
          )
        )
        def services(_query: DnsName) =
          IO.pure(Left(PeerDnsError.Lookup(Vector.empty)))
      rng <- Ref.of[IO, Rng](Rng(9L))
      root = SocketAddress[Host](host"relay.example", port"3001")
      aliasRoot = SocketAddress[Host](host"alias.example", port"3001")
      resolved <- NodeRootProvider.resolveRoots(Set(root, aliasRoot), Vector.empty, dns, rng)
      (addresses, ttl, source) = resolved
      _ <- IO(
        assertEquals(
          addresses,
          Set(SocketAddress[Host](first, root.port), SocketAddress[Host](second, root.port))
        )
      )
      _ <- IO(assertEquals(ttl, 240.seconds))
      _ <- IO(
        assertEquals(
          source,
          addresses
            .map(
              _ -> Set(
                NodeRootProvider.RootOrigin.Host(root),
                NodeRootProvider.RootOrigin.Host(aliasRoot)
              )
            )
            .toMap
        )
      )
    yield ()
  }

  test("literal IP roots do not mask the DNS refresh TTL of hostname roots") {
    for
      address <- IO.fromOption(Some(ipv4"192.0.2.60"))(new AssertionError("DNS address"))
      dns = new PeerDns[IO]:
        def addresses(_query: DnsName) = IO.pure(
          DnsAddressAnswer(Vector(DnsAddressRecord(address, 60.seconds)), Vector.empty)
        )
        def services(_query: DnsName) = IO.pure(Left(PeerDnsError.Lookup(Vector.empty)))
      rng <- Ref.of[IO, Rng](Rng(13L))
      named = SocketAddress[Host](host"relay.example", port"3001")
      fixed = SocketAddress[Host](ipv4"192.0.2.61", port"3001")
      resolved <- NodeRootProvider.resolveRoots(Set(named, fixed), Vector.empty, dns, rng)
      _ <- IO(assertEquals(resolved._1.size, 2))
      _ <- IO(assertEquals(resolved._2, 60.seconds))
    yield ()
  }

  test("SRV root resolution uses the selected service port and TTL for every target address") {
    for
      serviceName <- name("_cardano._tcp.pool.example")
      target <- name("target.example")
      address <- IO.fromOption(Some(ipv4"192.0.2.40"))(new AssertionError("target address"))
      dns = new PeerDns[IO]:
        def addresses(_query: DnsName) = IO.pure(
          DnsAddressAnswer(
            Vector(DnsAddressRecord(address, 900.seconds)),
            Vector.empty
          )
        )
        def services(_query: DnsName) =
          IO.pure(
            Right(Vector(DnsServiceRecord(0, 1, 3005, target, 75.seconds)))
          )
      rng <- Ref.of[IO, Rng](Rng(11L))
      resolved <- NodeRootProvider.resolveService(serviceName, dns, rng)
      expected = SocketAddress[Host](address, port"3005")
      _ <- IO(assertEquals(resolved, Vector(expected) -> Vector(75.seconds)))
      public <- NodeRootProvider.resolveRoots(
        Set.empty,
        Vector(serviceName -> true),
        dns,
        rng
      )
      _ <- IO(assertEquals(public._1, Set(expected)))
      _ <- IO(
        assertEquals(
          public._3,
          Map(expected -> Set(NodeRootProvider.RootOrigin.Service(serviceName, true)))
        )
      )
    yield ()
  }

  test(
    "repeated SRV domains keep the advertise flag of the service entry that resolved each peer"
  ) {
    for
      serviceName <- name("_cardano._tcp.pool.example")
      privateTarget <- name("private-target.example")
      publicTarget <- name("public-target.example")
      privateAddress <- IO.fromOption(Some(ipv4"192.0.2.70"))(new AssertionError("private address"))
      publicAddress <- IO.fromOption(Some(ipv4"192.0.2.71"))(new AssertionError("public address"))
      serviceCall <- Ref.of[IO, Int](0)
      dns = new PeerDns[IO]:
        def addresses(query: DnsName) = IO.pure(
          DnsAddressAnswer(
            Vector(
              DnsAddressRecord(
                if query == privateTarget then privateAddress else publicAddress,
                120.seconds
              )
            ),
            Vector.empty
          )
        )
        def services(_query: DnsName) =
          serviceCall.getAndUpdate(_ + 1).map { call =>
            val target = if call == 0 then privateTarget else publicTarget
            Right(Vector(DnsServiceRecord(0, 1, 3001, target, 75.seconds)))
          }
      rng <- Ref.of[IO, Rng](Rng(14L))
      resolved <- NodeRootProvider.resolveRoots(
        Set.empty,
        Vector(serviceName -> false, serviceName -> true),
        dns,
        rng
      )
      (addresses, _, source) = resolved
      privatePeer = SocketAddress[Host](privateAddress, port"3001")
      publicPeer = SocketAddress[Host](publicAddress, port"3001")
      _ <- IO(assertEquals(addresses, Set(privatePeer, publicPeer)))
      _ <- IO(
        assertEquals(
          source,
          Map(
            privatePeer -> Set(NodeRootProvider.RootOrigin.Service(serviceName, false)),
            publicPeer -> Set(NodeRootProvider.RootOrigin.Service(serviceName, true))
          )
        )
      )
      _ <- IO(
        assertEquals(
          NodeRootProvider.advertiseFromSources(source, Map.empty),
          Map(publicPeer -> true)
        )
      )
    yield ()
  }

  test(
    "an SRV target with a successful empty address answer uses the empty-answer refresh policy"
  ) {
    for
      domain <- name("relay.example")
      target <- name("target.example")
      config = RootConfig(
        advertise = false,
        DiffusionMode.InitiatorOnly,
        xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance.Outbound,
        trustable = false
      )
      dns = new PeerDns[IO]:
        def addresses(_query: DnsName) = IO.pure(DnsAddressAnswer(Vector.empty, Vector.empty))
        def services(_query: DnsName) = IO.pure(
          Right(Vector(DnsServiceRecord(0, 1, 3001, target, 300.seconds)))
        )
      rng <- Ref.of[IO, Rng](Rng(1L))
      entry = NodeRootProvider.LocalDnsEntry(
        NodeRootProvider.LocalDnsKey(
          0,
          0,
          NodeRootProvider.LocalDnsInput.Service(domain)
        ),
        config
      )
      result <- NodeRootProvider.resolveLocalDns(entry, dns, rng)
    yield assertEquals(result, LocalRootDnsCache.LookupResult.Success(Vector.empty, Vector.empty))
  }

  test("local DNS refresh retains group valencies and keeps aliases in their source groups") {
    for
      firstName <- name("first.example")
      aliasName <- name("alias.example")
      first <- IO.fromOption(Some(ipv4"192.0.2.50"))(new AssertionError("first address"))
      second <- IO.fromOption(Some(ipv4"192.0.2.51"))(new AssertionError("second address"))
      answers <- Ref.of[IO, Map[DnsName, DnsAddressAnswer]](
        Map(
          firstName -> DnsAddressAnswer(Vector(DnsAddressRecord(first, 120.seconds)), Vector.empty),
          aliasName -> DnsAddressAnswer(Vector(DnsAddressRecord(first, 120.seconds)), Vector.empty)
        )
      )
      port = port"3100"
      firstConfig = RootConfig(
        advertise = true,
        DiffusionMode.InitiatorOnly,
        xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance.Inbound,
        trustable = true
      )
      secondConfig = firstConfig.copy(advertise = false, trustable = false)
      dns = new PeerDns[IO]:
        def addresses(query: DnsName) =
          answers.get.map(_.getOrElse(query, DnsAddressAnswer(Vector.empty, Vector.empty)))
        def services(_query: DnsName) = IO.pure(Left(PeerDnsError.Lookup(Vector.empty)))
      rng <- Ref.of[IO, Rng](Rng(12L))
      groups = List(
        RootGroupSpec(1, 2, List(SocketAddress[Host](host"first.example", port) -> firstConfig)),
        RootGroupSpec(2, 3, List(SocketAddress[Host](host"alias.example", port) -> secondConfig))
      )
      services = List(Nil, Nil)
      entries = NodeRootProvider.localDnsEntries(groups, services)
      keys = entries.map(_.key)
      initialCache = LocalRootDnsCache.track(
        LocalRootDnsCache.empty[NodeRootProvider.LocalDnsKey, NodeRootProvider.Addr],
        keys,
        Duration.Zero
      )
      cache1 <- refreshLocalCache(initialCache, keys, entries, dns, rng, Duration.Zero)
      resolved = NodeRootProvider.localGroupsFromCache(groups, services, cache1)
      sameAddress = SocketAddress[Host](first, port)
      _ <- IO(assertEquals(resolved.map(group => (group.hot, group.warm)), List(1 -> 2, 2 -> 3)))
      _ <- IO(
        assertEquals(
          resolved.map(_.members.map(_._1).toSet),
          List(Set(sameAddress), Set(sameAddress))
        )
      )
      _ <- IO(assertEquals(resolved.map(_.members.head._2), List(firstConfig, secondConfig)))
      _ <- answers.update(
        _.updated(
          firstName,
          DnsAddressAnswer(Vector(DnsAddressRecord(second, 120.seconds)), Vector.empty)
        ).updated(
          aliasName,
          DnsAddressAnswer(
            Vector.empty,
            Vector(
              DnsLookupFailure(aliasName, DnsRecordType.A, new java.io.IOException("temporary"))
            )
          )
        )
      )
      cache2 <- refreshLocalCache(cache1, keys, entries, dns, rng, 120.seconds)
      afterPartialFailure = NodeRootProvider.localGroupsFromCache(groups, services, cache2)
      newAddress = SocketAddress[Host](second, port)
      _ <- IO(
        assertEquals(
          afterPartialFailure.map(_.members.map(_._1).toSet),
          List(Set(newAddress), Set(sameAddress))
        )
      )
      _ <- answers.update(_.updated(firstName, DnsAddressAnswer(Vector.empty, Vector.empty)))
      cache3 <- refreshLocalCache(cache2, keys, entries, dns, rng, 240.seconds)
      afterEmpty <- IO.pure(NodeRootProvider.localGroupsFromCache(groups, services, cache3))
      _ <- IO(
        assertEquals(afterEmpty.map(_.members.map(_._1).toSet), List(Set.empty, Set(sameAddress)))
      )
    yield ()
  }

  test("a changed local DNS name cannot inherit the prior source's cached addresses") {
    for
      oldName <- name("old-root.example")
      newName <- name("new-root.example")
      address <- IO.fromOption(Some(ipv4"192.0.2.53"))(new AssertionError("cached address"))
      config = RootConfig(
        advertise = true,
        DiffusionMode.InitiatorOnly,
        xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance.Outbound,
        trustable = true
      )
      oldGroups = List(
        RootGroupSpec(
          1,
          1,
          List(SocketAddress[Host](host"old-root.example", port"3100") -> config)
        )
      )
      newGroups = List(
        RootGroupSpec(
          1,
          1,
          List(SocketAddress[Host](host"new-root.example", port"3100") -> config)
        )
      )
      oldEntry = NodeRootProvider.localDnsEntries(oldGroups, Nil).head
      oldCache = LocalRootDnsCache.track(
        LocalRootDnsCache.empty[NodeRootProvider.LocalDnsKey, NodeRootProvider.Addr],
        Vector(oldEntry.key),
        Duration.Zero
      )
      oldRequest = LocalRootDnsCache.due(oldCache, Vector(oldEntry.key), Duration.Zero).head
      populated <- LocalRootDnsCache
        .complete(
          oldCache,
          oldRequest,
          Duration.Zero,
          LocalRootDnsCache.LookupResult.Success(
            Vector(SocketAddress[Host](address, port"3100")),
            Vector(60.seconds)
          )
        )
        .fold(error => fail(s"unexpected cache completion failure: $error"), IO.pure)
      newEntry = NodeRootProvider.localDnsEntries(newGroups, Nil).head
      trackedNew = LocalRootDnsCache.track(populated, Vector(newEntry.key), 1.second)
      _ <- IO(assertNotEquals(oldName, newName))
      _ <- IO(assertNotEquals(oldEntry.key, newEntry.key))
      _ <- IO(
        assertEquals(
          LocalRootDnsCache.snapshot(trackedNew, newEntry.key).map(_.values),
          Some(Vector.empty)
        )
      )
      _ <- IO(
        assertEquals(
          LocalRootDnsCache.complete(
            trackedNew,
            oldRequest,
            2.seconds,
            LocalRootDnsCache.LookupResult.Success(Vector.empty, Vector.empty)
          ),
          Left(LocalRootDnsCache.CacheError.StaleRequest)
        )
      )
    yield ()
  }

  test(
    "a refresh installs successful sources and retains failed sources in one Runtime generation"
  ) {
    for
      firstName <- name("runtime-first.example")
      aliasName <- name("runtime-alias.example")
      first <- IO.fromOption(Some(ipv4"192.0.2.80"))(new AssertionError("first address"))
      moved <- IO.fromOption(Some(ipv4"192.0.2.82"))(new AssertionError("moved address"))
      answers <- Ref.of[IO, Map[DnsName, DnsAddressAnswer]](
        Map(
          firstName -> DnsAddressAnswer(Vector(DnsAddressRecord(first, 60.seconds)), Vector.empty),
          aliasName -> DnsAddressAnswer(Vector(DnsAddressRecord(first, 60.seconds)), Vector.empty)
        )
      )
      dns = new PeerDns[IO]:
        def addresses(query: DnsName) =
          answers.get.map(_.getOrElse(query, DnsAddressAnswer(Vector.empty, Vector.empty)))
        def services(_query: DnsName) = IO.pure(Left(PeerDnsError.Lookup(Vector.empty)))
      port = port"3200"
      config = RootConfig(
        advertise = true,
        DiffusionMode.InitiatorOnly,
        xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance.Outbound,
        trustable = true
      )
      groups = List(
        RootGroupSpec(1, 1, List(SocketAddress[Host](host"runtime-first.example", port) -> config)),
        RootGroupSpec(1, 1, List(SocketAddress[Host](host"runtime-alias.example", port) -> config))
      )
      services = List(Nil, Nil)
      entries = NodeRootProvider.localDnsEntries(groups, services)
      keys = entries.map(_.key)
      now <- Clock[IO].monotonic
      past = now - 1.hour
      empty = LocalRootDnsCache.track(
        LocalRootDnsCache.empty[NodeRootProvider.LocalDnsKey, NodeRootProvider.Addr],
        keys,
        past
      )
      seedRng <- Ref.of[IO, Rng](Rng(4L))
      seeded <- refreshLocalCache(empty, keys, entries, dns, seedRng, past)
      _ <- answers.update(
        _.updated(
          firstName,
          DnsAddressAnswer(Vector(DnsAddressRecord(moved, 60.seconds)), Vector.empty)
        ).updated(
          aliasName,
          DnsAddressAnswer(
            Vector.empty,
            Vector(
              DnsLookupFailure(aliasName, DnsRecordType.A, new java.io.IOException("temporary"))
            )
          )
        )
      )
      rng <- Ref.of[IO, Rng](Rng(5L))
      refreshed <- PeerNetwork
        .refreshLocalDnsCache(
          PeerNetwork.Roots(groups, Set.empty, None, UseLedgerPeers.Disabled, localSrv = services),
          seeded,
          dns,
          rng
        )
        .map(_._1)
      roots = PeerNetwork.Roots(
        groups,
        Set.empty,
        None,
        UseLedgerPeers.Disabled,
        localSrv = services
      )
      resolved = NodeRootProvider.localGroupsFromCache(groups, services, refreshed)
      worker <- {
        given cats.mtl.Tell[IO, LedgerPeersTrace] = new cats.mtl.Tell[IO, LedgerPeersTrace]:
          def functor = cats.Functor[IO]
          def tell(_trace: LedgerPeersTrace) = IO.unit
        LedgerPeersWorker.create[IO](
          UseLedgerPeers.Disabled,
          LedgerPeersSource.none[IO],
          None,
          Rng(6L)
        )
      }
      canceled <- Deferred[IO, Unit]
      nextCanceled <- Deferred[IO, Unit]
      initial = PeerNetwork.Runtime[IO](
        BigInt(0),
        roots,
        NodeRootProvider.localGroupsFromCache(groups, services, seeded),
        seeded,
        worker,
        AssociationMode.LocalRootsOnly,
        ChurnLoop.Config(base = Targets.relay, regime = Regime.kind(_, false)),
        snapshotCheckStarted = true,
        snapshotCheckCanceled = canceled
      )
      staged = initial.copy(
        generation = BigInt(1),
        resolvedLocalRoots = resolved,
        localDnsCache = refreshed,
        snapshotCheckStarted = false,
        snapshotCheckCanceled = nextCanceled
      )
      current <- Ref.of[IO, PeerNetwork.Runtime[IO]](initial)
      installed <- PeerNetwork.installRuntimeGeneration(current, staged)
      actual <- current.get
      wasCanceled <- canceled.tryGet
      expectedNew = SocketAddress[Host](moved, port)
      expectedRetained = SocketAddress[Host](first, port)
      _ <- IO(assert(installed))
      _ <- IO(assertEquals(wasCanceled, Some(())))
      _ <- IO(assertEquals(actual.generation, BigInt(1)))
      _ <- IO(
        assertEquals(
          actual.resolvedLocalRoots.map(_.members.map(_._1).toSet),
          List(Set(expectedNew), Set(expectedRetained))
        )
      )
      _ <- IO(assertEquals(actual.localDnsCache, refreshed))
    yield ()
  }

  // D8 of issue 956: the reference keeps no DNS cache of its own (`resolvCache = Nothing` in the
  // dns-4.2.0 `defaultResolvConf` that ouroboros-network c45735a5 resolves roots with), so a lookup
  // within a record's TTL asks the resolver again, where dnsjava's default session would answer it
  // from its cache until the TTL ran out
  test("a lookup within a record's TTL asks the resolver again: the session keeps no DNS cache") {
    val executor =
      Resource.make(IO(Executors.newSingleThreadExecutor()))(e => IO(e.shutdownNow()).void)
    executor.use { lookups =>
      for
        domain <- name("cached.example")
        resolver = PeerDnsSuite.Answering(
          InetAddress.getByAddress(Array[Byte](-64, 0, 2, 10)),
          3600L
        )
        session <- IO(PeerDns.uncached(LookupSession.defaultBuilder().resolver(resolver), lookups))
        dns = PeerDns.fromSession[IO](session)
        first <- dns.addresses(domain)
        second <- dns.addresses(domain)
        asked <- IO(resolver.asked.get)
        expected = Vector(DnsAddressRecord(ipv4"192.0.2.10", 900.seconds))
        _ <- IO(assertEquals(first.records, expected, "the first answer, its TTL clipped"))
        _ <- IO(assertEquals(second.records, expected, "the second answer"))
        _ <- IO(assertEquals(asked, 2, "A queries that reached the resolver"))
      yield ()
    }
  }

object PeerDnsSuite:
  /** A resolver that answers an A query for any name with `address` at `ttl` seconds, and any other
    * query with no records, and counts the A queries it is asked. dnsjava's `Resolver` is a
    * callback interface that reports by a returned stage, so the count is an atomic held by the
    * resolver.
    */
  final class Answering(address: InetAddress, ttl: Long) extends Resolver:
    val asked: AtomicInteger = new AtomicInteger(0)
    def setPort(port: Int): Unit = ()
    def setTCP(flag: Boolean): Unit = ()
    def setIgnoreTruncation(flag: Boolean): Unit = ()
    def setEDNS(
        version: Int,
        payloadSize: Int,
        flags: Int,
        options: java.util.List[EDNSOption]
    ): Unit =
      ()
    def setTSIGKey(key: TSIG): Unit = ()
    def setTimeout(timeout: java.time.Duration): Unit = ()
    override def sendAsync(query: Message): CompletionStage[Message] =
      sendAsync(query, (task: Runnable) => task.run())
    override def sendAsync(query: Message, executor: Executor): CompletionStage[Message] =
      val question = query.getQuestion
      val reply = new Message(query.getHeader.getID)
      reply.getHeader.setFlag(Flags.QR)
      reply.addRecord(question, Section.QUESTION)
      if question.getType == Type.A then
        asked.incrementAndGet()
        reply.addRecord(new ARecord(question.getName, DClass.IN, ttl, address), Section.ANSWER)
      CompletableFuture.completedFuture(reply)
