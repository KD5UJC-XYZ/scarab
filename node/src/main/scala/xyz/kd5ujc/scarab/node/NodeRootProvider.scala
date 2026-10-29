// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Applicative
import cats.effect.{Async, Ref}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, IpAddress, Port, SocketAddress}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.LedgerRelay
import xyz.kd5ujc.scarab.network.selection.*
import xyz.kd5ujc.scarab.network.selection.Rng

/** What the local roots' DNS tells besides its answers (`TraceLocalRootPeers`,
  * `RootPeersDNS/LocalRootPeers.hs` of ouroboros-network 2fb69829).
  */
enum LocalRootsTrace:
  /** The lookup of `domain` threw (`TraceLocalRootError`); the lookups start again at once. */
  case Error(domain: DnsName, cause: Throwable)

/** The roots the governor asks for, from the topology and from the ledger
  * (`requestPublicRootPeersImpl`, `Cardano/Network/PeerSelection/PeerSelectionActions.hs` 40-100,
  * and `Ouroboros/Network/PeerSelection/PeerSelectionActions.hs` 125-184):
  *
  *   - in the sensitive state (bootstrap peers in use and the ledger too old), the bootstrap peers
  *     and nothing else;
  *   - otherwise the ledger peers the worker draws ([[LedgerPeersWorker]]); when the ledger gives
  *     none (ledger peers off, the ledger not yet at the `useLedgerAfterSlot` slot, or no pool with
  *     a relay) the public roots of the topology;
  *   - a big-ledger request returns the big-ledger map, or configured public roots classified as
  *     ordinary extras when the worker falls back. Fallback discovery never increases big-ledger
  *     counts.
  *
  * A ledger relay is a peer address: an IP address, host name, or SRV name resolved by [[PeerDns]].
  * The answer of the ledger is good for [[LedgerPeerStake.ReRequestInterval]], the time the
  * governor waits before it asks again; configured topology roots refresh each DNS source at its
  * own TTL or error retry interval.
  */
