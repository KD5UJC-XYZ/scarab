// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Resource}
import cats.effect.implicits.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.IpAddress
import java.util.concurrent.{
  CompletionStage,
  CompletionException,
  Executor,
  Executors,
  TimeoutException
}
import java.util.function.BiConsumer
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import org.xbill.DNS.{
  ARecord,
  AAAARecord,
  ExtendedResolver,
  Name,
  Record,
  Resolver,
  ResolverConfig,
  SRVRecord,
  SimpleResolver,
  TextParseException,
  Type
}
import org.xbill.DNS.lookup.{LookupSession, NoSuchDomainException}

/** One fully-qualified DNS name. Names are validated before reaching the DNS implementation. */
opaque type DnsName = String

object DnsName:
  def from(value: String): Either[PeerDnsError.InvalidName, DnsName] =
    val absolute = if value.endsWith(".") then value else s"$value."
    Either
      .catchOnly[TextParseException](Name.fromString(absolute))
      .fold(
        error => Left(PeerDnsError.InvalidName(value, error)),
        _ => Right(absolute)
      )

  extension (name: DnsName) def value: String = name

enum DnsRecordType:
  case A, AAAA, SRV

final case class DnsLookupFailure(
    name: DnsName,
    recordType: DnsRecordType,
    cause: Throwable
)

enum PeerDnsError:
  case InvalidName(value: String, cause: TextParseException)
  case Lookup(failures: Vector[DnsLookupFailure])

enum DnsServiceSelectionError:
  case NoUsableService

final case class DnsAddressRecord(address: IpAddress, ttl: FiniteDuration)

final case class DnsAddressAnswer(
    records: Vector[DnsAddressRecord],
    failures: Vector[DnsLookupFailure]
)

final case class DnsServiceRecord(
    priority: Int,
    weight: Int,
    port: Int,
    target: DnsName,
    ttl: FiniteDuration
)

/** Query-aware DNS operations; tests provide this algebra without public DNS traffic. */
trait PeerDns[F[_]]:
  def addresses(name: DnsName): F[DnsAddressAnswer]
  def services(name: DnsName): F[Either[PeerDnsError, Vector[DnsServiceRecord]]]

