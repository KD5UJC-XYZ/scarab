// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{Credential, DRep, GovActionId, TxIn}

/** A ledger (Shelley-based era) query as `Ouroboros.Consensus.Shelley.Ledger.Query` numbers them.
  * `Unsupported(tag)` is a query this node understands the framing of but does not answer.
  */
enum LedgerQuery:
  case GetLedgerTip
  case GetEpochNo
  case GetNonMyopicMemberRewards(credentials: Vector[Either[BigInt, Credential]])
  case GetCurrentPParams
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
  case GetLedgerPeerSnapshot(kind: Int)
  case QueryStakePoolDefaultVote(pool: Bytes)
  case GetPoolDistr2(pools: Option[Set[Bytes]])
  case GetStakeDistribution2
  case GetMaxMajorProtocolVersion
  case GetDRepDelegations(dreps: Set[DRep])
  case Unsupported(tag: Int)

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
  private type R[A] = Either[String, A]
  private val limits = Cbor.Limits(
    maxInputBytes = 2000000,
    maxItems = 200000,
    maxStringBytes = 1000000
  )

  def decode(raw: Bytes): R[NtcQuery] = Cbor.decode(raw, limits).flatMap(top)

  private def arr(n: Node, what: String): R[Vector[Node]] = n.value match
    case Value.Arr(xs) => Right(xs)
    case _             => Left(s"$what: expected array")

  private def uint(n: Node, what: String): R[Int] = n.value match
    case Value.UInt(v) if v.isValidInt => Right(v.toInt)
    case _                             => Left(s"$what: expected small unsigned integer")

  private def bytes(n: Node, what: String): R[Bytes] = n.value match
    case Value.ByteString(b) => Right(b)
    case _                   => Left(s"$what: expected byte string")

  /** Elements of a set: an array, optionally under tag 258. */
  private def elements(n: Node, what: String): R[Vector[Node]] = n.value match
    case Value.Tag(t, inner) if t == 258 => arr(inner, what)
    case _                               => arr(n, what)

  private def set[A](n: Node, what: String)(each: Node => R[A]): R[Set[A]] =
    elements(n, what).flatMap(_.traverse(each)).map(_.toSet)

  private def credential(n: Node): R[Credential] =
    arr(n, "credential").flatMap {
      case Vector(t, h) =>
        (uint(t, "credential kind"), bytes(h, "credential hash")).tupled.flatMap {
          case (0, hash) if hash.size == 28 => Right(Credential.Key(hash))
          case (1, hash) if hash.size == 28 => Right(Credential.Script(hash))
          case _                            => Left("credential: bad kind or hash")
        }
      case _ => Left("credential: expected two elements")
    }

  private def keyHash(n: Node): R[Bytes] =
    bytes(n, "key hash").filterOrElse(_.size == 28, "key hash: expected 28 bytes")

  private def txIn(n: Node): R[TxIn] = TxIn.decode(n)

  private def drep(n: Node): R[DRep] =
    arr(n, "drep").flatMap { xs =>
      xs.headOption.toRight("drep: empty").flatMap(uint(_, "drep kind")).flatMap {
        case 0 if xs.size == 2 => keyHash(xs(1)).map(h => DRep.Registered(Credential.Key(h)))
        case 1 if xs.size == 2 => keyHash(xs(1)).map(h => DRep.Registered(Credential.Script(h)))
        case 2 if xs.size == 1 => Right(DRep.AlwaysAbstain)
        case 3 if xs.size == 1 => Right(DRep.AlwaysNoConfidence)
        case _                 => Left("drep: bad kind or arity")
      }
    }

  private def govActionId(n: Node): R[GovActionId] =
    arr(n, "gov action id").flatMap {
      case Vector(i, ix) =>
        (bytes(i, "gov action tx id"), uint(ix, "gov action index")).mapN((t, k) =>
          GovActionId(t, BigInt(k))
        )
      case _ => Left("gov action id: expected two elements")
    }

  /** `Maybe (Set a)`: `[]` for nothing, `[set]` for just. */
  private def maybeSet[A](n: Node, what: String)(each: Node => R[A]): R[Option[Set[A]]] =
    arr(n, what).flatMap {
      case Vector()  => Right(None)
      case Vector(s) => set(s, what)(each).map(Some(_))
      case _         => Left(s"$what: expected a maybe")
    }

  private def top(root: Node): R[NtcQuery] =
    arr(root, "query").flatMap { xs =>
      xs.headOption.toRight("query: empty").flatMap(uint(_, "query tag")).flatMap {
        case 0 if xs.size == 2 => hardFork(xs(1))
        case 1 if xs.size == 1 => Right(NtcQuery.SystemStart)
        case 2 if xs.size == 1 => Right(NtcQuery.ChainBlockNo)
        case 3 if xs.size == 1 => Right(NtcQuery.ChainPoint)
        case 4 if xs.size == 1 => Right(NtcQuery.DebugLedgerConfig)
        case t                 => Left(s"query: unknown tag $t or arity ${xs.size}")
      }
    }

  private def hardFork(n: Node): R[NtcQuery] =
    arr(n, "block query").flatMap { xs =>
      xs.headOption.toRight("block query: empty").flatMap(uint(_, "block query tag")).flatMap {
        case 0 if xs.size == 2 =>
          arr(xs(1), "query in era").flatMap {
            case Vector(era, q) =>
              (uint(era, "era index"), shelley(q)).mapN(NtcQuery.IfCurrent(_, _))
            case _ => Left("query in era: expected [era, query]")
          }
        case 1 if xs.size == 3 =>
          // [1, <Some GetEraStart>, era index]: the query is `[0]` (GetEraStart)
          (arr(xs(1), "anytime query"), uint(xs(2), "era index")).tupled.flatMap {
            case (q, era) if q.size >= 1 => Right(NtcQuery.EraStart(era))
            case _                       => Left("anytime query: empty")
          }
        case 2 if xs.size == 2 =>
          arr(xs(1), "hard fork query").flatMap { qs =>
            qs.headOption
              .toRight("hard fork query: empty")
              .flatMap(uint(_, "hard fork tag"))
              .flatMap {
                case 0 => Right(NtcQuery.HardFork(HardForkQuery.GetInterpreter))
                case 1 => Right(NtcQuery.HardFork(HardForkQuery.GetCurrentEra))
                case t => Left(s"hard fork query: unknown tag $t")
              }
          }
        case t => Left(s"block query: unknown tag $t")
      }
    }

  /** The Shelley block query (`decodeShelleyQuery`). */
  def shelley(n: Node): R[LedgerQuery] =
    arr(n, "ledger query").flatMap { xs =>
      xs.headOption.toRight("ledger query: empty").flatMap(uint(_, "ledger query tag")).flatMap {
        tag =>
          import LedgerQuery.*
          def arg1[A](f: Node => R[A]): R[A] =
            if xs.size == 2 then f(xs(1)) else Left(s"ledger query $tag: expected one argument")
          def none(q: LedgerQuery): R[LedgerQuery] =
            if xs.size == 1 then Right(q) else Left(s"ledger query $tag: expected no argument")
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
                            case _                => Left("non-myopic coin")
                        case 1 => credential(v).map(Right(_))
                        case _ => Left("non-myopic tag")
                      }
                    case _ => Left("non-myopic credential")
                  }
                )
              ).map(s => GetNonMyopicMemberRewards(s.toVector))
            case 3 => none(GetCurrentPParams)
            case 6 => arg1(set(_, "addresses")(bytes(_, "address"))).map(GetUTxOByAddress(_))
            case 7 => none(GetUTxOWhole)
            case 8 => none(DebugEpochState)
            case 9 => arg1(shelley).map(GetCBOR(_))
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
            case 22 => arg1(set(_, "credentials")(credential)).map(GetStakeDelegDeposits(_))
            case 23 => none(GetConstitution)
            case 24 => none(GetGovState)
            case 25 => arg1(set(_, "credentials")(credential)).map(GetDRepState(_))
            case 26 => arg1(set(_, "dreps")(drep)).map(GetDRepStakeDistr(_))
            case 27 =>
              if xs.size != 4 then Left("committee members state: expected three arguments")
              else
                (
                  set(xs(1), "cold credentials")(credential),
                  set(xs(2), "hot credentials")(credential),
                  set(xs(3), "statuses")(uint(_, "member status"))
                ).mapN(GetCommitteeMembersState(_, _, _))
            case 28    => arg1(set(_, "credentials")(credential)).map(GetFilteredVoteDelegatees(_))
            case 29    => none(GetAccountState)
            case 30    => arg1(set(_, "pools")(keyHash)).map(GetSPOStakeDistr(_))
            case 31    => arg1(set(_, "ids")(govActionId)).map(GetProposals(_))
            case 32    => none(GetRatifyState)
            case 33    => none(GetFuturePParams)
            case 34    => arg1(uint(_, "peer kind")).map(GetLedgerPeerSnapshot(_))
            case 35    => arg1(keyHash).map(QueryStakePoolDefaultVote(_))
            case 36    => arg1(maybeSet(_, "pools")(keyHash)).map(GetPoolDistr2(_))
            case 37    => none(GetStakeDistribution2)
            case 38    => none(GetMaxMajorProtocolVersion)
            case 39    => arg1(set(_, "dreps")(drep)).map(GetDRepDelegations(_))
            case other => Left(s"ledger query: unknown tag $other")
      }
    }
