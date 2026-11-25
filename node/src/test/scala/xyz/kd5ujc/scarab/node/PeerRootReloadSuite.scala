// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.node.assertions.Assertions
import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.mtl.Handle
import cats.syntax.all.*
import com.comcast.ip4s.{Host, SocketAddress, host, ipv4, port}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.NodeError
import xyz.kd5ujc.scarab.network.*
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.node.forge.ForgedChain
import xyz.kd5ujc.scarab.protocol.ConnectionState.Provenance

/** Observable startup and reload ordering from ouroboros-network 6a49b6f6. DNS gates stay blocked
  * until assertions release them: success cannot come from a lookup timeout or a wall-clock race.
  */
class PeerRootReloadSuite extends FailsOnUncaught:
  private given Assertions[IO] = Assertions.noop[IO]

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
        _ <- FetchTesting.decisions(pool)
        mode <- Resource.eval(Ref.of[IO, FetchMode](FetchMode.BulkSync))
        hot = OutboundPeers.Hot[IO](
          magic = 42L,
          network = NodeToNode.Settings(),
          peer = Peer.Settings(fixed, 42L, byronEpochSlots = ForgedChain.geometry.byron.epochSlots),
          follow = (_, _) => IO.never,
          heightSnapshot = xyz.kd5ujc.scarab.node.ChainSyncHeights.origin,
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

  test("initial static roots and governor progress do not wait for DNS lookup answers") {
    Settled { settle =>
      for
        entered <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        active <- Ref.of[IO, Int](0)
        dns = new PeerDns[IO]:
          def addresses(_query: DnsName) =
            Resource.make(active.update(_ + 1))(_ => active.update(_ - 1)).use { _ =>
              entered.complete(()).void *> release.get.as(
                DnsAddressAnswer(
                  Vector(DnsAddressRecord(ipv4"192.0.2.21", 900.seconds)),
                  Vector.empty
                )
              )
            }
          def services(_query: DnsName) = IO.pure(Left(PeerDnsError.Lookup(Vector.empty)))
        given Resource[IO, PeerDns[IO]] = Resource.pure[IO, PeerDns[IO]](dns)
        _ <- running(roots(List(fixed -> nextConfig, cachedHost -> nextConfig)))
          .use { network =>
            for
              beforeRun <- entered.tryGet
              _ = assertEquals(beforeRun, None)
              configured <- network.state.get
              _ = assertEquals(configured.configuredRoots.keys.toSet, Set(fixed))
              _ <- network.run.background.use { _ =>
                for
                  _ <- entered.get
                  _ <- settle
                  blocked <- network.state.get
                  _ = assertEquals(blocked.localRoots.keys.toSet, Set(fixed))
                  _ = assert(blocked.peers.contains(fixed))
                  _ = assert(blocked.targets.known > 0)
                  _ = assertEquals(GovernorState.invariant(blocked), Nil)
                  pending <- active.get
                  _ = assertEquals(pending, 1)
                  _ <- release.complete(())
                  _ <- settle
                  resolved <- network.state.get
                  _ = assertEquals(resolved.configuredRoots.keys.toSet, Set(fixed, cached))
                  _ = assertEquals(resolved.localRoots.keys.toSet, Set(fixed, cached))
                  _ = assertEquals(resolved.configuredRoots.config(cached), nextConfig)
                  _ = assert(resolved.rootsGeneration > blocked.rootsGeneration)
                  _ = assertEquals(GovernorState.invariant(resolved), Nil)
                yield ()
              }
            yield ()
          }
          .timeout(1.second)
        remaining <- active.get
        _ = assertEquals(remaining, 0)
      yield ()
    }
  }

  test("resolver resource acquisition defects propagate unchanged") {
    val cause = new IllegalStateException("resolver allocation defect")
    given Resource[IO, PeerDns[IO]] = Resource.eval(IO.raiseError[PeerDns[IO]](cause))
    running(roots(List(fixed -> oldConfig))).use(_ => IO.unit).attempt.map {
      case Left(actual) => assert(actual eq cause)
      case Right(())    => fail("resolver acquisition unexpectedly succeeded")
    }
  }

  test("reload installs static roots, kept DNS flags and valencies while new DNS is blocked") {
    Settled { settle =>
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
              _ <- settle
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
    Settled { settle =>
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
            (for
              _ <- network.reloadRoots(roots(List(pendingHost -> nextConfig))).timeout(1.second)
              _ <- entered.get
              _ <- network.reloadRoots(roots(List(moved -> nextConfig))).timeout(1.second)
              installed <- network.state.get
              _ = assertEquals(installed.configuredRoots.keys.toSet, Set(moved))
              _ <- release.complete(())
              _ <- returned.get
              _ <- settle
              after <- network.state.get
              _ = assertEquals(after.rootsGeneration, installed.rootsGeneration)
              _ = assertEquals(after.configuredRoots.keys.toSet, Set(moved))
              _ = assert(!after.peers.contains(pending))
              _ = assertEquals(GovernorState.invariant(after), Nil)
            yield ()).guarantee(release.complete(()).void)
          }
        }
      yield ()
    }
  }

  /** Every seed is one controlled schedule. While a stale wake still restarted the generation the
    * refresh loop already resolved, seeds 360, 542, 574, 696 and 862 caught the replacement lookup
    * cancelled.
    */
  test("reload cancels obsolete DNS and starts the new source without its predecessor's deadline") {
    (1 to 1000).toList.traverse_ { n =>
      val seed = java.util.Base64.getEncoder.encodeToString(n.toString.getBytes)
      TestControl
        .executeEmbed(obsoleteDnsReload, seed = Some(seed))
        .adaptError { case failure: AssertionError =>
          new AssertionError(s"schedule seed $n", failure)
        }
    }
  }

  private def obsoleteDnsReload: IO[Unit] =
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

  test("a reload caller can stop waiting without losing the owner's DNS refresh") {
    Settled { settle =>
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
            // With the owner not running, quiescence puts the caller in its ack wait.
            _ <- network.reloadRoots(next).background.use(_ => settle)
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

  /** Issue 810: the reference keeps its public roots across a reconfiguration; the configured
    * public roots are read again only by the next request, once the ledger has no peers to give
    * (`requestPublicRootPeersImpl`, ouroboros-network c45735a5,
    * `ouroboros-network/lib/Ouroboros/Network/PeerSelection/PeerSelectionActions.hs` 155-184, and
    * `RootPeersDNS/PublicRootPeers.hs` 82), and only the new local roots are taken out of them
    * (`localRoots`, `cardano-diffusion/lib/Cardano/Network/PeerSelection/Governor/Monitor.hs`
    * 276-282).
    */
  test(
    "a topology reload that names other public roots keeps the public roots the governor knows"
  ) {
    val public = SocketAddress[Host](ipv4"192.0.2.31", port"3001")
    val dns = new PeerDns[IO]:
      def addresses(name: DnsName) = IO.pure(DnsAddressAnswer(Vector.empty, Vector.empty))
      def services(name: DnsName) = IO.pure(Left(PeerDnsError.Lookup(Vector.empty)))
    given Resource[IO, PeerDns[IO]] = Resource.pure[IO, PeerDns[IO]](dns)
    val configured = roots(List(fixed -> oldConfig)).copy(public = Set(cachedHost))
    Settled { settle =>
      running(configured).use { network =>
        network.run.background.use { _ =>
          for
            _ <- settle
            _ <- network.state.update(st =>
              st.copy(peers = st.peers + (public -> PeerRecord.cold(Origin.Public)))
            )
            before <- network.state.get
            _ <- network.reloadRoots(configured.copy(public = Set(moved))).timeout(1.second)
            _ <- settle
            after <- network.state.get
          yield
            assert(after.rootsGeneration > before.rootsGeneration)
            assertEquals(after.peers.get(public).map(_.origin), Some(Origin.Public))
            assertEquals(GovernorState.invariant(after), Nil)
        }
      }
    }
  }

  /** Issue 810: a local root's DNS name answered the same addresses in another order. The
    * reference's provider writes its groups, whose members are a map, only when they changed
    * (`monitorDomain`, ouroboros-network c45735a5,
    * `ouroboros-network/lib/Ouroboros/Network/PeerSelection/RootPeersDNS/LocalRootPeers.hs`
    * 236-246, the groups' type 261-267), and its governor acts only on local roots that differ
    * (`localRoots`, `cardano-diffusion/lib/Cardano/Network/PeerSelection/Governor/Monitor.hs` 255):
    * no new roots generation. The third lookup starts only once the second answer is dealt with.
    */
  test("a local DNS answer in another order publishes no roots generation") {
    def answer(addresses: com.comcast.ip4s.IpAddress*) =
      DnsAddressAnswer(addresses.toVector.map(DnsAddressRecord(_, 60.seconds)), Vector.empty)
    Settled.timed { settle =>
      for
        lookups <- Ref.of[IO, Int](0)
        resolvedFirst <- Deferred[IO, Unit]
        resolvedAgain <- Deferred[IO, Unit]
        askedThird <- Deferred[IO, Unit]
        dns = new PeerDns[IO]:
          def addresses(_query: DnsName) =
            lookups.getAndUpdate(_ + 1).flatMap {
              case 0 => resolvedFirst.complete(()).as(answer(ipv4"192.0.2.31", ipv4"192.0.2.32"))
              case 1 => resolvedAgain.complete(()).as(answer(ipv4"192.0.2.32", ipv4"192.0.2.31"))
              case _ => askedThird.complete(()) *> IO.never[DnsAddressAnswer]
            }
          def services(_query: DnsName) = IO.pure(Left(PeerDnsError.Lookup(Vector.empty)))
        given Resource[IO, PeerDns[IO]] = Resource.pure[IO, PeerDns[IO]](dns)
        _ <- running(roots(List(cachedHost -> nextConfig), hot = 1, warm = 2)).use { network =>
          network.run.background.use { _ =>
            for
              _ <- resolvedFirst.get
              _ <- settle
              installed <- network.state.get
              _ = assertEquals(
                installed.localRoots.keys.toSet,
                Set(
                  SocketAddress[Host](ipv4"192.0.2.31", port"3001"),
                  SocketAddress[Host](ipv4"192.0.2.32", port"3001")
                )
              )
              _ <- resolvedAgain.get
              _ <- askedThird.get
              _ <- settle
              after <- network.state.get
            yield
              assertEquals(after.rootsGeneration, installed.rootsGeneration)
              assertEquals(after.localRoots, installed.localRoots)
          }
        }
      yield ()
    }
  }

  /** Issue 810: a local root's DNS name answered other addresses, which publishes a new roots
    * generation; that generation took every big ledger peer away. The addresses of the big ledger
    * peers wait a day before they are dialled, so only the reload can change them.
    */
  test("a local DNS answer that changes keeps the big ledger peers") {
    val first = SocketAddress[Host](ipv4"192.0.2.31", port"3001")
    val second = SocketAddress[Host](ipv4"192.0.2.32", port"3001")
    val third = SocketAddress[Host](ipv4"192.0.2.33", port"3001")
    val bigLedger =
      List(ipv4"198.51.100.1", ipv4"198.51.100.2", ipv4"198.51.100.3")
        .map(ip => SocketAddress[Host](ip, port"3001"))
    Settled.timed { settle =>
      for
        lookups <- Ref.of[IO, Int](0)
        resolvedFirst <- Deferred[IO, Unit]
        resolvedAgain <- Deferred[IO, Unit]
        dns = new PeerDns[IO]:
          def addresses(_query: DnsName) =
            lookups.getAndUpdate(_ + 1).flatMap {
              case 0 =>
                resolvedFirst
                  .complete(())
                  .as(
                    DnsAddressAnswer(
                      Vector(
                        DnsAddressRecord(ipv4"192.0.2.31", 60.seconds),
                        DnsAddressRecord(ipv4"192.0.2.32", 60.seconds)
                      ),
                      Vector.empty
                    )
                  )
              case 1 =>
                resolvedAgain
                  .complete(())
                  .as(
                    DnsAddressAnswer(
                      Vector(
                        DnsAddressRecord(ipv4"192.0.2.33", 60.seconds),
                        DnsAddressRecord(ipv4"192.0.2.31", 60.seconds)
                      ),
                      Vector.empty
                    )
                  )
              case _ => IO.never[DnsAddressAnswer]
            }
          def services(_query: DnsName) = IO.pure(Left(PeerDnsError.Lookup(Vector.empty)))
        given Resource[IO, PeerDns[IO]] = Resource.pure[IO, PeerDns[IO]](dns)
        _ <- running(roots(List(cachedHost -> nextConfig), hot = 1, warm = 2)).use { network =>
          network.run.background.use { _ =>
            for
              _ <- resolvedFirst.get
              _ <- settle
              now <- IO.monotonic
              _ <- network.state.update(st =>
                st.copy(peers =
                  st.peers ++ bigLedger.map(
                    _ -> PeerRecord
                      .cold(Origin.BigLedger)
                      .copy(status = Status.Cold(Some(now + 1.day)))
                  )
                )
              )
              installed <- network.state.get
              _ = assertEquals(installed.localRoots.keys.toSet, Set(first, second))
              _ <- resolvedAgain.get
              _ <- settle
              after <- network.state.get
              _ = assert(after.rootsGeneration > installed.rootsGeneration)
              _ = assertEquals(after.localRoots.keys.toSet, Set(first, third))
              _ = assertEquals(after.view(now).big.known.toSet, bigLedger.toSet)
              _ = assertEquals(GovernorState.invariant(after), Nil)
            yield ()
          }
        }
      yield ()
    }
  }

  /** Issue 810: the reference compares the whole `UseBootstrapPeers` value, its list of relays
    * included (ouroboros-network c45735a5,
    * `cardano-diffusion/api/lib/Cardano/Network/PeerSelection/Bootstrap.hs` 20-22), so a reload
    * that names other bootstrap peers runs `monitorBootstrapPeersFlag`, which forgets the bootstrap
    * peers neither established nor being connected
    * (`cardano-diffusion/lib/Cardano/Network/PeerSelection/Governor/Monitor.hs` 429-453); a reload
    * that names the same ones does nothing. The known bootstrap peer waits a day before it is
    * dialled, so only the reload can change it.
    */
  test(
    "a reload that names other bootstrap peers forgets those not established; the same keeps them"
  ) {
    val known = SocketAddress[Host](ipv4"192.0.2.41", port"3001")
    val other = SocketAddress[Host](ipv4"192.0.2.42", port"3001")
    val dns = new PeerDns[IO]:
      def addresses(name: DnsName) = IO.pure(DnsAddressAnswer(Vector.empty, Vector.empty))
      def services(name: DnsName) = IO.pure(Left(PeerDnsError.Lookup(Vector.empty)))
    given Resource[IO, PeerDns[IO]] = Resource.pure[IO, PeerDns[IO]](dns)
    val configured = roots(Nil, hot = 0, warm = 0).copy(bootstrap = Some(Vector(known)))
    Settled { settle =>
      running(configured).use { network =>
        network.run.background.use { _ =>
          for
            _ <- settle
            now <- IO.monotonic
            _ <- network.state.update(st =>
              st.copy(peers =
                st.peers + (known -> PeerRecord
                  .cold(Origin.Bootstrap)
                  .copy(status = Status.Cold(Some(now + 1.day))))
              )
            )
            _ <- network.reloadRoots(configured).timeout(1.second)
            _ <- settle
            same <- network.state.get
            _ <- network
              .reloadRoots(configured.copy(bootstrap = Some(Vector(other))))
              .timeout(1.second)
            _ <- settle
            changed <- network.state.get
          yield
            assertEquals(same.peers.get(known).map(_.origin), Some(Origin.Bootstrap))
            assertEquals(changed.peers.get(known), None)
            assert(changed.sensitivity.useBootstrapPeers)
            assertEquals(GovernorState.invariant(changed), Nil)
        }
      }
    }
  }

  test("startup bootstrap policy includes SRV-only and explicitly empty configurations") {
    val absent = roots(Nil, hot = 0, warm = 0)
    val domain = DnsName.from("bootstrap.example").fold(e => fail(e.toString), identity)
    val inputs = List(
      (absent, false),
      (absent.copy(bootstrap = Some(Vector.empty)), true),
      (absent.copy(bootstrapSrv = Vector(domain)), true)
    )
    val dns = new PeerDns[IO]:
      def addresses(name: DnsName) = IO.pure(DnsAddressAnswer(Vector.empty, Vector.empty))
      def services(name: DnsName) = IO.pure(Left(PeerDnsError.Lookup(Vector.empty)))
    given Resource[IO, PeerDns[IO]] = Resource.pure[IO, PeerDns[IO]](dns)
    inputs.traverse_ { (input, enabled) =>
      running(input).use { network =>
        network.state.get.map { state =>
          assertEquals(input.bootstrapConfigured, enabled)
          assertEquals(state.sensitivity.useBootstrapPeers, enabled)
          assertEquals(state.requested.active, if enabled then 2 else Targets.relay.active)
          assertEquals(state.requested.root, if enabled then Targets.relay.root else 0)
        }
      }
    }
  }

  test("topology reload uses the same bootstrap flag for SRV-only, empty and absent roots") {
    Settled { settle =>
      val absent = roots(Nil, hot = 0, warm = 0)
      val domain = DnsName.from("bootstrap.example").fold(e => fail(e.toString), identity)
      val srvOnly = absent.copy(bootstrapSrv = Vector(domain))
      val explicitEmpty = absent.copy(bootstrap = Some(Vector.empty))
      val dns = new PeerDns[IO]:
        def addresses(name: DnsName) = IO.pure(DnsAddressAnswer(Vector.empty, Vector.empty))
        def services(name: DnsName) = IO.pure(Left(PeerDnsError.Lookup(Vector.empty)))
      given Resource[IO, PeerDns[IO]] = Resource.pure[IO, PeerDns[IO]](dns)
      running(absent).use { network =>
        network.run.background.use { _ =>
          List((srvOnly, true), (explicitEmpty, true), (absent, false)).traverse_ {
            (input, enabled) =>
              for
                _ <- network.reloadRoots(input).timeout(1.second)
                _ <- settle
                state <- network.state.get
              yield
                assertEquals(state.sensitivity.useBootstrapPeers, enabled)
                assertEquals(GovernorState.invariant(state), Nil)
          }
        }
      }
    }
  }
