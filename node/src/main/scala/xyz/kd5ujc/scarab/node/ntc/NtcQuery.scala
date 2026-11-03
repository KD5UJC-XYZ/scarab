// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.codec.DomainError
import cats.Monad
import cats.syntax.all.*
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  ByteStrings,
  Credential,
  DRep,
  DecoderVersion,
  GovActionId,
  TxIn
}
import xyz.kd5ujc.scarab.ledger.DetachedRecords
import xyz.kd5ujc.scarab.protocol.{Era, LocalStateQuery, NtcVersion}

/** The two native `LedgerPeersKind` cases of a peer snapshot query. */
enum LedgerPeerKind:
  case All, Big

/** A ledger (Shelley-based era) query as `Ouroboros.Consensus.Shelley.Ledger.Query` numbers them.
  * `Unsupported(tag)` is a query this node understands the framing of but does not answer.
  */
enum LedgerQuery:
  case GetLedgerTip
  case GetEpochNo
  case GetNonMyopicMemberRewards(credentials: Vector[Either[BigInt, Credential]])
  case GetCurrentPParams
  case GetProposedPParamsUpdates
  case GetStakeDistribution
  case GetUTxOByAddress(addresses: Set[Bytes])
  case GetUTxOWhole
  case DebugEpochState
  case GetCBOR(inner: LedgerQuery)
  case GetFilteredDelegationsAndRewardAccounts(credentials: Set[Credential])
  case GetGenesisConfig
  case DebugNewEpochState
  case DebugChainDepState
  case GetRewardProvenance
  case GetUTxOByTxIn(inputs: Set[TxIn])
  case GetStakePools
  case GetStakePoolParams(pools: Set[Bytes])
  case GetRewardInfoPools
  case GetPoolState(pools: Option[Set[Bytes]])
  case GetStakeSnapshots(pools: Option[Set[Bytes]])
  case GetPoolDistr(pools: Option[Set[Bytes]])
  case GetStakeDelegDeposits(credentials: Set[Credential])
  case GetConstitution
  case GetGovState
  case GetDRepState(credentials: Set[Credential])
  case GetDRepStakeDistr(dreps: Set[DRep])
  case GetCommitteeMembersState(cold: Set[Credential], hot: Set[Credential], statuses: Set[Int])
  case GetFilteredVoteDelegatees(credentials: Set[Credential])
  case GetAccountState
  case GetSPOStakeDistr(pools: Set[Bytes])
  case GetProposals(ids: Set[GovActionId])
  case GetRatifyState
  case GetFuturePParams
  case GetLedgerPeerSnapshotLegacy
  case GetLedgerPeerSnapshot(kind: LedgerPeerKind)
  case QueryStakePoolDefaultVote(pool: Bytes)
  case GetPoolDistr2(pools: Option[Set[Bytes]])
  case GetStakeDistribution2
  case GetMaxMajorProtocolVersion
  case GetDRepDelegations(dreps: Set[DRep])
  case Unsupported(tag: Int)

object LedgerQuery:
  /** Peel the only recursive constructor without consuming the worker's call stack. */
  def cborLayers(query: LedgerQuery): (Int, LedgerQuery) =
    @tailrec
    def loop(current: LedgerQuery, depth: Int): (Int, LedgerQuery) = current match
      case LedgerQuery.GetCBOR(inner) => loop(inner, depth + 1)
      case leaf                       => (depth, leaf)
    loop(query, 0)

/** The hard-fork-combinator specific queries. */
enum HardForkQuery:
  case GetInterpreter
  case GetCurrentEra

/** An era of the reference's `CardanoEras`, in its order: the index a query names an era by is the
  * case's ordinal (`type CardanoEras c = ByronBlock ': CardanoShelleyEras c`,
  * `ouroboros-consensus-cardano/src/ouroboros-consensus-cardano/Ouroboros/Consensus/Cardano/Block.hs:245-257`,
  * ouroboros-consensus 82ecba32). Every node-to-client version this node speaks enables all eight
  * for queries, Dijkstra too, though the node has no Dijkstra ledger: a query of it is read and
  * answered with the era mismatch, as the reference answers it before that era (#720).
  */
enum QueryEra:
  case Byron, Shelley, Allegra, Mary, Alonzo, Babbage, Conway, Dijkstra

  def index: Int = ordinal

  /** `singleEraName`, the name an era mismatch gives the era: the ledger's `eraName`
    * (`Shelley/ShelleyHFC.hs:256`; `type EraName DijkstraEra = "Dijkstra"`,
    * `Cardano/Ledger/Internal/Definition/Era.hs:138`, cardano-ledger f649f975) and "Byron"
    * (`Byron/ByronHFC.hs:252`).
    */
  def name: String = toString

