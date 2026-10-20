// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.mtl.Handle
import com.comcast.ip4s.{Host, SocketAddress, host, ipv4, port}
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.NodeError
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** Observable reload ordering from ouroboros-network 6a49b6f6. DNS gates stay blocked until
  * assertions release them: success cannot come from a lookup timeout or a wall-clock race.
  */
class PeerRootReloadSuite extends FailsOnUncaught:
  import PeerNetwork.given

  private val oldConfig = RootConfig(false, DiffusionMode.InitiatorOnly, Provenance.Outbound, false)
  private val nextConfig =
    RootConfig(true, DiffusionMode.InitiatorAndResponder, Provenance.Inbound, true)
  private val cached = SocketAddress[Host](ipv4"192.0.2.21", port"3001")
  private val fixed = SocketAddress[Host](ipv4"192.0.2.22", port"3001")
  private val moved = SocketAddress[Host](ipv4"192.0.2.23", port"3001")
  private val pending = SocketAddress[Host](ipv4"192.0.2.24", port"3001")
  private val cachedHost = SocketAddress[Host](host"cached.example", port"3001")
  private val pendingHost = SocketAddress[Host](host"pending.example", port"3001")

  private def roots(members: List[(PeerNetwork.Addr, RootConfig)], hot: Int = 1, warm: Int = 1) =
    PeerNetwork.Roots(
      List(RootGroupSpec(hot, warm, members)),
      Set.empty,
      None,
      UseLedgerPeers.Disabled
    )

  private def running(roots: PeerNetwork.Roots)(using
      Resource[IO, PeerDns[IO]]
  ): Resource[IO, PeerNetwork.Running[IO, PeerNetwork.Addr]] =
    NodeScope.resource.flatMap { node =>
      given Handle[IO, NodeError] = node
      for
        transport <- Resource.eval(InMemoryPeers.create(42L))
        manager <- Resource.eval(ConnectionManager.create[IO]())
        pool <- Resource.eval(BlockPool.create[IO])
        mode <- Resource.eval(Ref.of[IO, FetchMode](FetchMode.BulkSync))
        hot = OutboundPeers.Hot[IO](
          magic = 42L,
          network = NodeToNode.Settings(),
          peer = Peer.Settings(fixed, 42L, byronEpochSlots = ForgedChain.geometry.byron.epochSlots),
          follow = (_, _) => IO.never,
          prepare = _ => IO.unit,
          headers = None,
          transactions = None,
          observe = None,
          pool = pool,
          versus = _ => IO.pure(Gsm.Candidate.DoesNotIntersect),
          lost = _ => IO.unit,
          fatal = cause => IO(fail(s"unexpected session failure: $cause"))
        )
        view = new PeerNetwork.ChainView[IO]:
          def tip = IO.pure(None)
          def geometry = IO.pure(ForgedChain.geometry)
        network <- PeerNetwork.resource[IO](
          PeerNetwork.Settings(roots, Targets.relay, seed = 279L),
          transport,
          manager,
          OutboundPeers.Config(),
          hot,
          view,
          mode,
          _ => IO.unit,
          (_, _) => IO.unit,
          _ => IO.unit,
          _ => IO.unit,
          GsmRunner.Marker.none[IO],
          LedgerPeersSource.none[IO]
        )
      yield network
    }

  test("reload installs static roots, kept DNS flags and valencies while new DNS is blocked") {
    TestControl.executeEmbed {
      for
        entered <- Deferred[IO, Unit]
        active <- Ref.of[IO, Int](0)
        calls <- Ref.of[IO, Vector[String]](Vector.empty)
        dns = new PeerDns[IO]:
          def addresses(query: DnsName) =
            calls.update(_ :+ query.value) *>
              (if query.value == "cached.example." then
                 IO.pure(
                   DnsAddressAnswer(
                     Vector(DnsAddressRecord(ipv4"192.0.2.21", 900.seconds)),
                     Vector.empty
                   )
                 )
               else
                 Resource.make(active.update(_ + 1))(_ => active.update(_ - 1)).use { _ =>
                   (if query.value == "pending.example." then entered.complete(()).void
                    else IO.unit) *>
                     IO.never[DnsAddressAnswer]
                 })
          def services(_query: DnsName) = IO.pure(Left(PeerDnsError.Lookup(Vector.empty)))
        given Resource[IO, PeerDns[IO]] = Resource.pure[IO, PeerDns[IO]](dns)
        initial = roots(List(cachedHost -> oldConfig, fixed -> oldConfig))
        next = roots(
          List(moved -> nextConfig, cachedHost -> nextConfig, pendingHost -> nextConfig),
          2,
          3
        )
          .copy(public = Set(SocketAddress[Host](host"public.example", port"3001")))
        _ <- running(initial).use { network =>
          network.run.background.use { _ =>
            for
              _ <- IO.sleep(1.nanosecond)
              _ <- network.reloadRoots(next).timeout(1.second)
              _ <- entered.get
              state <- network.state.get
              _ = assertEquals(state.configuredRoots.keys.toSet, Set(cached, moved))
              _ = assertEquals(state.configuredRoots.config(cached), nextConfig)
              _ = assertEquals(state.configuredRoots.config(moved), nextConfig)
              _ = assertEquals(
                state.configuredRoots.groups.map(g => (g.hot.value, g.warm.value)),
                Vector((2, 2))
              )
              _ = assertEquals(state.localRoots.keys.toSet, Set(cached, moved))
              _ = assertEquals(state.peers(cached).advertise, Some(true))
              _ = assertEquals(GovernorState.invariant(state), Nil)
              blocked <- active.get
              _ = assert(blocked > 0)
            yield ()
          }
        }
        remaining <- active.get
        _ = assertEquals(remaining, 0)
        queried <- calls.get
        _ = assert(queried.contains("pending.example."))
      yield ()
    }
  }

  test("a DNS refresh completing after another reload cannot restore removed roots") {
    TestControl.executeEmbed {
      for
        entered <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        returned <- Deferred[IO, Unit]
        dns = new PeerDns[IO]:
          def addresses(_query: DnsName) =
            entered.complete(()).void *> IO.uncancelable(_ =>
              release.get *>
                IO.pure(
                  DnsAddressAnswer(
                    Vector(DnsAddressRecord(ipv4"192.0.2.24", 900.seconds)),
                    Vector.empty
                  )
                ).guarantee(returned.complete(()).void)
            )
          def services(_query: DnsName) = IO.pure(Left(PeerDnsError.Lookup(Vector.empty)))
        given Resource[IO, PeerDns[IO]] = Resource.pure[IO, PeerDns[IO]](dns)
        _ <- running(roots(List(fixed -> oldConfig))).use { network =>
          network.run.background.use { _ =>
            for
              _ <- IO.sleep(1.nanosecond)
              _ <- network.reloadRoots(roots(List(pendingHost -> nextConfig))).timeout(1.second)
              _ <- entered.get
              _ <- network.reloadRoots(roots(List(moved -> nextConfig))).timeout(1.second)
              installed <- network.state.get
              _ = assertEquals(installed.configuredRoots.keys.toSet, Set(moved))
              _ <- release.complete(())
              _ <- returned.get
              _ <- IO.sleep(1.nanosecond)
              after <- network.state.get
              _ = assertEquals(after.rootsGeneration, installed.rootsGeneration)
              _ = assertEquals(after.configuredRoots.keys.toSet, Set(moved))
              _ = assert(!after.peers.contains(pending))
              _ = assertEquals(GovernorState.invariant(after), Nil)
            yield ()
          }
        }
      yield ()
    }
  }

  test("reload cancels obsolete DNS and starts the new source without its predecessor's deadline") {
    TestControl.executeEmbed {
      for
        oldEntered <- Deferred[IO, Unit]
        oldCanceled <- Deferred[IO, Unit]
        nextEntered <- Deferred[IO, Unit]
        active <- Ref.of[IO, Set[DnsName]](Set.empty)
        dns = new PeerDns[IO]:
          def addresses(query: DnsName) =
            Resource.make(active.update(_ + query))(_ => active.update(_ - query)).use { _ =>
              if query.value == "pending.example." then
                oldEntered.complete(()).void *> IO
                  .never[DnsAddressAnswer]
                  .onCancel(oldCanceled.complete(()).void)
              else nextEntered.complete(()).void *> IO.never[DnsAddressAnswer]
            }
          def services(_query: DnsName) = IO.pure(Left(PeerDnsError.Lookup(Vector.empty)))
        given Resource[IO, PeerDns[IO]] = Resource.pure[IO, PeerDns[IO]](dns)
        _ <- running(roots(List(fixed -> oldConfig))).use { network =>
          network.run.background.use { _ =>
            for
              _ <- IO.sleep(1.nanosecond)
              _ <- network.reloadRoots(roots(List(pendingHost -> nextConfig))).timeout(1.second)
              _ <- oldEntered.get
              nextHost = SocketAddress[Host](host"replacement.example", port"3001")
              _ <- network
                .reloadRoots(roots(List(moved -> nextConfig, nextHost -> nextConfig)))
                .timeout(1.second)
              _ <- nextEntered.get.timeout(1.second)
              _ <- oldCanceled.get
              remaining <- active.get
              _ = assertEquals(remaining.map(_.value), Set("replacement.example."))
              state <- network.state.get
              _ = assertEquals(state.configuredRoots.keys.toSet, Set(moved))
              _ = assertEquals(GovernorState.invariant(state), Nil)
            yield ()
          }
        }
        remaining <- active.get
        _ = assertEquals(remaining, Set.empty[DnsName])
      yield ()
    }
  }

  test("a reload caller can stop waiting without losing the owner's DNS refresh") {
    TestControl.executeEmbed {
      for
        entered <- Deferred[IO, Unit]
        active <- Ref.of[IO, Int](0)
        dns = new PeerDns[IO]:
          def addresses(_query: DnsName) =
            Resource.make(active.update(_ + 1))(_ => active.update(_ - 1)).use { _ =>
              entered.complete(()).void *> IO.never[DnsAddressAnswer]
            }
          def services(_query: DnsName) = IO.pure(Left(PeerDnsError.Lookup(Vector.empty)))
        given Resource[IO, PeerDns[IO]] = Resource.pure[IO, PeerDns[IO]](dns)
        _ <- running(roots(List(fixed -> oldConfig))).use { network =>
          val next = roots(List(moved -> nextConfig, pendingHost -> nextConfig))
          for
            // With the owner not running, virtual time drains the caller into its ack wait.
            _ <- network.reloadRoots(next).background.use(_ => IO.sleep(1.nanosecond))
            notLookedUp <- entered.tryGet
            _ = assertEquals(notLookedUp, None)
            _ <- network.run.background.use { _ =>
              for
                _ <- entered.get.timeout(1.second)
                state <- network.state.get
                _ = assertEquals(state.configuredRoots.keys.toSet, Set(moved))
                _ = assertEquals(GovernorState.invariant(state), Nil)
              yield ()
            }
          yield ()
        }
        remaining <- active.get
        _ = assertEquals(remaining, 0)
      yield ()
    }
  }

  test("public root attribution survives unchanged DNS sources and is pruned when sources change") {
    val public = SocketAddress[Host](ipv4"192.0.2.31", port"3001")
    val bootstrap = SocketAddress[Host](ipv4"192.0.2.32", port"3001")
    val configured = roots(List(fixed -> oldConfig)).copy(
      public = Set(cachedHost),
      bootstrap = Some(Vector(pendingHost))
    )
    val known = GovernorState
      .initial[PeerNetwork.Addr](1L, LocalRoots.empty, Targets.relay, Sensitivity.initial(false))
      .copy(peers =
        SortedMap(
          public -> PeerRecord.cold(Origin.Public),
          bootstrap -> PeerRecord.cold(Origin.Bootstrap)
        )
      )
    assertEquals(
      PeerNetwork.retainedPublicRoots(configured, configured, known),
      Set(public, bootstrap)
    )
    val changedNames =
      configured.copy(public = Set(pendingHost), bootstrap = Some(Vector(cachedHost)))
    assertEquals(PeerNetwork.retainedPublicRoots(configured, changedNames, known), Set.empty)
    val replaced = configured.copy(public = Set(moved), bootstrap = None)
    assertEquals(PeerNetwork.retainedPublicRoots(configured, replaced, known), Set(moved))
    val applied = PeerSelection
      .apply(
        known,
        Event.RootsConfigured(
          LocalRoots.empty,
          false,
          PeerNetwork.retainedPublicRoots(configured, replaced, known),
          Some(BigInt(1))
        ),
        Duration.Zero
      )
      .state
    assertEquals(applied.peers(public).origin, Origin.Other)
    assertEquals(applied.peers(bootstrap).origin, Origin.Other)
  }