object NodeRootProvider:
  type Addr = SocketAddress[Host]

  private[node] enum LocalDnsInput:
    case Host(name: DnsName, port: Port)
    case Service(name: DnsName)

    def domain: DnsName = this match
      case Host(name, _) => name
      case Service(name) => name

  /** Includes source position for duplicate entries and the configured name for stale pruning. */
  private[node] final case class LocalDnsKey(
      groupIndex: Int,
      sourceIndex: Int,
      input: LocalDnsInput
  )

  private[node] final case class LocalDnsEntry(key: LocalDnsKey, config: RootConfig)

  private[node] enum RootOrigin:
    case Host(peer: Addr)
    case Service(domain: DnsName, advertise: Boolean)

  /** Roots supplied without DNS records use native `ttlForResults []`. */
  val ConfiguredTtl: FiniteDuration = PeerDns.ttlForResults(Vector.empty)

  /** The peer address of a ledger relay, none for one this node does not connect to: an SRV domain,
    * a name that is not a host name, the unspecified address.
    */
  def address(relay: LedgerRelay): Option[Addr] = relay match
    case LedgerRelay.Address(ip, port) =>
      for
        a <- IpAddress.fromBytes(ip.octets.toArray)
        if !a.toBytes.forall(_ == 0)
        p <- Port.fromInt(port.toInt)
      yield SocketAddress[Host](a, p)
    case LedgerRelay.Domain(name, port) =>
      // a snapshot writes a domain with a trailing dot; the topology's spelling has none
      for
        h <- Host.fromString(name.stripSuffix("."))
        p <- Port.fromInt(port.toInt)
      yield SocketAddress[Host](h, p)
    case LedgerRelay.Srv(_) => None

  def apply[F[_]: Applicative](
      public: Set[Addr],
      bootstrap: Vector[Addr],
      worker: LedgerPeersWorker[F],
      advertise: Map[Addr, Boolean] = Map.empty
  ): RootProvider[F, Addr] = new RootProvider[F, Addr]:
    def request(max: Int, sensitive: Boolean) =
      if sensitive then
        Applicative[F].pure(
          Some(PublicRoots(Set.empty[Addr], bootstrap.toSet) -> ConfiguredTtl)
        )
      else
        worker.request[LedgerKind.All](max).map {
          case LedgerAnswer.FallBackToPublicRoots() =>
            Some(PublicRoots(public, Set.empty[Addr], advertise = advertise) -> ConfiguredTtl)
          case LedgerAnswer.Picked(relays, _) =>
            Some(
              PublicRoots(Set.empty[Addr], Set.empty[Addr], relays.flatMap(address).toSet) ->
                LedgerPeerStake.ReRequestInterval
            )
        }

    def requestBigLedger(max: Int) =
      worker.request[LedgerKind.Big](max).map {
        case LedgerAnswer.FallBackToPublicRoots() =>
          Some(
            BigLedgerRoots.fromLedger(
              None,
              PublicRoots(public, Set.empty[Addr], advertise = advertise)
            ) -> ConfiguredTtl
          )
        case LedgerAnswer.Picked(relays, _) =>
          Some(
            BigLedgerRoots.fromLedger(
              Some(relays.flatMap(address).toSet),
              PublicRoots(Set.empty[Addr], Set.empty[Addr])
            ) -> LedgerPeerStake.ReRequestInterval
          )
      }

  /** Read one complete topology/worker snapshot for each request. Reload swaps this pair only after
    * the replacement topology, peer snapshot and worker have all been constructed.
    */
  def dynamic[F[_]: Async](
      current: Ref[F, PeerNetwork.Runtime[F]],
      dns: PeerDns[F],
      rng: Ref[F, Rng]
  ): RootProvider[F, Addr] = new RootProvider[F, Addr]:
    def request(max: Int, bootstrap: Boolean) =
      current.get.flatMap { runtime =>
        val roots = runtime.roots
        if bootstrap then
          resolveRoots(
            roots.bootstrap.getOrElse(Vector.empty).toSet,
            roots.bootstrapSrv.map(_ -> false),
            dns,
            rng
          )
            .map { case (addresses, ttl, _) =>
              Some(PublicRoots(Set.empty, addresses, advertise = Map.empty) -> ttl)
            }
        else
          runtime.worker.request[LedgerKind.All](max).flatMap {
            case LedgerAnswer.FallBackToPublicRoots() =>
              resolveRoots(roots.public, roots.publicSrv, dns, rng).map {
                case (addresses, ttl, source) =>
                  Some(
                    PublicRoots(
                      addresses,
                      Set.empty,
                      advertise = advertiseFromSources(source, roots.advertise)
                    ) -> ttl
                  )
              }
            case LedgerAnswer.Picked(relays, _) =>
              resolveLedger(relays, dns, rng).map { case (addresses, ttl) =>
                Some(PublicRoots(Set.empty, Set.empty, addresses) -> ttl)
              }
          }
      }

    def requestBigLedger(max: Int) =
      current.get.flatMap { runtime =>
        runtime.worker.request[LedgerKind.Big](max).flatMap {
          case LedgerAnswer.FallBackToPublicRoots() =>
            resolveRoots(runtime.roots.public, runtime.roots.publicSrv, dns, rng)
              .map { case (addresses, ttl, source) =>
                Some(
                  BigLedgerRoots.fromLedger(
                    None,
                    PublicRoots(
                      addresses,
                      Set.empty[Addr],
                      advertise = advertiseFromSources(source, runtime.roots.advertise)
                    )
                  ) -> ttl
                )
              }
          case LedgerAnswer.Picked(relays, _) =>
            resolveLedger(relays, dns, rng).map { case (addresses, ttl) =>
              Some(
                BigLedgerRoots
                  .fromLedger(Some(addresses), PublicRoots(Set.empty[Addr], Set.empty[Addr])) -> ttl
              )
            }
        }
      }

  private[node] def resolveRoots[F[_]: Async](
      peers: Set[Addr],
      services: Vector[(DnsName, Boolean)],
      dns: PeerDns[F],
      rng: Ref[F, Rng]
  ): F[(Set[Addr], FiniteDuration, Map[Addr, Set[RootOrigin]])] =
    for
      fixed <- peers.toVector.traverse(peer => resolveOne(peer, dns).map(peer -> _))
      srv <- services.traverse { case (service, advertise) =>
        resolveService(service, dns, rng).map(result => (service, advertise) -> result)
      }
      addresses = fixed.flatMap { case (_, (found, _)) => found }.toSet ++
        srv.flatMap(_._2._1)
      ttls = fixed.flatMap(_._2._2) ++ srv.flatMap(_._2._2)
      source = fixed.flatMap { case (original, (found, _)) =>
        found.map(_ -> RootOrigin.Host(original))
      }.toList ++ srv.flatMap { case ((domain, advertise), (found, _)) =>
        found.map(_ -> RootOrigin.Service(domain, advertise))
      }
      provenance = source
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.toSet)
        .toMap
    yield (addresses, PeerDns.ttlForResults(ttls), provenance)

  private[node] def advertiseFromSources(
      source: Map[Addr, Set[RootOrigin]],
      hosts: Map[Addr, Boolean]
  ): Map[Addr, Boolean] =
    source.flatMap { case (peer, origins) =>
      Option.when(origins.exists {
        case RootOrigin.Host(original)        => hosts.getOrElse(original, false)
        case RootOrigin.Service(_, advertise) => advertise
      })(peer -> true)
    }

  /** The DNS lookups named by the current topology. The key preserves configured source identity,
    * including duplicate entries and group membership, so a changed name cannot inherit an old
    * address set.
    */
  private[node] def localDnsEntries(
      groups: List[RootGroupSpec[Addr]],
      services: List[List[(DnsName, RootConfig)]]
  ): Vector[LocalDnsEntry] =
    groups.zipWithIndex.toVector.flatMap { case (group, groupIndex) =>
      val hosts = group.members.zipWithIndex.toVector.flatMap {
        case ((peer, config), sourceIndex) =>
          peer.host match
            case _: IpAddress => Vector.empty
            case host =>
              DnsName.from(host.toString).toOption.toVector.map { name =>
                LocalDnsEntry(
                  LocalDnsKey(groupIndex, sourceIndex, LocalDnsInput.Host(name, peer.port)),
                  config
                )
              }
      }
      val srv =
        services.lift(groupIndex).getOrElse(Nil).zipWithIndex.map { case ((name, config), index) =>
          LocalDnsEntry(LocalDnsKey(groupIndex, index, LocalDnsInput.Service(name)), config)
        }
      hosts ++ srv
    }

  /** Rebuild the resolved groups from configured static addresses and the last successful DNS value
    * for each source. A failed source therefore retains its previous peers independently.
    */
  private[node] def localGroupsFromCache(
      groups: List[RootGroupSpec[Addr]],
      services: List[List[(DnsName, RootConfig)]],
      cache: LocalRootDnsCache.State[LocalDnsKey, Addr]
  ): List[RootGroupSpec[Addr]] =
    val cached = localDnsEntries(groups, services).map(entry => entry.key -> entry.config).toMap
    groups.zipWithIndex.map { case (group, groupIndex) =>
      val fixed = group.members.zipWithIndex.flatMap { case (root @ (peer, config), sourceIndex) =>
        peer.host match
          case _: IpAddress => Some(root)
          case host =>
            DnsName.from(host.toString).toOption.toList.flatMap { name =>
              val key = LocalDnsKey(groupIndex, sourceIndex, LocalDnsInput.Host(name, peer.port))
              LocalRootDnsCache.snapshot(cache, key).toList.flatMap(_.values.map(_ -> config))
            }
      }
      val srv = services.lift(groupIndex).getOrElse(Nil).zipWithIndex.flatMap {
        case ((name, _), sourceIndex) =>
          val key = LocalDnsKey(groupIndex, sourceIndex, LocalDnsInput.Service(name))
          val config = cached.get(key)
          LocalRootDnsCache.snapshot(cache, key).toList.flatMap { snapshot =>
            snapshot.values.flatMap(address => config.map(address -> _))
          }
      }
      group.copy(members = fixed ++ srv)
    }

  /** Resolve one configured local-root source into cache data. A partial A/AAAA answer succeeds;
    * only an answer with no addresses and lookup failures is a failure. SRV TTL replaces the
    * selected target's address TTLs, as in `DNSActions.hs`.
    */
  private[node] def resolveLocalDns[F[_]: Async](
      entry: LocalDnsEntry,
      dns: PeerDns[F],
      rng: Ref[F, Rng]
  ): F[LocalRootDnsCache.LookupResult[Addr]] =
    import LocalRootDnsCache.LookupResult

    def failed(failures: Vector[DnsLookupFailure]): LookupResult[Addr] =
      val errors = failures.map(PeerDns.failureKind)
      cats.data.NonEmptyList.fromList(errors.toList) match
        case Some(found) => LookupResult.Failure(found)
        case None        => LookupResult.Success(Vector.empty, Vector.empty)

    def addresses(name: DnsName, port: Port): F[LookupResult[Addr]] =
      dns.addresses(name).map { answer =>
        val peers = answer.records.map(record => SocketAddress[Host](record.address, port))
        if peers.nonEmpty then LookupResult.Success(peers, answer.records.map(_.ttl))
        else if answer.failures.nonEmpty then failed(answer.failures)
        else LookupResult.Success(Vector.empty, Vector.empty)
      }

    entry.key.input match
      case LocalDnsInput.Host(name, port) => addresses(name, port)
      case LocalDnsInput.Service(domain) =>
        val prefix = "_cardano._tcp."
        val query =
          if domain.value.startsWith(prefix) then Right(domain)
          else DnsName.from(s"$prefix${domain.value}")
        query match
          case Left(_) => Async[F].pure(LookupResult.Success(Vector.empty, Vector.empty))
          case Right(name) =>
            dns.services(name).flatMap {
              case Left(PeerDnsError.Lookup(failures)) => Async[F].pure(failed(failures))
              case Left(_) => Async[F].pure(LookupResult.Success(Vector.empty, Vector.empty))
              case Right(records) =>
                rng
                  .modify { current =>
                    PeerDns.selectService(records, current) match
                      case Left(_)                => current -> Option.empty[DnsServiceRecord]
                      case Right((service, next)) => next -> Some(service)
                  }
                  .flatMap {
                    case None => Async[F].pure(LookupResult.Success(Vector.empty, Vector.empty))
                    case Some(service) =>
                      dns.addresses(service.target).map { answer =>
                        val peers = answer.records.flatMap { record =>
                          Port
                            .fromInt(service.port)
                            .map(port => SocketAddress[Host](record.address, port))
                        }
                        if peers.nonEmpty then
                          LookupResult.Success(peers, Vector.fill(peers.size)(service.ttl))
                        else if answer.failures.nonEmpty then failed(answer.failures)
                        else LookupResult.Success(Vector.empty, Vector.empty)
                      }
                  }
            }

  private def resolveOne[F[_]: Async](
      peer: Addr,
      dns: PeerDns[F]
  ): F[(Vector[Addr], Vector[FiniteDuration])] =
    peer.host match
      case ip: IpAddress =>
        Async[F].pure((Vector(SocketAddress[Host](ip, peer.port)), Vector.empty))
      case host =>
        DnsName.from(host.toString) match
          case Left(_) => Async[F].pure((Vector.empty, Vector.empty))
          case Right(name) =>
            dns.addresses(name).map { answer =>
              val values =
                answer.records.map(record => SocketAddress[Host](record.address, peer.port))
              (values, answer.records.map(_.ttl))
            }

  private[node] def resolveLedger[F[_]: Async](
      relays: Vector[LedgerRelay],
      dns: PeerDns[F],
      rng: Ref[F, Rng]
  ): F[(Set[Addr], FiniteDuration)] =
    relays
      .traverse {
        case LedgerRelay.Srv(domain) =>
          DnsName.from(domain) match
            case Left(_)     => Async[F].pure(Vector.empty[Addr] -> Vector.empty[FiniteDuration])
            case Right(name) => resolveService(name, dns, rng)
        case relay =>
          address(relay).fold(
            Async[F].pure(Vector.empty[Addr] -> Vector.empty[FiniteDuration])
          )(peer => resolveOne(peer, dns))
      }
      .map { results =>
        (results.flatMap(_._1).toSet, PeerDns.ttlForResults(results.flatMap(_._2)))
      }

  private[node] def resolveService[F[_]: Async](
      domain: DnsName,
      dns: PeerDns[F],
      rng: Ref[F, Rng]
  ): F[(Vector[Addr], Vector[FiniteDuration])] =
    val prefix = "_cardano._tcp."
    val query =
      if domain.value.startsWith(prefix) then Right(domain)
      else DnsName.from(s"$prefix${domain.value}")
    query match
      case Left(_) => Async[F].pure(Vector.empty[Addr] -> Vector.empty[FiniteDuration])
      case Right(name) =>
        dns.services(name).flatMap {
          case Left(_) => Async[F].pure(Vector.empty[Addr] -> Vector.empty[FiniteDuration])
          case Right(records) =>
            rng
              .modify { current =>
                PeerDns.selectService(records, current) match
                  case Left(_)                => current -> Option.empty[DnsServiceRecord]
                  case Right((service, next)) => next -> Some(service)
              }
              .flatMap {
                case None => Async[F].pure(Vector.empty[Addr] -> Vector.empty[FiniteDuration])
                case Some(service) =>
                  dns.addresses(service.target).map { answer =>
                    val addresses = answer.records.flatMap { record =>
                      Port
                        .fromInt(service.port)
                        .map(port => SocketAddress[Host](record.address, port))
                    }
                    addresses -> addresses.map(_ => service.ttl)
                  }
              }
        }