object PeerDns:
  /** The assembly scope may provide a resolver resource, for example a controlled DNS service. */
  given resolver[F[_]: Async]: Resource[F, PeerDns[F]] = dnsJava[F]

  private val MinTtl: FiniteDuration = 60.seconds
  private val MaxTtl: FiniteDuration = 900.seconds
  private val LookupTimeout: FiniteDuration = 5.seconds

  private[node] def failureKind(
      failure: DnsLookupFailure
  ): LocalRootDnsCache.DnsFailureKind = failure.cause match
    case _: NoSuchDomainException => LocalRootDnsCache.DnsFailureKind.NameError
    case _                        => LocalRootDnsCache.DnsFailureKind.Other

  private def unwrap(error: Throwable): Throwable = error match
    case completion: CompletionException if completion.getCause != null => completion.getCause
    case other                                                          => other

  /** The resolver session and its four-thread lookup executor share this node-owned Resource.
    * Lookup futures are canceled with the calling fiber; Resource release shuts down the executor.
    */
  def dnsJava[F[_]: Async]: Resource[F, PeerDns[F]] =
    Resource
      .make(Async[F].delay(Executors.newFixedThreadPool(4)))(executor =>
        Async[F].delay(executor.shutdownNow()).void
      )
      .evalMap(executor => Async[F].delay(uncached(sessionBuilder(systemResolver()), executor)))
      .map(fromSession[F])

  /** The resolver of dnsjava's `LookupSession.defaultBuilder()`, built as it builds it (dnsjava
    * v3.6.5 `LookupSession.java:372-376`): a `SimpleResolver` for each server of the system
    * configuration, asked through an `ExtendedResolver`. The reference asks, on POSIX systems, the
    * servers that the `nameserver` lines of `/etc/resolv.conf` name (dns-4.2.0
    * `internal/Network/DNS/Resolver/Internal.hs:46-50`).
    */
  private[node] def systemResolver(): ExtendedResolver =
    new ExtendedResolver(
      ResolverConfig.getCurrentConfig.servers.asScala.toVector
        .map(server => new SimpleResolver(server): Resolver)
        .asJava
    )

  /** The session's builder: `resolver`, without the hosts-file parser that `defaultBuilder()` adds
    * (`LookupSession.java:379`), which answers an A or AAAA query for a name the hosts file lists
    * without asking the resolver (`LookupSession.java:417-425`, `460-482`). The reference reads no
    * hosts file: ouroboros-network `c45735a5` looks every root up with dns-4.2.0's `lookupRaw`
    * (`RootPeersDNS/DNSActions.hs:292-295`), which sends the query to those servers
    * (`Network/DNS/Transport.hs:81-103`). The only file dns-4.2.0 reads is that configuration file
    * (`internal/Network/DNS/Resolver/Internal.hs:46`), and it turns each server into an address
    * with `AI_NUMERICHOST` (`Network/DNS/Resolver.hs:68-76`). The ndots that `defaultBuilder()`
    * reads from the system configuration is not kept, and it sets no search path: the reference has
    * neither, as it makes every name absolute (`Transport.hs:89-91`), and a session without a
    * search path looks a name up once, made absolute, whatever its ndots
    * (`LookupSession.java:432-449`).
    */
  private[node] def sessionBuilder(resolver: Resolver): LookupSession.LookupSessionBuilder =
    LookupSession.builder().resolver(resolver)

  /** The session from `builder` without a record cache, its lookups on `executor`. The reference
    * keeps no DNS cache of its own: ouroboros-network `c45735a5` resolves local and public roots
    * with `DNS.defaultResolvConf` (`PeerSelection/PeerSelectionActions.hs:93-98`, `180`), whose
    * `resolvCache` is `Nothing` in the `dns-4.2.0` that cardano-node 11.1.3 links
    * (`internal/Network/DNS/Types/Resolver.hs:104-112`). Every lookup asks the resolver, and the
    * clipped TTL only schedules the next one. dnsjava's default session caches answers for their
    * whole TTL, which would hold an address past the reference's 900 s once a record's TTL is
    * longer.
    */
  private[node] def uncached(
      builder: LookupSession.LookupSessionBuilder,
      executor: Executor
  ): LookupSession =
    builder.clearCaches().executor(executor).build()

  private[node] def fromSession[F[_]: Async](session: LookupSession): PeerDns[F] =
    new DnsJava[F](session)

  /** Clip every positive DNS RR TTL as in `RootPeersDNS/DNSActions.hs` (60 through 900 seconds). */
  def clippedTtl(seconds: Long): FiniteDuration =
    seconds.max(MinTtl.toSeconds).min(MaxTtl.toSeconds).seconds

  /** A and AAAA lookups retain each record's own clipped TTL. If either family resolves, preserve
    * its addresses and report the other family failure as metadata, like the reference resolver.
    */
  def combineAddresses(
      ipv4: Either[DnsLookupFailure, Vector[DnsAddressRecord]],
      ipv6: Either[DnsLookupFailure, Vector[DnsAddressRecord]]
  ): DnsAddressAnswer =
    val answers = Vector(ipv4, ipv6)
    val records = answers.collect { case Right(found) => found }.flatten
    val failures = answers.collect { case Left(failure) => failure }
    DnsAddressAnswer(records, failures)

  private[node] def lookupCompletion[A](
      name: DnsName,
      recordType: DnsRecordType,
      failure: Option[Throwable],
      decode: => Vector[A]
  ): Either[Throwable, Either[DnsLookupFailure, Vector[A]]] =
    failure match
      case Some(error) => Right(Left(DnsLookupFailure(name, recordType, unwrap(error))))
      case None =>
        Either
          .catchNonFatal(decode)
          .fold(Left(_), records => Right(Right(records)))

  /** Bound one asynchronous resolver request and cancel its underlying future on timeout. */
  private[node] def awaitLookup[F[_]: Async, A, B](
      name: DnsName,
      recordType: DnsRecordType,
      stage: => CompletionStage[A],
      decode: A => Vector[B]
  ): F[Either[DnsLookupFailure, Vector[B]]] =
    val F = Async[F]
    val timeoutFailure = DnsLookupFailure(name, recordType, new TimeoutException())
    F.async[Either[DnsLookupFailure, Vector[B]]] { complete =>
      F.delay {
        val lookup = stage
        lookup.whenComplete(
          new BiConsumer[A, Throwable]:
            def accept(result: A, failure: Throwable): Unit =
              complete(lookupCompletion(name, recordType, Option(failure), decode(result)))
        )
        Some(F.delay(lookup.toCompletableFuture.cancel(true)).void)
      }
    }.timeoutTo(LookupTimeout, F.pure(Left(timeoutFailure)))

  /** `ttlForResults` in `RootPeersDNS/LocalRootPeers.hs`: the longest positive clipped answer
    * interval, with a one-minute retry when every lookup failed or no record was returned.
    */
  def ttlForResults(ttls: Vector[FiniteDuration]): FiniteDuration =
    ttls.maxOption.getOrElse(MinTtl)

  /** SRV records provide the TTL for the final address set; the address records only provide IPs.
    */
  def addressesWithServiceTtl(
      service: DnsServiceRecord,
      addresses: Vector[DnsAddressRecord]
  ): Vector[DnsAddressRecord] = addresses.map(_.copy(ttl = service.ttl))

  /** The Haskell resolver's deliberately small SRV subset: discard target `.`, keep only the
    * numerically lowest priority group, then draw one target by its advertised weight. The
    * inclusive draw and stable cumulative selection follow `DNSActions.hs` 's `randomR` /CDF rule
    * (`runWeightedLookup`, `DNSActions.hs` 463-477, ouroboros-network `c45735a5`): the weights are
    * `Word16` s, so `randomR (0, upperBound)` is random's `Word16` draw ([[Rng.uniformWord32R]]).
    */
  def selectService(
      records: Vector[DnsServiceRecord],
      rng: xyz.kd5ujc.scarab.network.selection.Rng
  ): Either[DnsServiceSelectionError, (DnsServiceRecord, xyz.kd5ujc.scarab.network.selection.Rng)] =
    val usable = records.filterNot(_.target.value == ".")
    val ordered = usable.sortBy(_.priority)
    ordered.headOption match
      case None => Left(DnsServiceSelectionError.NoUsableService)
      case Some(first) =>
        val firstPriority = first.priority
        val preferred = ordered.takeWhile(_.priority == firstPriority)
        val total = preferred.foldLeft(0L)((sum, service) => sum + service.weight.toLong)
        val (draw, next) = rng.uniformWord32R(0L, total)
        val selected = preferred
          .scanLeft(0L -> Option.empty[DnsServiceRecord]) { case ((before, _), service) =>
            (before + service.weight.toLong) -> Some(service)
          }
          .drop(1)
          .find { case (upper, _) => upper >= draw }
          .flatMap(_._2)
          .getOrElse(first)
        Right(selected -> next)

  private final class DnsJava[F[_]: Async](session: LookupSession) extends PeerDns[F]:
    private val F = Async[F]

    private def lookup[A](
        name: DnsName,
        recordType: DnsRecordType,
        kind: Int
    )(decode: Vector[Record] => Vector[A]): F[Either[DnsLookupFailure, Vector[A]]] =
      def begin(parsed: Name): F[Either[DnsLookupFailure, Vector[A]]] =
        awaitLookup(
          name,
          recordType,
          session.lookupAsync(parsed, kind),
          result => decode(result.getRecords.asScala.toVector)
        )

      DnsName.from(name.value) match
        case Left(error) => F.pure(Left(DnsLookupFailure(name, recordType, error.cause)))
        case Right(_) =>
          Either
            .catchOnly[TextParseException](Name.fromString(name.value))
            .fold(
              error => F.pure(Left(DnsLookupFailure(name, recordType, error))),
              begin
            )

    def addresses(name: DnsName): F[DnsAddressAnswer] =
      val v4 = lookup(name, DnsRecordType.A, Type.A) {
        _.collect { case record: ARecord => record }
          .flatMap(record =>
            address(record.getAddress.getAddress).map(DnsAddressRecord(_, ttl(record)))
          )
      }
      val v6 = lookup(name, DnsRecordType.AAAA, Type.AAAA) {
        _.collect { case record: AAAARecord => record }
          .flatMap(record =>
            address(record.getAddress.getAddress).map(DnsAddressRecord(_, ttl(record)))
          )
      }
      (v4, v6).parMapN { (a, aaaa) =>
        combineAddresses(a, aaaa)
      }

    def services(name: DnsName): F[Either[PeerDnsError, Vector[DnsServiceRecord]]] =
      lookup(name, DnsRecordType.SRV, Type.SRV) { records =>
        records.collect { case record: SRVRecord => record }.flatMap { record =>
          DnsName.from(record.getTarget.toString).toOption.map { target =>
            DnsServiceRecord(
              record.getPriority,
              record.getWeight,
              record.getPort,
              target,
              ttl(record)
            )
          }
        }
      }.map(_.leftMap(failure => PeerDnsError.Lookup(Vector(failure))))

    private def address(bytes: Array[Byte]): Option[IpAddress] = IpAddress.fromBytes(bytes)

    private def ttl(record: Record): FiniteDuration = clippedTtl(record.getTTL)