object QueryEra:
  def of(era: Era): QueryEra = era match
    case Era.Byron   => Byron
    case Era.Shelley => Shelley
    case Era.Allegra => Allegra
    case Era.Mary    => Mary
    case Era.Alonzo  => Alonzo
    case Era.Babbage => Babbage
    case Era.Conway  => Conway

  /** The era of an index, as `nsFromIndex` finds it; none past the last. */
  def fromIndex(index: Int): Option[QueryEra] = values.lift(index)

/** A top-level node-to-client query: `Ouroboros.Consensus.Ledger.Query` (tags 0 to 4) with the
  * hard-fork-combinator block-query envelope (`QueryIfCurrent` 0, `QueryAnytime` 1, `QueryHardFork`
  * 2).
  */
enum NtcQuery:
  /** `QueryIfCurrent` of a Shelley-based era: the decoder never gives it [[QueryEra.Byron]]. */
  case IfCurrent(era: QueryEra, query: LedgerQuery)

  /** `QueryIfCurrent` of Byron's one query, `GetUpdateInterfaceState`. */
  case ByronUpdateInterfaceState

  /** `QueryAnytime GetEraStart` for an era. */
  case EraStart(era: QueryEra)
  case HardFork(query: HardForkQuery)
  case SystemStart
  case ChainBlockNo
  case ChainPoint
  case DebugLedgerConfig

