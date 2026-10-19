// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Resource}
import cats.effect.implicits.*
import cats.effect.syntax.all.*
import cats.syntax.all.*
import com.comcast.ip4s.IpAddress
import java.util.concurrent.{CompletionStage, CompletionException, Executors, TimeoutException}
import java.util.function.BiConsumer
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import org.xbill.DNS.{ARecord, AAAARecord, Name, Record, SRVRecord, TextParseException, Type}
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
      .evalMap(executor =>
        Async[F].delay(LookupSession.defaultBuilder().executor(executor).build())
      )
      .map(new DnsJava[F](_))

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
    * inclusive draw and stable cumulative selection follow `DNSActions.hs` 's `randomR` /CDF rule.
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
        val (draw, next) = rng.between(0L, total)
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
              record.getPriority.toInt,
              record.getWeight.toInt,
              record.getPort.toInt,
              target,
              ttl(record)
            )
          }
        }
      }.map(_.leftMap(failure => PeerDnsError.Lookup(Vector(failure))))

    private def address(bytes: Array[Byte]): Option[IpAddress] = IpAddress.fromBytes(bytes)

    private def ttl(record: Record): FiniteDuration = clippedTtl(record.getTTL)
