// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.codec.DomainError
import cats.Monad
import cats.syntax.all.*
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{ByteStrings, Credential, DRep, DecoderVersion, GovActionId, TxIn}
import xyz.kd5ujc.scarab.protocol.{Era, NtcVersion}

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

/** A top-level node-to-client query: `Ouroboros.Consensus.Ledger.Query` (tags 0 to 4) with the
  * hard-fork-combinator block-query envelope (`QueryIfCurrent` 0, `QueryAnytime` 1, `QueryHardFork`
  * 2).
  */
enum NtcQuery:
  case IfCurrent(era: Int, query: LedgerQuery)

  /** `QueryAnytime GetEraStart` for an era index. */
  case EraStart(era: Int)
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
      s"IfCurrent($era,$shown)".take(120)
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
    case InvalidNonMyopicCredential
    case ExpectedCoin
    case Empty(field: String)
    case Arity(field: String, expected: Int, actual: Int)
    case UnknownTag(field: String, tag: Int)
    case Constructor(field: String, tag: Int, arity: Int)

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
      case InvalidNonMyopicCredential     => "non-myopic credential: expected [tag, value]"
      case ExpectedCoin                   => "non-myopic coin: expected unsigned integer"
      case Empty(field)                   => s"$field: empty"
      case Arity(field, expected, actual) => s"$field: expected $expected elements, got $actual"
      case UnknownTag(field, tag)         => s"$field: unknown tag $tag"
      case Constructor(field, tag, arity) => s"$field: unknown tag $tag or arity $arity"

  private type R[A] = Either[Error, A]
  private val limits = Cbor.Limits(
    maxInputBytes = 2000000,
    maxItems = 200000,
    maxStringBytes = 1000000
  )

  /** The byte-string policy of a query argument. The reference decodes the argument at the era's
    * lowest protocol version (`fromEraCBOR @era`, `Era.hs:236-243`, cardano-ledger f649f975), so
    * the form follows the era's own version rather than a hard-coded answer; every era from Shelley
    * to Conway is below version 12 and therefore reads a definite string.
    *
    * An era index this node does not know falls back to `Definite`, the answer every era this node
    * knows gives: the reference decodes an argument only for an era it knows, so there is no
    * reference verdict here, and this is stated as our choice rather than measured. Evidence: #437.
    */
  private def strings(era: Int): ByteStrings =
    Era.fromIndex(BigInt(era)).fold(ByteStrings.Definite)(e => ByteStrings.of(DecoderVersion.of(e)))

  def decode(raw: Bytes): R[NtcQuery] =
    Cbor.decode(raw, limits).leftMap(Error.MalformedCbor(_)).flatMap(top)

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

  /** Elements of a set: an array, optionally under tag 258. */
  private def elements(n: Node, what: String): R[Vector[Node]] = n.value match
    case Value.Tag(t, inner) if t == 258 => arr(inner, what)
    case _                               => arr(n, what)

  private def set[A](n: Node, what: String)(each: Node => R[A]): R[Set[A]] =
    elements(n, what).flatMap(_.traverse(each)).map(_.toSet)

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

  private def keyHash(n: Node)(using ByteStrings): R[Bytes] =
    bytes(n, "key hash").filterOrElse(_.size == 28, Error.InvalidHash("key hash"))

  // Query arguments read a definite byte string, as the reference's query decoders do: consensus
  // decodes them with `fromEraCBOR @era` (the `(2, 15)` GetUTxOByTxIn case,
  // `Ouroboros/Consensus/Shelley/Ledger/Query.hs:960` at ouroboros-consensus 82ecba32), which is
  // `eraDecoder @era decCBOR` at the era's *lowest* protocol version (`Era.hs:236-243`, cardano-ledger
  // f649f975) — 2..9, below the version 12 where `decodeBytes` starts taking a chunked string.
  // Evidence: #437.
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

  private def govActionId(n: Node)(using ByteStrings): R[GovActionId] =
    arr(n, "gov action id").flatMap {
      case Vector(i, ix) =>
        (bytes(i, "gov action tx id"), uint(ix, "gov action index")).mapN((t, k) =>
          GovActionId(t, BigInt(k))
        )
      case xs => Left(Error.Arity("gov action id", 2, xs.size))
    }

  /** `Maybe (Set a)`: `[]` for nothing, `[set]` for just. */
  private def maybeSet[A](n: Node, what: String)(each: Node => R[A]): R[Option[Set[A]]] =
    arr(n, what).flatMap {
      case Vector()  => Right(None)
      case Vector(s) => set(s, what)(each).map(Some(_))
      case xs        => Left(Error.Arity(what, 1, xs.size))
    }

  private def top(root: Node): R[NtcQuery] =
    arr(root, "query").flatMap { xs =>
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
    arr(n, "block query").flatMap { xs =>
      xs.headOption
        .toRight(Error.Empty("block query"))
        .flatMap(uint(_, "block query tag"))
        .flatMap {
          case 0 if xs.size == 2 =>
            arr(xs(1), "query in era").flatMap {
              case Vector(era, q) =>
                uint(era, "era index")
                  .flatMap(e => shelley(q, strings(e)).map(NtcQuery.IfCurrent(e, _)))
              case qs => Left(Error.Arity("query in era", 2, qs.size))
            }
          case 1 if xs.size == 3 =>
            // [1, <Some GetEraStart>, era index]: the query is `[0]` (GetEraStart)
            (arr(xs(1), "anytime query"), uint(xs(2), "era index")).tupled.flatMap {
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
            arr(xs(1), "hard fork query").flatMap {
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

  /** The Shelley block query, reading its byte strings under `policy`. Package-scoped: the caller
    * has already decoded the era, so this is not a query entry point of its own.
    */
  private[ntc] def shelley(n: Node, policy: ByteStrings): R[LedgerQuery] =
    given ByteStrings = policy
    final case class Pending(node: Node, depth: Int)
    Monad[R].tailRecM(Pending(n, 0)) { pending =>
      arr(pending.node, "ledger query").flatMap {
        case Vector(tag, inner) if tag.value == Value.UInt(9) =>
          Right(Left(Pending(inner, pending.depth + 1)))
        case _ =>
          shelleyLeaf(pending.node).map { leaf =>
            Right((0 until pending.depth).foldLeft(leaf)((q, _) => LedgerQuery.GetCBOR(q)))
          }
      }
    }

  private def shelleyLeaf(n: Node)(using ByteStrings): R[LedgerQuery] =
    arr(n, "ledger query").flatMap { xs =>
      xs.headOption
        .toRight(Error.Empty("ledger query"))
        .flatMap(uint(_, "ledger query tag"))
        .flatMap { tag =>
          import LedgerQuery.*
          def arg1[A](f: Node => R[A]): R[A] =
            if xs.size == 2 then f(xs(1)) else Left(Error.Arity(s"ledger query $tag", 2, xs.size))
          def none(q: LedgerQuery): R[LedgerQuery] =
            if xs.size == 1 then Right(q) else Left(Error.Arity(s"ledger query $tag", 1, xs.size))
          tag match
            case 0 => none(GetLedgerTip)
            case 1 => none(GetEpochNo)
            case 2 =>
              arg1(
                set(_, "non-myopic credentials")(c =>
                  arr(c, "non-myopic credential").flatMap {
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
              ).map(s => GetNonMyopicMemberRewards(s.toVector))
            case 3 => none(GetCurrentPParams)
            case 4 => none(GetProposedPParamsUpdates)
            case 5 => none(GetStakeDistribution)
            case 6 => arg1(set(_, "addresses")(bytes(_, "address"))).map(GetUTxOByAddress(_))
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
            case 17 => arg1(set(_, "pools")(keyHash)).map(GetStakePoolParams(_))
            case 18 => none(GetRewardInfoPools)
            case 19 => arg1(maybeSet(_, "pools")(keyHash)).map(GetPoolState(_))
            case 20 => arg1(maybeSet(_, "pools")(keyHash)).map(GetStakeSnapshots(_))
            case 21 => arg1(maybeSet(_, "pools")(keyHash)).map(GetPoolDistr(_))
            case 22 => arg1(set(_, "credentials")(credential)).map(GetStakeDelegDeposits(_))
            case 23 => none(GetConstitution)
            case 24 => none(GetGovState)
            case 25 => arg1(set(_, "credentials")(credential)).map(GetDRepState(_))
            case 26 => arg1(set(_, "dreps")(drep)).map(GetDRepStakeDistr(_))
            case 27 =>
              if xs.size != 4 then Left(Error.Arity("committee members state", 4, xs.size))
              else
                (
                  set(xs(1), "cold credentials")(credential),
                  set(xs(2), "hot credentials")(credential),
                  set(xs(3), "statuses")(uint(_, "member status"))
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