object NtcQuery:
  /** Logging must not call the recursively derived `toString` before truncating it. */
  def describe(query: NtcQuery): String = query match
    case NtcQuery.IfCurrent(era, inner) =>
      val (depth, leaf) = LedgerQuery.cborLayers(inner)
      val shown = if depth == 0 then leaf.toString else s"GetCBOR[$depth]($leaf)"
      s"IfCurrent(${era.index},$shown)".take(120)
    case other => other.toString.take(120)

  enum Error extends DomainError:
    case UnsupportedVersion(version: NtcVersion)
    case MalformedCbor(cause: Cbor.Error)
    case ExpectedArray(field: String)
    case ExpectedUnsigned(field: String)
    case ExpectedBytes(field: String)
    // `field` is a `String` like the cases around it. Typing the field names, rather than keying
    // errors by a description, is owed across this enum under #575 (#82 and #189, cited before, are
    // both closed).
    case IndefiniteBytes(field: String)
    case InvalidHash(field: String)
    case InvalidCredential
    case InvalidDRep
    case InvalidTxIn(cause: xyz.kd5ujc.scarab.ledger.LedgerDecodeError)
    case InvalidAddress(cause: xyz.kd5ujc.scarab.ledger.LedgerDecodeError)
    case InvalidNonMyopicCredential
    case ExpectedCoin
    case Empty(field: String)
    case Arity(field: String, expected: Int, actual: Int)

    /** A `RecD` record written as a non-empty indefinite list, which the reference refuses below
      * decoder version 12.
      */
    case IndefiniteRecord(field: String)
    case Word16Range(field: String)
    case InvalidTxId

    /** A list the reference reads with a definite length only, written indefinite (#709). */
    case IndefiniteList(field: String)

    /** A set read with `fromCBOR` without its tag 258. */
    case ExpectedSetTag(field: String)

    /** A set read with `fromCBOR` whose elements are not in strictly ascending order. */
    case NotAscending(field: String)

    /** A set read with the ledger's decoder that holds two elements alike. */
    case Duplicate(field: String)
    case MemberStatusRange

    /** A set read with the ledger's decoder below version 9 under tag 258, which it does not take.
      */
    case UnexpectedSetTag(field: String)

    /** A query that `requireCG` guards, of an era before Conway. */
    case ConwayOnly(tag: Int)
    case UnknownTag(field: String, tag: Int)
    case Constructor(field: String, tag: Int, arity: Int)

    /** An era index past the last era of `CardanoEras`, which `nsFromIndex` does not find. */
    case UnknownEra(index: Int)

    /** An unsigned integer the reference reads with `decodeWord8`, past 255. */
    case Word8Range(field: String)

    def message: String = this match
      case UnsupportedVersion(version) =>
        s"query is not supported at node-to-client version ${version.number}"
      case MalformedCbor(detail)          => s"invalid query CBOR: $detail"
      case ExpectedArray(field)           => s"$field: expected array"
      case ExpectedUnsigned(field)        => s"$field: expected small unsigned integer"
      case ExpectedBytes(field)           => s"$field: expected byte string"
      case IndefiniteBytes(field)         => s"$field: expected a definite-length byte string"
      case InvalidHash(field)             => s"$field: expected 28 bytes"
      case InvalidCredential              => "credential: bad kind or hash"
      case InvalidDRep                    => "drep: bad kind or arity"
      case InvalidTxIn(detail)            => s"input: ${detail.show}"
      case InvalidAddress(detail)         => s"address: ${detail.show}"
      case InvalidNonMyopicCredential     => "non-myopic credential: expected [tag, value]"
      case ExpectedCoin                   => "non-myopic coin: expected unsigned integer"
      case Empty(field)                   => s"$field: empty"
      case Arity(field, expected, actual) => s"$field: expected $expected elements, got $actual"
      case IndefiniteRecord(field) =>
        s"$field: expected a definite list, or an empty indefinite list before its fields"
      case Word16Range(field)      => s"$field: expected an unsigned integer of 16 bits"
      case InvalidTxId             => "gov action tx id: expected 32 bytes"
      case IndefiniteList(field)   => s"$field: expected a definite list"
      case ExpectedSetTag(field)   => s"$field: expected a set under tag 258"
      case NotAscending(field)     => s"$field: expected elements in strictly ascending order"
      case Duplicate(field)        => s"$field: an element twice"
      case MemberStatusRange       => "member status: expected 0, 1 or 2"
      case UnexpectedSetTag(field) => s"$field: a set under tag 258 before version 9"
      case ConwayOnly(tag)         => s"ledger query $tag: not supported before Conway"
      case UnknownTag(field, tag)  => s"$field: unknown tag $tag"
      case Constructor(field, tag, arity) => s"$field: unknown tag $tag or arity $arity"
      case UnknownEra(index)              => s"era index: invalid index $index"
      case Word8Range(field)              => s"$field: expected an unsigned integer of 8 bits"

  private type R[A] = Either[Error, A]

  /** A query is read with no budget but its size: the reference reads LocalStateQuery with no byte
    * or item limit (`LocalStateQueryEngine`), and the wire bounds only its nesting, as recorded in
    * docs/decisions.md (#474, #749).
    */
  private val limits = Cbor.Limits.Unbounded

  /** How a query of an era reads its arguments: at the era's lowest protocol version, as the
    * reference reads them (`fromEraCBOR @era`, `Era.hs:236-243`, cardano-ledger f649f975): its byte
    * strings (every era from Shelley to Conway is below version 12 and reads a definite string;
    * evidence #437), its addresses as the era reads an output's ([[Address.Mode.of]]), its sets by
    * the version's rules ([[set]]); and whether the era has Conway's governance, without which a
    * query that `requireCG` guards is refused (`Shelley/Ledger/Query.hs:936-942`,
    * ouroboros-consensus 82ecba32; #724).
    *
    * Dijkstra, which the node has no ledger of, reads at its own lowest version, 12
    * ([[DecoderVersion.Dijkstra]]): a chunked byte string, and a `GovActionId` as a record of its
    * own (`decodeGovActionId`, `Conway/Governance/Procedures.hs:182-190`); a credential and a DRep
    * as below 12, where `Summands` is `decodeRecordSum` too (`Decoding/Coders.hs:514`,
    * `Credential.hs:327-344`, `DRep.hs:90-110`); an address as Conway's (`fromCborBothAddr` takes
    * the version-9 branch, `Address.hs:459-467`); with Conway's governance (`getConwayEraGovDict _ =
    * Just ConwayEraGovDict`, `Shelley/Eras.hs:215`, ouroboros-consensus 82ecba32; #720).
    */
  private[ntc] final case class Reading(
      version: DecoderVersion,
      addresses: Address.Mode,
      governance: Boolean
  ):
    def strings: ByteStrings = ByteStrings.of(version)

  private[ntc] object Reading:
    def of(era: Era): Reading =
      Reading(DecoderVersion.of(era), Address.Mode.of(era), era.ordinal >= Era.Conway.ordinal)

    val dijkstra: Reading = Reading(DecoderVersion.Dijkstra, Address.Mode.Strict, true)

  /** The reading of a Shelley-based era's query; none for Byron, whose query is not a ledger one.
    */
  private def reading(era: QueryEra): Option[Reading] = era match
    case QueryEra.Byron    => None
    case QueryEra.Shelley  => Some(Reading.of(Era.Shelley))
    case QueryEra.Allegra  => Some(Reading.of(Era.Allegra))
    case QueryEra.Mary     => Some(Reading.of(Era.Mary))
    case QueryEra.Alonzo   => Some(Reading.of(Era.Alonzo))
    case QueryEra.Babbage  => Some(Reading.of(Era.Babbage))
    case QueryEra.Conway   => Some(Reading.of(Era.Conway))
    case QueryEra.Dijkstra => Some(Reading.dijkstra)

  def decode(raw: Bytes): R[NtcQuery] =
    DetachedRecords.read(raw, limits, shape)(Error.MalformedCbor(_))(top)

  /** Where a query of `MsgQuery` ends, as the reference's decoder of queries reads it from the
    * stream: one holding a detached record spans the record's fields after it, and one whose
    * record's fields have not all arrived is short ([[DetachedRecords.prefix]]); one the walk
    * refuses is refused, as the reference's stream decoder fails on it whatever follows. A query
    * without an empty indefinite list `9f ff` is one item of a tree, as before (#700).
    */
  val reader: LocalStateQuery.QueryReader = input =>
    if !DetachedRecords.mayHold(input) then LocalStateQuery.QuerySpan.Item
    else
      DetachedRecords.prefix(input, shape) match
        case DetachedRecords.Prefix.Item(end, records) if !records.isEmpty =>
          LocalStateQuery.QuerySpan.Prefix(end)
        case DetachedRecords.Prefix.Short      => LocalStateQuery.QuerySpan.Short
        case DetachedRecords.Prefix.Refused    => LocalStateQuery.QuerySpan.Refused
        case DetachedRecords.Prefix.Item(_, _) => LocalStateQuery.QuerySpan.Item

  /** The query as the reference's decoders read it, as far as it holds a `RecD` record
    * ([[DetachedRecords]]): the set of `GovActionId` of `GetProposals` (`(2, 31)`,
    * `Shelley/Ledger/Query.hs:981`, ouroboros-consensus 82ecba32), in the hard-fork combinator's
    * `QueryIfCurrent` of an era below decoder version 12, where a record written as an empty
    * indefinite list has its fields after it (#700), and in any `GetCBOR` (`(2, 9)`). The era index
    * says which: Byron's query is a bare tag (`decodeByronQuery`) and Dijkstra's reads at version
    * 12 ([[Reading.dijkstra]]), so neither holds such a record (#720). No other argument of a query
    * is a `RecD` record: a `TxIn` is `decodeRecordNamed` over its fields (`TxIn.hs:103-108`), a
    * credential and a DRep `Summands` (`Credential.hs:327-334`, `DRep.hs:90-99`), cardano-ledger
    * f649f975.
    */
  private[ntc] val shape: DetachedRecords.Shape =
    import DetachedRecords.Shape.*
    lazy val ledger: DetachedRecords.Shape = Sum {
      case 9  => List(ledger)
      case 31 => List(ListOf(DetachedRecords.Shapes.actionId))
      case _  => Nil
    }
    val below12 = Sum {
      case era if era >= QueryEra.Shelley.index && era <= QueryEra.Conway.index => List(ledger)
      case _                                                                    => Nil
    }
    val inEra = Sum {
      case 0 => List(below12)
      case _ => Nil
    }
    Sum {
      case 0 => List(inEra)
      case _ => Nil
    }

  /** Apply the reference client codec's supported-version contract to an incoming query. */
  def decode(raw: Bytes, version: NtcVersion): R[NtcQuery] =
    decode(raw).flatMap { query =>
      Either.cond(
        QueryVersion.supports(version, query),
        query,
        Error.UnsupportedVersion(version)
      )
    }

  private def arr(n: Node, what: String): R[Vector[Node]] = n.value match
    case Value.Arr(xs) => Right(xs)
    case _             => Left(Error.ExpectedArray(what))

  private def uint(n: Node, what: String): R[Int] = n.value match
    case Value.UInt(v) if v.isValidInt => Right(v.toInt)
    case _                             => Left(Error.ExpectedUnsigned(what))

  private def bytes(n: Node, what: String)(using strings: ByteStrings): R[Bytes] = n.value match
    case Value.ByteString(b) =>
      // The reference joins a chunked string's chunks from decoder version 12 (`decodeBytes`), so the
      // form is refused only where the era's policy is definite. `ByteStrings.reads` is the one place
      // that answers this, shared with `CborRead.bytes`.
      Either.cond(strings.reads(n), b, Error.IndefiniteBytes(what))
    case _ => Left(Error.ExpectedBytes(what))

  /** A list the reference reads with `decodeListLen`, `enforceSize` or `decodeListLenOf`: a
    * definite list only (cborg's `decodeListLen` takes a definite length; #709). The envelopes of a
    * query are read so (`queryDecodeNodeToClient`, `Ouroboros/Consensus/Ledger/Query.hs:483`; the
    * hard-fork combinator's block query, `decodeNS` and its anytime and hard-fork queries,
    * `HardFork/Combinator/Serialisation/SerialiseNodeToClient.hs:410-478`, `Common.hs:420-423`,
    * `HardFork/Combinator/Ledger/Query.hs:545-551`; `decodeShelleyQuery`,
    * `Shelley/Ledger/Query.hs:921`; ouroboros-consensus 82ecba32), as are a plain `Maybe` and
    * `Either` of cardano-binary.
    */
  private def definite(n: Node, what: String): R[Vector[Node]] =
    arr(n, what).filterOrElse(_ => !n.isIndefinite, Error.IndefiniteList(what))

  /** A set an argument reads with the ledger's decoder at the era's lowest protocol version
    * (`LC.fromEraCBOR @era`, `decodeSet`,
    * `libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Decoder.hs:952-976,1043-1077`,
    * cardano-ledger f649f975): a list, definite or indefinite; from version 9, optionally under tag
    * 258 and without two elements alike (`decodeSetEnforceNoDuplicates`); below it, untagged, two
    * elements alike being one (`Set.fromList` over `decodeCollection`).
    */
  private def set[A](n: Node, what: String)(each: Node => R[A])(using read: Reading): R[Set[A]] =
    val conway = read.version.atLeast(9)
    val items = n.value match
      case Value.Tag(t, inner) if t == 258 =>
        if conway then arr(inner, what) else Left(Error.UnexpectedSetTag(what))
      case _ => arr(n, what)
    items
      .flatMap(_.traverse(each))
      .flatMap(xs =>
        Either.cond(!conway || xs.distinct.size == xs.size, xs.toSet, Error.Duplicate(what))
      )

  /** A set an argument reads with `fromCBOR` of cardano-binary (`decodeSetSkel`,
    * `cardano-binary/src/Cardano/Binary/FromCBOR.hs:550-576`, cardano-base 1.9.1.0): tag 258 around
    * a definite list whose elements are in strictly ascending order, as the type's `Ord` orders
    * them. Its elements read a definite byte string in every era: a hash's `fromCBOR` is cborg's
    * `decodeBytes`, and a credential's reads at `shelleyProtVer` (`toPlainDecoder Nothing
    * shelleyProtVer decCBOR`, `Cardano/Ledger/Credential.hs:349-350`, cardano-ledger f649f975), so
    * a Dijkstra query reads them as Conway's does (#720).
    */
  private def plainSet[A](n: Node, what: String)(each: ByteStrings ?=> Node => R[A])(using
      order: Ordering[A]
  ): R[Vector[A]] = n.value match
    case Value.Tag(t, inner) if t == 258 =>
      definite(inner, what)
        .flatMap(_.traverse(each(using ByteStrings.Definite)))
        .flatMap(xs =>
          Either.cond(xs.lazyZip(xs.drop(1)).forall(order.lt), xs, Error.NotAscending(what))
        )
    case _ => Left(Error.ExpectedSetTag(what))

  // The `Ord` of a key hash, of a `Hash`, is its bytes', unsigned and lexicographic: `Bytes`'s own.

  /** The `Ord` of `Credential`, derived: a script credential before a key one (`data Credential =
    * ScriptHashObj | KeyHashObj`, `Cardano/Ledger/Credential.hs:98-101`), then the hash.
    */
  private given Ordering[Credential] = Ordering.by {
    case Credential.Script(h) => (0, h)
    case Credential.Key(h)    => (1, h)
  }

  /** The `Ord` of `Either Coin (Credential Staking)`: every `Left` before every `Right`. */
  private given Ordering[Either[BigInt, Credential]] = (a, b) =>
    (a, b) match
      case (Left(x), Left(y))   => x.compare(y)
      case (Left(_), Right(_))  => -1
      case (Right(_), Left(_))  => 1
      case (Right(x), Right(y)) => summon[Ordering[Credential]].compare(x, y)

  private def credential(n: Node)(using ByteStrings): R[Credential] =
    arr(n, "credential").flatMap {
      case Vector(t, h) =>
        (uint(t, "credential kind"), bytes(h, "credential hash")).tupled.flatMap {
          case (0, hash) if hash.size == 28 => Right(Credential.Key(hash))
          case (1, hash) if hash.size == 28 => Right(Credential.Script(hash))
          case _                            => Left(Error.InvalidCredential)
        }
      case xs => Left(Error.Arity("credential", 2, xs.size))
    }

  /** An address, read as the era reads an output's (`decCBOR` of `Addr`), kept as the address it
    * reads, which the UTxO's addresses are compared with.
    */
  private def address(n: Node)(using ByteStrings, Address.Mode): R[Bytes] =
    bytes(n, "address").flatMap(b =>
      Address.decode(b, summon[Address.Mode]).bimap(Error.InvalidAddress(_), _.raw)
    )

  private def keyHash(n: Node)(using ByteStrings): R[Bytes] =
    bytes(n, "key hash").filterOrElse(_.size == 28, Error.InvalidHash("key hash"))

  // Query arguments read a definite byte string, as the reference's query decoders do: consensus
  // decodes them with `fromEraCBOR @era` (the `(2, 15)` GetUTxOByTxIn case,
  // `Ouroboros/Consensus/Shelley/Ledger/Query.hs:960` at ouroboros-consensus 82ecba32), which is
  // `eraDecoder @era decCBOR` at the era's *lowest* protocol version (`Era.hs:236-243`, cardano-ledger
  // f649f975) — 2..9 for Shelley to Conway, below the version 12 where `decodeBytes` starts taking a
  // chunked string, and 12 for Dijkstra, which takes it ([[Reading.dijkstra]]). Evidence: #437, #720.
  private def txIn(n: Node)(using strings: ByteStrings): R[TxIn] =
    TxIn.decode(n)(using strings).leftMap(Error.InvalidTxIn(_))

  private def drep(n: Node)(using ByteStrings): R[DRep] =
    arr(n, "drep").flatMap { xs =>
      xs.headOption.toRight(Error.Empty("drep")).flatMap(uint(_, "drep kind")).flatMap {
        case 0 if xs.size == 2 => keyHash(xs(1)).map(h => DRep.Registered(Credential.Key(h)))
        case 1 if xs.size == 2 => keyHash(xs(1)).map(h => DRep.Registered(Credential.Script(h)))
        case 2 if xs.size == 1 => Right(DRep.AlwaysAbstain)
        case 3 if xs.size == 1 => Right(DRep.AlwaysNoConfidence)
        case _                 => Left(Error.InvalidDRep)
      }
    }

  /** `GovActionId`, the `RecD` record of a transaction id and a `Word16` index
    * (`Conway/Governance/Procedures.hs:162,182-190`, cardano-ledger f649f975): a definite list of
    * the two, or the two after an empty indefinite list ([[Node.isDetached]]); a non-empty
    * indefinite list fails the reference's check for its break before its first field (`decodeCount
    * (RecD cn) n`, `Decoding/Coders.hs:482`; `decodeListLikeT`, `Decoding/Decoder.hs:690-708`).
    * From version 12 it is `decodeRecordNamed` over the two fields (`decodeGovActionId`,
    * `Procedures.hs:184-190`): a definite or an indefinite list of the two, and no detached record,
    * which the walk of a Dijkstra query does not make ([[shape]]; #720).
    */
  private def govActionId(n: Node)(using read: Reading)(using ByteStrings): R[GovActionId] =
    val what = "gov action id"
    arr(n, what).flatMap {
      case _ if n.isIndefinite && !n.isDetached && !read.version.atLeast(12) =>
        Left(Error.IndefiniteRecord(what))
      case Vector(i, ix) =>
        (
          bytes(i, "gov action tx id").filterOrElse(_.size == 32, Error.InvalidTxId),
          uint(ix, "gov action index").filterOrElse(
            _ <= 0xffff,
            Error.Word16Range("gov action index")
          )
        ).mapN((t, k) => GovActionId(t, BigInt(k)))
      case xs => Left(Error.Arity(what, 2, xs.size))
    }

  /** `Maybe (Set a)` read with `fromCBOR`: `[]` for nothing, `[set]` for just, a definite list
    * (`decodeMaybe`, `cardano-binary/src/Cardano/Binary/FromCBOR.hs:445-453`), of a plain set.
    */
  private def maybeSet[A: Ordering](n: Node, what: String)(
      each: ByteStrings ?=> Node => R[A]
  ): R[Option[Set[A]]] =
    definite(n, what).flatMap {
      case Vector()  => Right(None)
      case Vector(s) => plainSet(s, what)(each).map(xs => Some(xs.toSet))
      case xs        => Left(Error.Arity(what, 1, xs.size))
    }

  private def top(root: Node): R[NtcQuery] =
    definite(root, "query").flatMap { xs =>
      xs.headOption.toRight(Error.Empty("query")).flatMap(uint(_, "query tag")).flatMap {
        case 0 if xs.size == 2 => hardFork(xs(1))
        case 1 if xs.size == 1 => Right(NtcQuery.SystemStart)
        case 2 if xs.size == 1 => Right(NtcQuery.ChainBlockNo)
        case 3 if xs.size == 1 => Right(NtcQuery.ChainPoint)
        case 4 if xs.size == 1 => Right(NtcQuery.DebugLedgerConfig)
        case t                 => Left(Error.Constructor("query", t, xs.size))
      }
    }

  private def hardFork(n: Node): R[NtcQuery] =
    definite(n, "block query").flatMap { xs =>
      xs.headOption
        .toRight(Error.Empty("block query"))
        .flatMap(uint(_, "block query tag"))
        .flatMap {
          case 0 if xs.size == 2 =>
            // `decodeNS`: two items, the era index and the era's own query
            definite(xs(1), "query in era").flatMap {
              case Vector(e, q) =>
                eraIndex(e).flatMap { era =>
                  reading(era).fold(byron(q))(r => shelley(q, r).map(NtcQuery.IfCurrent(era, _)))
                }
              case qs => Left(Error.Arity("query in era", 2, qs.size))
            }
          case 1 if xs.size == 3 =>
            // [1, <Some GetEraStart>, era index]: the query is `[0]` (GetEraStart)
            (definite(xs(1), "anytime query"), eraIndex(xs(2))).tupled.flatMap {
              // QueryAnytime's Serialise instance: Ledger/Query.hs:536-551 at 82ecba3.
              case (Vector(tag), era) =>
                uint(tag, "anytime query tag").flatMap {
                  case 0 => Right(NtcQuery.EraStart(era))
                  case t => Left(Error.UnknownTag("anytime query", t))
                }
              case (q, _) => Left(Error.Arity("anytime query", 1, q.size))
            }
          case 2 if xs.size == 2 =>
            // SerialiseNodeToClient.hs:410-417 at 82ecba3 enforces a singleton.
            definite(xs(1), "hard fork query").flatMap {
              case Vector(tag) =>
                uint(tag, "hard fork tag").flatMap {
                  case 0 => Right(NtcQuery.HardFork(HardForkQuery.GetInterpreter))
                  case 1 => Right(NtcQuery.HardFork(HardForkQuery.GetCurrentEra))
                  case t => Left(Error.UnknownTag("hard fork query", t))
                }
              case qs => Left(Error.Arity("hard fork query", 1, qs.size))
            }
          case t => Left(Error.Constructor("block query", t, xs.size))
        }
    }

  /** An era index as `decodeNS` and the `Serialise` of `EraIndex` read it: `decodeWord8`, then
    * `nsFromIndex`, which fails past the last era ("decodeNS: invalid index", "EraIndex: invalid
    * index"; `HardFork/Combinator/Serialisation/Common.hs:420-433`,
    * `HardFork/Combinator/Abstract/SingleEraBlock.hs:169-175`, ouroboros-consensus 82ecba32; #720).
    */
  private def eraIndex(n: Node): R[QueryEra] =
    uint(n, "era index")
      .filterOrElse(_ <= 0xff, Error.Word8Range("era index"))
      .flatMap(i => QueryEra.fromIndex(i).toRight(Error.UnknownEra(i)))

  /** Byron's block query, `decodeByronQuery`: a bare `decodeWord8` tag, 0 for
    * `GetUpdateInterfaceState` and nothing else
    * (`ouroboros-consensus-cardano/src/byron/Ouroboros/Consensus/Byron/Ledger/Ledger.hs:556-561`,
    * ouroboros-consensus 82ecba32; #720).
    */
  private def byron(n: Node): R[NtcQuery] =
    uint(n, "byron query tag").flatMap {
      case 0 => Right(NtcQuery.ByronUpdateInterfaceState)
      case t => Left(Error.UnknownTag("byron query", t))
    }

  /** The Shelley block query, reading its byte strings under `policy`. Package-scoped: the caller
    * has already decoded the era, so this is not a query entry point of its own.
    */
  private[ntc] def shelley(n: Node, read: Reading): R[LedgerQuery] =
    given Reading = read
    given ByteStrings = read.strings
    given Address.Mode = read.addresses
    final case class Pending(node: Node, depth: Int)
    Monad[R].tailRecM(Pending(n, 0)) { pending =>
      definite(pending.node, "ledger query").flatMap {
        case Vector(tag, inner) if tag.value == Value.UInt(9) =>
          Right(Left(Pending(inner, pending.depth + 1)))
        case _ =>
          shelleyLeaf(pending.node).map { leaf =>
            Right((0 until pending.depth).foldLeft(leaf)((q, _) => LedgerQuery.GetCBOR(q)))
          }
      }
    }

  private def shelleyLeaf(n: Node)(using
      read: Reading
  )(using
      ByteStrings,
      Address.Mode
  ): R[LedgerQuery] =
    definite(n, "ledger query").flatMap { xs =>
      xs.headOption
        .toRight(Error.Empty("ledger query"))
        .flatMap(uint(_, "ledger query tag"))
        .flatMap { tag =>
          import LedgerQuery.*
          def arg1[A](f: Node => R[A]): R[A] =
            if xs.size == 2 then f(xs(1)) else Left(Error.Arity(s"ledger query $tag", 2, xs.size))
          def none(q: LedgerQuery): R[LedgerQuery] =
            if xs.size == 1 then Right(q) else Left(Error.Arity(s"ledger query $tag", 1, xs.size))
          // the queries `requireCG` guards, refused before Conway whatever their arguments
          val governance = Set(23, 25, 26, 27, 28, 30, 31, 32, 33, 35, 39)
          if governance(tag) && !read.governance then Left(Error.ConwayOnly(tag))
          else
            tag match
              case 0 => none(GetLedgerTip)
              case 1 => none(GetEpochNo)
              case 2 =>
                arg1(
                  plainSet(_, "non-myopic credentials")(c =>
                    definite(c, "non-myopic credential").flatMap {
                      case Vector(k, v) =>
                        uint(k, "non-myopic tag").flatMap {
                          case 0 =>
                            v.value match
                              case Value.UInt(coin) => Right(Left(coin))
                              case _                => Left(Error.ExpectedCoin)
                          case 1 => credential(v).map(Right(_))
                          case _ => Left(Error.InvalidNonMyopicCredential)
                        }
                      case _ => Left(Error.InvalidNonMyopicCredential)
                    }
                  )
                ).map(GetNonMyopicMemberRewards(_))
              case 3 => none(GetCurrentPParams)
              case 4 => none(GetProposedPParamsUpdates)
              case 5 => none(GetStakeDistribution)
              case 6 => arg1(set(_, "addresses")(address)).map(GetUTxOByAddress(_))
              case 7 => none(GetUTxOWhole)
              case 8 => none(DebugEpochState)
              case 9 => Left(Error.Arity("ledger query 9", 2, xs.size))
              case 10 =>
                arg1(set(_, "credentials")(credential))
                  .map(GetFilteredDelegationsAndRewardAccounts(_))
              case 11 => none(GetGenesisConfig)
              case 12 => none(DebugNewEpochState)
              case 13 => none(DebugChainDepState)
              case 14 => none(GetRewardProvenance)
              case 15 => arg1(set(_, "inputs")(txIn)).map(GetUTxOByTxIn(_))
              case 16 => none(GetStakePools)
              case 17 => arg1(plainSet(_, "pools")(keyHash)).map(xs => GetStakePoolParams(xs.toSet))
              case 18 => none(GetRewardInfoPools)
              case 19 => arg1(maybeSet(_, "pools")(keyHash)).map(GetPoolState(_))
              case 20 => arg1(maybeSet(_, "pools")(keyHash)).map(GetStakeSnapshots(_))
              case 21 => arg1(maybeSet(_, "pools")(keyHash)).map(GetPoolDistr(_))
              case 22 =>
                arg1(plainSet(_, "credentials")(credential))
                  .map(xs => GetStakeDelegDeposits(xs.toSet))
              case 23 => none(GetConstitution)
              case 24 => none(GetGovState)
              case 25 =>
                arg1(plainSet(_, "credentials")(credential)).map(xs => GetDRepState(xs.toSet))
              case 26 => arg1(set(_, "dreps")(drep)).map(GetDRepStakeDistr(_))
              case 27 =>
                if xs.size != 4 then Left(Error.Arity("committee members state", 4, xs.size))
                else
                  (
                    plainSet(xs(1), "cold credentials")(credential).map(_.toSet),
                    plainSet(xs(2), "hot credentials")(credential).map(_.toSet),
                    // `MemberStatus` is `decodeEnumBounded` of its three constructors
                    set(xs(3), "statuses")(
                      uint(_, "member status").filterOrElse(_ <= 2, Error.MemberStatusRange)
                    )
                  ).mapN(GetCommitteeMembersState(_, _, _))
              case 28 => arg1(set(_, "credentials")(credential)).map(GetFilteredVoteDelegatees(_))
              case 29 => none(GetAccountState)
              case 30 => arg1(set(_, "pools")(keyHash)).map(GetSPOStakeDistr(_))
              case 31 => arg1(set(_, "ids")(govActionId)).map(GetProposals(_))
              case 32 => none(GetRatifyState)
              case 33 => none(GetFuturePParams)
              case 34 =>
                if xs.size == 1 then Right(GetLedgerPeerSnapshotLegacy)
                else
                  arg1(uint(_, "peer kind")).flatMap {
                    case n if n == 0 => Right(GetLedgerPeerSnapshot(LedgerPeerKind.All))
                    case n if n == 1 => Right(GetLedgerPeerSnapshot(LedgerPeerKind.Big))
                    case kind        => Left(Error.UnknownTag("ledger peer kind", kind))
                  }
              case 35    => arg1(keyHash).map(QueryStakePoolDefaultVote(_))
              case 36    => arg1(maybeSet(_, "pools")(keyHash)).map(GetPoolDistr2(_))
              case 37    => none(GetStakeDistribution2)
              case 38    => none(GetMaxMajorProtocolVersion)
              case 39    => arg1(set(_, "dreps")(drep)).map(GetDRepDelegations(_))
              case other => Left(Error.UnknownTag("ledger query", other))
        }
    }
