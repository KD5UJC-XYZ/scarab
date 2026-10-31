// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import cats.syntax.all.*
import java.nio.charset.StandardCharsets
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor as CborCodec, DomainError, Node, Value as V}
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  Committee,
  Constitution,
  Credential as LedgerCredential,
  GovActionId,
  LedgerDecodeError,
  PoolParams,
  PoolRelayError,
  PoolRelays,
  Proposal,
  RewardAccount,
  Vote,
  TxOut
}
import xyz.kd5ujc.scarab.ledger.state as S
import xyz.kd5ujc.scarab.models.runtime.DecodeError
import xyz.kd5ujc.scarab.storage.haskell.state.{Params, UtxoCodecs}
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellStateCodecs
import xyz.kd5ujc.scarab.ledger.state.ProtVer
import xyz.kd5ujc.scarab.storage.scls.SclsRecord.Namespace

/** Typed value codecs for `ledger-f649-conway-v0`.
  *
  * Each method handles the namespace value only; key codecs and framing are separate. In
  * particular, canonical credentials here use the SCLS tags Script=0 and Key=1, which differ from
  * the ledger's transaction credential encoding. StakePools values use the distinct source codecs
  * in `EntitiesStakePools/V0.hs:124-280` and `BasicTypes.hs:210-221,284-301`: current `account_id`
  * is LedgerCBOR, while delegators use canonical Credential; relay and metadata sub-items retain
  * their checked original CBOR bytes. Limits are caller-selected and shared by the outer value and
  * the embedded TxOut, relay and metadata decoders.
  */
object SclsValue:
  enum Entry:
    case Blocks(value: BigInt)
    case Utxo(value: TxOut)
    case Account(value: S.ConwayAccountState)
    case CommitteeEntity(value: S.CommitteeState)
    case DRep(value: S.DRepState)
    case ProposalRoot(value: GovActionId)
    case PParams(value: Params.ConwayPParams)
    case Proposal(value: ProposalValue)
    case StakePool(value: StakePoolValue)
    case VrfKeyCount(value: S.NonZero[S.Word64])
    case GovernanceCommittee(value: Option[Committee])
    case GovernanceConstitution(value: Constitution)

    def namespace: Namespace = this match
      case Blocks(_)                 => Namespace.Blocks
      case Utxo(_)                   => Namespace.Utxo
      case Account(_)                => Namespace.Accounts
      case CommitteeEntity(_)        => Namespace.CommitteeEntities
      case DRep(_)                   => Namespace.DReps
      case ProposalRoot(_)           => Namespace.ProposalRoots
      case PParams(_)                => Namespace.PParams
      case Proposal(_)               => Namespace.Proposals
      case StakePool(_)              => Namespace.StakePools
      case VrfKeyCount(_)            => Namespace.StakePoolVrfHashes
      case GovernanceCommittee(_)    => Namespace.Committee
      case GovernanceConstitution(_) => Namespace.Constitution

  enum Error extends DomainError:
    case Cbor(cause: CborCodec.Error)
    case Runtime(cause: DecodeError)
    case Vocabulary(cause: S.VocabError)
    case WrongShape(namespace: Namespace, expected: String)
    case WrongFieldOrder(namespace: Namespace, expected: Vector[String], found: Vector[String])
    case InvalidTag(namespace: Namespace, found: BigInt)
    case InvalidInteger(namespace: Namespace, found: BigInt)
    case InvalidRatio(namespace: Namespace, numerator: BigInt, denominator: BigInt)
    case NonCanonical(namespace: Namespace)
    case RetainedProcedureMismatch(namespace: Namespace)
    case PoolRelay(cause: PoolRelayError)
    case Ledger(cause: LedgerDecodeError)

  /** The three nullable fields of the canonical `CanonicalStakePool` namespace record. */
  final case class StakePoolValue(
      state: Option[S.StakePoolState],
      retiringEpoch: Option[S.EpochNo],
      futureParams: Option[PoolParams]
  )

  /** A proposal value excludes its GovActionId, which belongs to the namespace key. The raw
    * procedure bytes are retained because canonical-state wraps an OnChain value and writes its
    * original inner CBOR rather than the decoded/re-encoded procedure.
    */
  final case class ProposalValue(
      order: S.Word64,
      drepVotes: Map[S.Credential[S.DRepRole], Vote],
      proposedIn: S.EpochNo,
      expiresAfter: S.EpochNo,
      committeeVotes: Map[S.Credential[S.HotCommittee], Vote],
      stakePoolVotes: Map[S.KeyHash[S.StakePool], Vote],
      proposal: Proposal,
      proposalProcedureRaw: Bytes
  )

  private val Empty = Bytes.empty
  private def node(value: V): Node = Node(value, Empty)
  private def uint(value: BigInt): Node = node(V.UInt(value))
  private def bytes(value: Bytes): Node = node(V.ByteString(value))
  private def text(value: String): Node = node(V.Text(value))
  private val nul: Node = node(V.Null)
  private def array(items: Vector[Node]): Node = node(V.Arr(items))
  private def encoded(value: Node): Either[Error, Bytes] =
    CborCodec.encode(value.value).leftMap(Error.Cbor(_))
  private def shape(namespace: Namespace, expected: String): Error =
    Error.WrongShape(namespace, expected)

  private def asUInt(value: Node, namespace: Namespace): Either[Error, BigInt] = value.value match
    case V.UInt(n) => Right(n)
    case _         => Left(shape(namespace, "unsigned integer"))

  /** `Natural` is encoded through `encodeInteger` by the pinned SCLS CBOR library, including tag-2
    * positive bignums above uint64 (`scls-cbor/.../Encoder.hs:448-449`).
    */
  private def natural(value: Node, namespace: Namespace): Either[Error, BigInt] =
    val maxWord = (BigInt(1) << 64) - 1
    value.value match
      case V.UInt(n) => Right(n)
      case V.Tag(tag, wrapped) if tag == 2 =>
        wrapped.value match
          case V.ByteString(raw) if raw.nonEmpty =>
            val decoded = BigInt(1, raw.toArray)
            if decoded > maxWord then Right(decoded)
            else Left(Error.InvalidInteger(namespace, decoded))
          case _ => Left(shape(namespace, "tag-2 nonempty positive bignum"))
      case _ => Left(shape(namespace, "CBOR natural"))

  private def naturalNode(value: BigInt): Node =
    val maxWord = (BigInt(1) << 64) - 1
    if value <= maxWord then uint(value)
    else
      val signed = value.toByteArray
      val unsigned = if signed.headOption.contains(0.toByte) then signed.drop(1) else signed
      node(V.Tag(2, bytes(Bytes.wrap(unsigned))))

  private def asBytes(value: Node, namespace: Namespace): Either[Error, Bytes] = value.value match
    case V.ByteString(b) => Right(b)
    case _               => Left(shape(namespace, "byte string"))

  private def asArray(value: Node, size: Int, namespace: Namespace): Either[Error, Vector[Node]] =
    value.value match
      case V.Arr(items) if items.size == size => Right(items)
      case _                                  => Left(shape(namespace, s"array($size)"))

  private def compactCoin(value: Node, namespace: Namespace): Either[Error, S.CompactCoin] =
    asUInt(value, namespace).flatMap(n => S.CompactCoin.fromBigInt(n).leftMap(Error.Vocabulary(_)))

  private def compactCoinNode(value: S.CompactCoin): Node = uint(value.toBigInt)

  private val maxWord64 = (BigInt(1) << 64) - 1

  private def canonicalCoin(value: Node, namespace: Namespace): Either[Error, BigInt] =
    asUInt(value, namespace).flatMap(n =>
      Either.cond(n <= maxWord64, n, Error.InvalidInteger(namespace, n))
    )

  private def canonicalCoinNode(value: BigInt, namespace: Namespace): Either[Error, Node] =
    Either.cond(
      value >= 0 && value <= maxWord64,
      uint(value),
      Error.InvalidInteger(namespace, value)
    )

  private def keyHashSet[R <: S.KeyRole](
      value: Node,
      namespace: Namespace
  ): Either[Error, Vector[S.KeyHash[R]]] = value.value match
    case V.Tag(258, wrapped) =>
      asNodes(wrapped, namespace).flatMap(_.traverse(keyHash[R](_, namespace))).flatMap { hashes =>
        Either.cond(
          hashes.distinct.size == hashes.size,
          hashes,
          shape(namespace, "unique tag-258 key-hash set")
        )
      }
    case _ => Left(shape(namespace, "tag-258 key-hash set"))

  private def keyHashSetNode[R <: S.KeyRole](values: Iterable[S.KeyHash[R]]): Either[Error, Node] =
    values.toVector
      .traverse { hash =>
        val encodedHash = bytes(hash.bytes)
        encoded(encodedHash).map(_ -> encodedHash)
      }
      .map(entries =>
        node(V.Tag(258, array(entries.sortWith((a, b) => a._1.compareTo(b._1) < 0).map(_._2))))
      )

  /** `AccountId` is LedgerCBOR: Key=0 and Script=1, the opposite of SCLS Credential. */
  private def ledgerCredential[R <: S.KeyRole](
      value: Node,
      namespace: Namespace
  ): Either[Error, S.Credential[R]] =
    asArray(value, 2, namespace).flatMap { pair =>
      for
        tag <- asUInt(pair(0), namespace)
        hash <- asBytes(pair(1), namespace)
        result <-
          if tag == 0 then
            S.KeyHash
              .fromBytes[R](hash)
              .leftMap(Error.Vocabulary(_))
              .map(S.Credential.KeyHashObj[R](_))
          else if tag == 1 then
            S.ScriptHash
              .fromBytes(hash)
              .leftMap(Error.Vocabulary(_))
              .map(S.Credential.ScriptHashObj[R](_))
          else Left(Error.InvalidTag(namespace, tag))
      yield result
    }

  private def ledgerCredentialNode[R <: S.KeyRole](value: S.Credential[R]): Node = value match
    case S.Credential.KeyHashObj(hash)    => array(Vector(uint(0), bytes(hash.bytes)))
    case S.Credential.ScriptHashObj(hash) => array(Vector(uint(1), bytes(hash.bytes)))

  private def accountAddress(value: Node, namespace: Namespace): Either[Error, RewardAccount] =
    asBytes(value, namespace).flatMap(raw => RewardAccount.decode(raw).leftMap(Error.Ledger(_)))

  private def accountAddressNode(value: RewardAccount, namespace: Namespace): Either[Error, Node] =
    val credential = value.credential
    val hash = credential.hash
    Either.cond(
      value.network >= 0 && value.network <= 15 && hash.size == 28,
      bytes(Bytes.wrap(Array(((credential match
        case LedgerCredential.Key(_)    => 0xe0
        case LedgerCredential.Script(_) => 0xf0
      ) | value.network).toByte) ++ hash.toArray)),
      shape(namespace, "reward account with a network nibble and 28-byte credential hash")
    )

  private def poolMetadata(value: Node, namespace: Namespace): Either[Error, Bytes] =
    val raw = value.original
    value.value match
      case V.Arr(Vector(url, hashNode)) =>
        val checkedUrl = url.value match
          case V.Text(textValue) =>
            Either.cond(
              textValue.getBytes(StandardCharsets.UTF_8).length <= 128,
              (),
              shape(namespace, "PoolMetadata URL up to 128 UTF-8 bytes")
            )
          case _ => Left(shape(namespace, "PoolMetadata [URL, hash]"))
        for
          _ <- checkedUrl
          hash <- asBytes(hashNode, namespace)
          _ <- Either.cond(hash.size == 32, (), shape(namespace, "PoolMetadata 32-byte hash"))
          canonical <- encoded(value)
          _ <- Either.cond(canonical == raw, (), Error.NonCanonical(namespace))
        yield raw
      case _ => Left(shape(namespace, "PoolMetadata [URL, hash]"))

  private def poolMetadataNode(
      value: Bytes,
      namespace: Namespace,
      limits: CborCodec.Limits
  ): Either[Error, Node] =
    CborCodec
      .decode(value, limits)
      .leftMap(Error.Cbor(_))
      .flatMap(decoded => poolMetadata(decoded, namespace).map(_ => decoded))

  private def poolRelays(
      value: Node,
      namespace: Namespace,
      limits: CborCodec.Limits
  ): Either[Error, Bytes] =
    value.value match
      case V.Arr(items) =>
        val raw = if items.isEmpty then Bytes.empty else value.original
        PoolRelays
          .decode(raw, limits)
          .leftMap(Error.PoolRelay(_))
          .flatMap(relays =>
            Either.cond(
              items.isEmpty || PoolRelays.encode(relays) == raw,
              raw,
              Error.NonCanonical(namespace)
            )
          )
      case _ => Left(shape(namespace, "StakePoolRelay sequence"))

  private def poolRelaysNode(
      value: Bytes,
      namespace: Namespace,
      limits: CborCodec.Limits
  ): Either[Error, Node] =
    if value.isEmpty then Right(array(Vector.empty))
    else
      PoolRelays
        .decode(value, limits)
        .leftMap(Error.PoolRelay(_))
        .flatMap(relays =>
          Either.cond(PoolRelays.encode(relays) == value, relays, Error.NonCanonical(namespace))
        )
        .flatMap(_ => CborCodec.decode(value, limits).leftMap(Error.Cbor(_)))

  private def ratio(
      value: Node,
      namespace: Namespace
  ): Either[Error, xyz.kd5ujc.scarab.codec.Rational] =
    value.value match
      case V.Tag(30, wrapped) =>
        asArray(wrapped, 2, namespace).flatMap { pair =>
          (asUInt(pair(0), namespace), asUInt(pair(1), namespace)).tupled.flatMap { (n, d) =>
            unitInterval(n, d, namespace).map(r =>
              xyz.kd5ujc.scarab.codec.Rational(r.numerator, r.denominator)
            )
          }
        }
      case _ => Left(shape(namespace, "tag-30 UnitInterval"))

  private def ratioNode(
      value: xyz.kd5ujc.scarab.codec.Rational,
      namespace: Namespace
  ): Either[Error, Node] =
    unitInterval(value.numerator, value.denominator, namespace)
      .map(r => node(V.Tag(30, array(Vector(uint(r.numerator), uint(r.denominator))))))

  private def poolState(
      value: Node,
      namespace: Namespace,
      limits: CborCodec.Limits
  ): Either[Error, S.StakePoolState] =
    val names = Vector(
      "vrf",
      "cost",
      "margin",
      "owners",
      "pledge",
      "relays",
      "deposit",
      "metadata",
      "account_id",
      "delegators"
    )
    fieldMap(value, names, namespace).flatMap { fields =>
      for
        vrfBytes <- asBytes(fields("vrf"), namespace)
        vrf <- S.VRFVerKeyHash.fromBytes(vrfBytes).leftMap(Error.Vocabulary(_))
        costValue <- canonicalCoin(fields("cost"), namespace)
        cost = S.Coin(costValue)
        pledgeValue <- canonicalCoin(fields("pledge"), namespace)
        pledge = S.Coin(pledgeValue)
        margin <- ratio(fields("margin"), namespace)
        owners <- keyHashSet[S.Staking](fields("owners"), namespace)
        relays <- poolRelays(fields("relays"), namespace, limits)
        deposit <- compactCoin(fields("deposit"), namespace)
        metadata <- optional(fields("metadata"), poolMetadata(_, namespace))
        account <- ledgerCredential[S.Staking](fields("account_id"), namespace)
        delegators <- credentialSet[S.Staking](fields("delegators"), namespace)
      yield
        val ownerOrder = Ordering.fromLessThan[S.KeyHash[S.Staking]]((a, b) =>
          summon[cats.Order[S.KeyHash[S.Staking]]].lt(a, b)
        )
        val credentialOrder = Ordering.fromLessThan[S.Credential[S.Staking]]((a, b) =>
          summon[cats.Order[S.Credential[S.Staking]]].lt(a, b)
        )
        S.StakePoolState(
          vrf,
          pledge,
          cost,
          margin,
          account,
          scala.collection.immutable.SortedSet.from(owners)(using ownerOrder),
          relays,
          metadata,
          deposit,
          scala.collection.immutable.SortedSet.from(delegators)(using credentialOrder)
        )
    }

  private def futurePool(
      value: Node,
      namespace: Namespace,
      limits: CborCodec.Limits
  ): Either[Error, PoolParams] =
    val names = Vector(
      "id",
      "vrf",
      "cost",
      "margin",
      "owners",
      "pledge",
      "relays",
      "metadata",
      "account_address"
    )
    fieldMap(value, names, namespace).flatMap { fields =>
      for
        id <- asBytes(fields("id"), namespace)
        _ <- Either.cond(id.size == 28, (), shape(namespace, "28-byte stake pool id"))
        vrf <- asBytes(fields("vrf"), namespace)
        _ <- Either.cond(vrf.size == 32, (), shape(namespace, "32-byte VRF verification key hash"))
        cost <- canonicalCoin(fields("cost"), namespace)
        margin <- ratio(fields("margin"), namespace)
        owners <- keyHashSet[S.Staking](fields("owners"), namespace)
        pledge <- canonicalCoin(fields("pledge"), namespace)
        relays <- poolRelays(fields("relays"), namespace, limits)
        metadata <- optional(fields("metadata"), poolMetadata(_, namespace))
        address <- accountAddress(fields("account_address"), namespace)
      yield PoolParams(
        id,
        vrf,
        pledge,
        cost,
        margin,
        address,
        owners.map(_.bytes).toSet,
        relays,
        metadata
      )
    }

  private def stakePool(
      value: Node,
      namespace: Namespace,
      limits: CborCodec.Limits
  ): Either[Error, StakePoolValue] =
    fieldMap(
      value,
      Vector("stake_pool_state", "retiring_epoch_no", "future_stake_pool_params"),
      namespace
    )
      .flatMap { fields =>
        for
          state <- optional(fields("stake_pool_state"), poolState(_, namespace, limits))
          retiring <- optional(
            fields("retiring_epoch_no"),
            n =>
              canonicalCoin(n, namespace).flatMap(v =>
                S.EpochNo.fromBigInt(v).leftMap(Error.Vocabulary(_))
              )
          )
          future <- optional(fields("future_stake_pool_params"), futurePool(_, namespace, limits))
        yield StakePoolValue(state, retiring, future)
      }

  private def keyHash[R <: S.KeyRole](
      value: Node,
      namespace: Namespace
  ): Either[Error, S.KeyHash[R]] =
    asBytes(value, namespace).flatMap(b => S.KeyHash.fromBytes[R](b).leftMap(Error.Vocabulary(_)))

  private def credential[R <: S.KeyRole](
      value: Node,
      namespace: Namespace
  ): Either[Error, S.Credential[R]] =
    asArray(value, 2, namespace).flatMap { pair =>
      for
        tag <- asUInt(pair(0), namespace)
        hash <- asBytes(pair(1), namespace)
        result <-
          if tag == 0 then
            S.ScriptHash
              .fromBytes(hash)
              .leftMap(Error.Vocabulary(_))
              .map(S.Credential.ScriptHashObj[R](_))
          else if tag == 1 then
            S.KeyHash
              .fromBytes[R](hash)
              .leftMap(Error.Vocabulary(_))
              .map(S.Credential.KeyHashObj[R](_))
          else Left(Error.InvalidTag(namespace, tag))
      yield result
    }

  private def credentialNode[R <: S.KeyRole](value: S.Credential[R]): Node = value match
    case S.Credential.ScriptHashObj(hash) => array(Vector(uint(0), bytes(hash.bytes)))
    case S.Credential.KeyHashObj(hash)    => array(Vector(uint(1), bytes(hash.bytes)))

  private def ledgerCredentialNode(value: LedgerCredential): Node = value match
    case LedgerCredential.Script(hash) => array(Vector(uint(0), bytes(hash)))
    case LedgerCredential.Key(hash)    => array(Vector(uint(1), bytes(hash)))

  private def fieldMap(
      value: Node,
      names: Vector[String],
      namespace: Namespace
  ): Either[Error, Map[String, Node]] =
    value.value match
      case V.Map(pairs) =>
        val decoded = pairs.traverse { case (key, field) =>
          key.value match
            case V.Text(name) => Right(name -> field)
            case _            => Left(shape(namespace, "text-keyed map"))
        }
        decoded.flatMap { fields =>
          val found = fields.map(_._1)
          Either.cond(
            found == names,
            fields.toMap,
            Error.WrongFieldOrder(namespace, names, found)
          )
        }
      case _ => Left(shape(namespace, "map"))

  private def proposalValue(
      value: Node,
      namespace: Namespace,
      limits: CborCodec.Limits
  ): Either[Error, ProposalValue] =
    asArray(value, 2, namespace).flatMap { outer =>
      fieldMap(
        outer(1),
        Vector(
          "drep_votes",
          "proposed_in",
          "expires_after",
          "committee_votes",
          "stake_pool_votes",
          "proposal_procedure"
        ),
        namespace
      ).flatMap { fields =>
        for
          drepVotes <- credentialMap[S.DRepRole, Vote](fields("drep_votes"), namespace)(
            vote(_, namespace)
          )
          proposed <- epochNo(fields("proposed_in"), namespace)
          expires <- epochNo(fields("expires_after"), namespace)
          committeeVotes <- credentialMap[S.HotCommittee, Vote](
            fields("committee_votes"),
            namespace
          )(vote(_, namespace))
          poolVotes <- valueMap(fields("stake_pool_votes"), namespace)(
            keyHash[S.StakePool](_, namespace),
            vote(_, namespace)
          )
          raw <- asBytes(fields("proposal_procedure"), namespace)
          procedureNode <- CborCodec.decode(raw, limits).leftMap(Error.Cbor(_))
          proposal <- HaskellStateCodecs
            .proposal(using
              ProtVer(S.Word64.fromUnsigned(9L), S.Word64.fromUnsigned(0L))
            )
            .decode(procedureNode)
            .leftMap(Error.Runtime(_))
          order <- word64(outer(0), namespace)
        yield ProposalValue(
          order,
          drepVotes,
          proposed,
          expires,
          committeeVotes,
          poolVotes,
          proposal,
          raw
        )
      }
    }

  private def word64(value: Node, namespace: Namespace): Either[Error, S.Word64] =
    asUInt(value, namespace).flatMap(n =>
      Either.cond(
        n <= maxWord64,
        S.Word64.fromUnsigned(n.toLong),
        Error.InvalidInteger(namespace, n)
      )
    )

  private def epochNo(value: Node, namespace: Namespace): Either[Error, S.EpochNo] =
    asUInt(value, namespace)
      .flatMap(n => S.EpochNo.fromBigInt(n).leftMap(Error.Vocabulary(_)))

  private def vote(value: Node, namespace: Namespace): Either[Error, Vote] =
    asUInt(value, namespace).flatMap(n =>
      if n == 0 then Right(Vote.No)
      else if n == 1 then Right(Vote.Yes)
      else if n == 2 then Right(Vote.Abstain)
      else Left(Error.InvalidTag(namespace, n))
    )

  private def valueMap[K, A](value: Node, namespace: Namespace)(
      readKey: Node => Either[Error, K],
      readValue: Node => Either[Error, A]
  ): Either[Error, Map[K, A]] = value.value match
    case V.Map(pairs) =>
      pairs
        .traverse { case (k, v) => (readKey(k), readValue(v)).tupled }
        .flatMap(entries =>
          Either.cond(
            entries.map(_._1).distinct.size == entries.size,
            entries.toMap,
            shape(namespace, "unique map keys")
          )
        )
    case _ => Left(shape(namespace, "map"))

  private def textMap(fields: Vector[(String, Node)]): Either[Error, Node] =
    fields
      .traverse { case (name, value) => encoded(text(name)).map(_ -> (text(name) -> value)) }
      .map(entries => node(V.Map(entries.sortWith((a, b) => a._1.compareTo(b._1) < 0).map(_._2))))

  private def valueMapNode[K, A](values: Map[K, A])(
      writeKey: K => Either[Error, Node],
      writeValue: A => Either[Error, Node]
  ): Either[Error, Node] =
    values.toVector
      .traverse { case (key, value) =>
        writeKey(key).flatMap { keyNode =>
          (encoded(keyNode), writeValue(value)).mapN((keyBytes, v) => keyBytes -> (keyNode -> v))
        }
      }
      .map(entries => node(V.Map(entries.sortWith((a, b) => a._1.compareTo(b._1) < 0).map(_._2))))

  private def voteNode(value: Vote): Node = uint(value.ordinal)

  private def proposalValueNode(
      value: ProposalValue,
      limits: CborCodec.Limits
  ): Either[Error, Node] =
    val namespace = Namespace.Proposals
    val protocolVersion = ProtVer(S.Word64.fromUnsigned(9L), S.Word64.fromUnsigned(0L))
    val procedureCodec = HaskellStateCodecs.proposal(using protocolVersion)
    for
      procedureNode <- CborCodec.decode(value.proposalProcedureRaw, limits).leftMap(Error.Cbor(_))
      checked <- procedureCodec.decode(procedureNode).leftMap(Error.Runtime(_))
      _ <- Either.cond(checked == value.proposal, (), Error.RetainedProcedureMismatch(namespace))
      drep <- credentialMapNode(value.drepVotes.toVector)(v => Right(voteNode(v)))
      committee <- credentialMapNode(value.committeeVotes.toVector)(v => Right(voteNode(v)))
      pools <- valueMapNode(value.stakePoolVotes)(
        h => Right(bytes(h.bytes)),
        v => Right(voteNode(v))
      )
      fields <- textMap(
        Vector(
          "drep_votes" -> drep,
          "proposed_in" -> uint(S.EpochNo.toBigInt(value.proposedIn)),
          "expires_after" -> uint(S.EpochNo.toBigInt(value.expiresAfter)),
          "committee_votes" -> committee,
          "stake_pool_votes" -> pools,
          "proposal_procedure" -> bytes(value.proposalProcedureRaw)
        )
      )
    yield array(Vector(uint(S.Word64.toBigInt(value.order)), fields))

  private def credentialMap[R <: S.KeyRole, A](value: Node, namespace: Namespace)(
      read: Node => Either[Error, A]
  ): Either[Error, Map[S.Credential[R], A]] = value.value match
    case V.Map(pairs) =>
      pairs
        .traverse { case (key, field) => (credential[R](key, namespace), read(field)).tupled }
        .flatMap { entries =>
          Either.cond(
            entries.map(_._1).distinct.size == entries.size,
            entries.toMap,
            shape(namespace, "unique credential map")
          )
        }
    case _ => Left(shape(namespace, "credential-keyed map"))

  private def credentialSet[R <: S.KeyRole](
      value: Node,
      namespace: Namespace
  ): Either[Error, Vector[S.Credential[R]]] =
    value.value match
      case V.Tag(258, wrapped) =>
        asNodes(wrapped, namespace).flatMap { nodes =>
          nodes.traverse(credential[R](_, namespace)).flatMap { values =>
            Either.cond(
              values.distinct.size == values.size,
              values,
              shape(namespace, "unique credential set")
            )
          }
        }
      case _ => Left(shape(namespace, "tag-258 credential set"))

  private def asNodes(value: Node, namespace: Namespace): Either[Error, Vector[Node]] =
    value.value match
      case V.Arr(items) => Right(items)
      case _            => Left(shape(namespace, "array"))

  private def credentialMapNode[R <: S.KeyRole, A](
      values: Iterable[(S.Credential[R], A)]
  )(write: A => Either[Error, Node]): Either[Error, Node] =
    values.toVector
      .traverse { case (key, value) =>
        val encodedKey = credentialNode(key)
        (encoded(encodedKey), write(value))
          .mapN((keyBytes, field) => keyBytes -> (encodedKey -> field))
      }
      .map(entries => node(V.Map(entries.sortWith((a, b) => a._1.compareTo(b._1) < 0).map(_._2))))

  private def credentialSetNode[R <: S.KeyRole](
      values: Vector[S.Credential[R]]
  ): Either[Error, Node] =
    values
      .traverse { credential =>
        val encodedCredential = credentialNode(credential)
        encoded(encodedCredential).map(_ -> encodedCredential)
      }
      .map(entries =>
        node(V.Tag(258, array(entries.sortWith((a, b) => a._1.compareTo(b._1) < 0).map(_._2))))
      )

  private def validAnchorUrl(url: String, namespace: Namespace): Either[Error, String] =
    Either.cond(
      url.getBytes(StandardCharsets.UTF_8).length <= 128,
      url,
      shape(namespace, "anchor URL up to 128 UTF-8 bytes")
    )

  private def anchor(value: Node, namespace: Namespace): Either[Error, Anchor] =
    asArray(value, 2, namespace).flatMap { pair =>
      pair(0).value match
        case V.Text(url) =>
          (validAnchorUrl(url, namespace), asBytes(pair(1), namespace)).tupled.flatMap {
            (checkedUrl, hash) =>
              Either.cond(
                hash.size == 32,
                Anchor(checkedUrl, hash),
                shape(namespace, "anchor with 32-byte hash")
              )
          }
        case _ => Left(shape(namespace, "anchor [text, bytes]"))
    }

  private def encodeAnchor(value: Anchor, namespace: Namespace): Either[Error, Node] =
    (
      validAnchorUrl(value.url, namespace),
      Either.cond(value.hash.size == 32, value.hash, shape(namespace, "anchor with 32-byte hash"))
    )
      .mapN((url, hash) => array(Vector(text(url), bytes(hash))))

  private def optional[A](value: Node, read: Node => Either[Error, A]): Either[Error, Option[A]] =
    value.value match
      case V.Null => Right(None)
      case _      => read(value).map(Some(_))

  private def optionalNode[A](value: Option[A], write: A => Node): Node = value.fold(nul)(write)

  private def decodeEntry(
      namespace: Namespace,
      value: Node,
      limits: CborCodec.Limits
  ): Either[Error, Entry] = namespace match
    case Namespace.Blocks =>
      natural(value, namespace).map(Entry.Blocks(_))
    case Namespace.Utxo =>
      asBytes(value, namespace).flatMap { inner =>
        CborCodec.decode(inner, limits).leftMap(Error.Cbor(_)).flatMap { node =>
          UtxoCodecs.txOut.decode(node).leftMap(Error.Runtime(_)).map(Entry.Utxo(_))
        }
      }
    case Namespace.Accounts =>
      fieldMap(
        value,
        Vector("balance", "deposit", "drep_delegation", "stake_pool_delegation"),
        namespace
      ).flatMap { fields =>
        for
          balance <- compactCoin(fields("balance"), namespace)
          deposit <- compactCoin(fields("deposit"), namespace)
          drep <- optional(fields("drep_delegation"), decodeDRep(_, namespace))
          pool <- optional(fields("stake_pool_delegation"), keyHash[S.StakePool](_, namespace))
        yield Entry.Account(S.ConwayAccountState(balance, deposit, pool, drep))
      }
    case Namespace.CommitteeEntities =>
      credentialMap[S.ColdCommittee, S.CommitteeAuthorization](value, namespace)(
        decodeAuthorization(_, namespace)
      )
        .map(fields =>
          val ordering = Ordering.fromLessThan[S.Credential[S.ColdCommittee]]((a, b) =>
            summon[cats.Order[S.Credential[S.ColdCommittee]]].lt(a, b)
          )
          Entry.CommitteeEntity(
            S.CommitteeState(scala.collection.immutable.SortedMap.from(fields)(using ordering))
          )
        )
    case Namespace.DReps =>
      fieldMap(value, Vector("anchor", "expiry", "deposit", "delegations"), namespace).flatMap {
        fields =>
          for
            drepAnchor <- optional(fields("anchor"), anchor(_, namespace))
            expiryValue <- asUInt(fields("expiry"), namespace)
            expiry <- S.EpochNo.fromBigInt(expiryValue).leftMap(Error.Vocabulary(_))
            deposit <- compactCoin(fields("deposit"), namespace)
            delegators <- credentialSet[S.Staking](fields("delegations"), namespace)
          yield Entry.DRep(
            S.DRepState(
              expiry,
              drepAnchor,
              deposit,
              scala.collection.immutable.SortedSet.from(delegators)(using
                Ordering.fromLessThan[S.Credential[S.Staking]]((a, b) =>
                  summon[cats.Order[S.Credential[S.Staking]]].lt(a, b)
                )
              )
            )
          )
      }
    case Namespace.ProposalRoots =>
      asArray(value, 2, namespace).flatMap { pair =>
        for
          txId <- asBytes(pair(0), namespace).flatMap(hash =>
            Either.cond(hash.size == 32, hash, shape(namespace, "32-byte transaction id"))
          )
          index <- asUInt(pair(1), namespace)
          _ <- Either.cond(index <= 65535, (), Error.InvalidInteger(namespace, index))
        yield Entry.ProposalRoot(GovActionId(txId, index))
      }
    case Namespace.PParams =>
      Params.conwayPParamsCodec.decode(value).leftMap(Error.Runtime(_)).map(Entry.PParams(_))
    case Namespace.Proposals =>
      proposalValue(value, namespace, limits).map(Entry.Proposal(_))
    case Namespace.StakePools =>
      stakePool(value, namespace, limits).map(Entry.StakePool(_))
    case Namespace.StakePoolVrfHashes =>
      asUInt(value, namespace).flatMap { n =>
        S.Word64
          .fromBigInt(S.VocabError.Kind.NonZero, n)
          .leftMap(Error.Vocabulary(_))
          .flatMap(word => S.NonZero.from(word).leftMap(Error.Vocabulary(_)))
          .map(Entry.VrfKeyCount(_))
      }
    case Namespace.Committee =>
      optional(value, decodeCommittee(_, namespace)).map(Entry.GovernanceCommittee(_))
    case Namespace.Constitution =>
      asArray(value, 2, namespace).flatMap { pair =>
        (
          anchor(pair(0), namespace),
          optional(
            pair(1),
            n =>
              asBytes(n, namespace).flatMap { hash =>
                Either.cond(hash.size == 28, hash, shape(namespace, "28-byte script hash"))
              }
          )
        ).mapN { (a, script) =>
          Entry.GovernanceConstitution(Constitution(a, script))
        }
      }

  /** Decode and verify original-byte canonicality using one consistent caller resource profile. */
  def decode(
      namespace: Namespace,
      raw: Bytes,
      limits: CborCodec.Limits = CborCodec.Limits()
  ): Either[Error, Entry] =
    decodeNode(namespace, raw, limits).flatMap { entry =>
      encode(entry, limits).flatMap(bytes =>
        Either.cond(bytes == raw, entry, Error.NonCanonical(namespace))
      )
    }

  private def decodeNode(
      namespace: Namespace,
      raw: Bytes,
      limits: CborCodec.Limits
  ): Either[Error, Entry] =
    CborCodec.decode(raw, limits).leftMap(Error.Cbor(_)).flatMap(decodeEntry(namespace, _, limits))

  /** Encode using the caller's output and nested original-byte validation limits. */
  def encode(entry: Entry, limits: CborCodec.Limits = CborCodec.Limits()): Either[Error, Bytes] =
    encodeNode(entry, limits)
      .flatMap(node => CborCodec.encode(node.value, limits).leftMap(Error.Cbor(_)))

  private def poolStateNode(
      value: S.StakePoolState,
      limits: CborCodec.Limits
  ): Either[Error, Node] =
    val namespace = Namespace.StakePools
    for
      cost <- canonicalCoinNode(S.Coin.value(value.cost), namespace)
      pledge <- canonicalCoinNode(S.Coin.value(value.pledge), namespace)
      margin <- ratioNode(value.margin, namespace)
      owners <- keyHashSetNode(value.owners)
      relays <- poolRelaysNode(value.relays, namespace, limits)
      metadata <- value.metadata.traverse(poolMetadataNode(_, namespace, limits))
      delegators <- credentialSetNode(value.delegators.toVector)
      fields <- textMap(
        Vector(
          "vrf" -> bytes(value.vrf.bytes),
          "cost" -> cost,
          "margin" -> margin,
          "owners" -> owners,
          "pledge" -> pledge,
          "relays" -> relays,
          "deposit" -> compactCoinNode(value.deposit),
          "metadata" -> optionalNode(metadata, identity),
          "account_id" -> ledgerCredentialNode(value.accountId),
          "delegators" -> delegators
        )
      )
    yield fields

  private def futurePoolNode(value: PoolParams, limits: CborCodec.Limits): Either[Error, Node] =
    val namespace = Namespace.StakePools
    for
      _ <- Either.cond(value.operator.size == 28, (), shape(namespace, "28-byte stake pool id"))
      _ <- Either.cond(
        value.vrfKeyHash.size == 32,
        (),
        shape(namespace, "32-byte VRF verification key hash")
      )
      cost <- canonicalCoinNode(value.cost, namespace)
      pledge <- canonicalCoinNode(value.pledge, namespace)
      margin <- ratioNode(value.margin, namespace)
      owners <- value.owners.toVector
        .traverse(owner => keyHash[S.Staking](bytes(owner), namespace))
        .flatMap(keyHashSetNode(_))
      relays <- poolRelaysNode(value.relays, namespace, limits)
      metadata <- value.metadata.traverse(poolMetadataNode(_, namespace, limits))
      address <- accountAddressNode(value.rewardAccount, namespace)
      fields <- textMap(
        Vector(
          "id" -> bytes(value.operator),
          "vrf" -> bytes(value.vrfKeyHash),
          "cost" -> cost,
          "margin" -> margin,
          "owners" -> owners,
          "pledge" -> pledge,
          "relays" -> relays,
          "metadata" -> optionalNode(metadata, identity),
          "account_address" -> address
        )
      )
    yield fields

  private def stakePoolNode(value: StakePoolValue, limits: CborCodec.Limits): Either[Error, Node] =
    for
      state <- value.state.traverse(poolStateNode(_, limits))
      future <- value.futureParams.traverse(futurePoolNode(_, limits))
      fields <- textMap(
        Vector(
          "stake_pool_state" -> optionalNode(state, identity),
          "retiring_epoch_no" -> optionalNode(
            value.retiringEpoch.map(e => uint(S.EpochNo.toBigInt(e))),
            identity
          ),
          "future_stake_pool_params" -> optionalNode(future, identity)
        )
      )
    yield fields

  private def encodeNode(entry: Entry, limits: CborCodec.Limits): Either[Error, Node] = entry match
    case Entry.Blocks(value) =>
      Either.cond(value >= 0, naturalNode(value), Error.InvalidInteger(Namespace.Blocks, value))
    case Entry.Utxo(value) =>
      for
        rawNode <- CborCodec.decode(value.raw, limits).leftMap(Error.Cbor(_))
        decoded <- UtxoCodecs.txOut.decode(rawNode).leftMap(Error.Runtime(_))
        _ <- Either.cond(
          decoded == value,
          (),
          shape(Namespace.Utxo, "TxOut matching preserved raw bytes")
        )
      yield bytes(value.raw)
    case Entry.Account(value) =>
      textMap(
        Vector(
          "balance" -> compactCoinNode(value.balance),
          "deposit" -> compactCoinNode(value.deposit),
          "drep_delegation" -> optionalNode(value.drep, encodeDRep),
          "stake_pool_delegation" -> optionalNode(value.pool, h => bytes(h.bytes))
        )
      )
    case Entry.CommitteeEntity(value) =>
      credentialMapNode(value.creds.toVector)(encodeAuthorization)
    case Entry.DRep(value) =>
      for
        anchorNode <- value.anchor.traverse(encodeAnchor(_, Namespace.DReps))
        delegators <- credentialSetNode(value.delegators.toVector)
        fields <- textMap(
          Vector(
            "anchor" -> optionalNode(anchorNode, identity),
            "expiry" -> uint(S.EpochNo.toBigInt(value.expiry)),
            "deposit" -> compactCoinNode(value.deposit),
            "delegations" -> delegators
          )
        )
      yield fields
    case Entry.ProposalRoot(value) =>
      for
        _ <- Either.cond(
          value.txId.size == 32,
          (),
          shape(Namespace.ProposalRoots, "32-byte transaction id")
        )
        _ <- Either.cond(
          value.index >= 0 && value.index <= 65535,
          (),
          Error.InvalidInteger(Namespace.ProposalRoots, value.index)
        )
      yield array(Vector(bytes(value.txId), uint(value.index)))
    case Entry.PParams(value) =>
      CborCodec
        .decode(Params.conwayPParamsCodec.encodeUnchecked(value), limits)
        .leftMap(Error.Cbor(_))
    case Entry.Proposal(value)  => proposalValueNode(value, limits)
    case Entry.StakePool(value) => stakePoolNode(value, limits)
    case Entry.VrfKeyCount(value) =>
      Right(uint(S.Word64.toBigInt(value.value)))
    case Entry.GovernanceCommittee(value) =>
      value.traverse(encodeCommittee).map(v => optionalNode(v, identity))
    case Entry.GovernanceConstitution(value) =>
      for
        encodedAnchor <- encodeAnchor(value.anchor, Namespace.Constitution)
        encodedScript <- value.script.traverse(hash =>
          Either.cond(
            hash.size == 28,
            bytes(hash),
            shape(Namespace.Constitution, "28-byte script hash")
          )
        )
      yield array(Vector(encodedAnchor, optionalNode(encodedScript, identity)))

  private def decodeDRep(value: Node, namespace: Namespace): Either[Error, S.DRep] =
    value.value match
      case V.Arr(Vector(tag, hash)) =>
        for
          number <- asUInt(tag, namespace)
          raw <- asBytes(hash, namespace)
          result <-
            // Account DRep uses ledger CBOR (KEY=0, SCRIPT=1), unlike the canonical Credential
            // instance above (SCRIPT=0, KEY=1); see EntitiesAccounts/V0.hs:118-124.
            if number == 0 then
              S.KeyHash
                .fromBytes[S.DRepRole](raw)
                .leftMap(Error.Vocabulary(_))
                .map(S.DRep.DRepKeyHash(_))
            else if number == 1 then
              S.ScriptHash.fromBytes(raw).leftMap(Error.Vocabulary(_)).map(S.DRep.DRepScriptHash(_))
            else Left(Error.InvalidTag(namespace, number))
        yield result
      case V.Arr(Vector(tag)) =>
        asUInt(tag, namespace).flatMap { n =>
          if n == 2 then Right(S.DRep.DRepAlwaysAbstain)
          else if n == 3 then Right(S.DRep.DRepAlwaysNoConfidence)
          else Left(Error.InvalidTag(namespace, n))
        }
      case _ => Left(shape(namespace, "DRep constructor array"))

  private def encodeDRep(value: S.DRep): Node = value match
    case S.DRep.DRepKeyHash(hash)      => array(Vector(uint(0), bytes(hash.bytes)))
    case S.DRep.DRepScriptHash(hash)   => array(Vector(uint(1), bytes(hash.bytes)))
    case S.DRep.DRepAlwaysAbstain      => array(Vector(uint(2)))
    case S.DRep.DRepAlwaysNoConfidence => array(Vector(uint(3)))

  private def decodeAuthorization(
      value: Node,
      namespace: Namespace
  ): Either[Error, S.CommitteeAuthorization] =
    asArray(value, 2, namespace).flatMap { pair =>
      asUInt(pair(0), namespace).flatMap { tag =>
        if tag == 0 then
          credential[S.HotCommittee](pair(1), namespace)
            .map(S.CommitteeAuthorization.CommitteeHotCredential(_))
        else if tag == 1 then
          optional(pair(1), anchor(_, namespace))
            .map(S.CommitteeAuthorization.CommitteeMemberResigned(_))
        else Left(Error.InvalidTag(namespace, tag))
      }
    }

  private def encodeAuthorization(value: S.CommitteeAuthorization): Either[Error, Node] =
    value match
      case S.CommitteeAuthorization.CommitteeHotCredential(credential) =>
        Right(array(Vector(uint(0), credentialNode(credential))))
      case S.CommitteeAuthorization.CommitteeMemberResigned(anchor) =>
        anchor
          .traverse(encodeAnchor(_, Namespace.CommitteeEntities))
          .map(encodedAnchor => array(Vector(uint(1), optionalNode(encodedAnchor, identity))))

  private def decodeCommittee(value: Node, namespace: Namespace): Either[Error, Committee] =
    asArray(value, 2, namespace).flatMap { pair =>
      for
        members <- credentialMap[S.ColdCommittee, BigInt](pair(0), namespace)(asUInt(_, namespace))
        threshold <- pair(1).value match
          case V.Tag(tag, wrapped) if tag == 30 =>
            asArray(wrapped, 2, namespace).flatMap { xs =>
              (asUInt(xs(0), namespace), asUInt(xs(1), namespace)).tupled.flatMap {
                (numerator, denominator) =>
                  unitInterval(numerator, denominator, namespace)
              }
            }
          case _ => Left(shape(namespace, "tag 30 rational"))
      yield
        val ledgerMembers = members.map { case (credential, expiry) =>
          val converted = credential match
            case S.Credential.ScriptHashObj(hash) => LedgerCredential.Script(hash.bytes)
            case S.Credential.KeyHashObj(hash)    => LedgerCredential.Key(hash.bytes)
          converted -> expiry
        }
        Committee(ledgerMembers, threshold)
    }

  private def encodeCommittee(value: Committee): Either[Error, Node] =
    val ratio = value.threshold
    val ratioChecked = unitInterval(ratio.numerator, ratio.denominator, Namespace.Committee)
    val members = value.members.toVector.traverse { case (credential, expiry) =>
      val hash = credential match
        case LedgerCredential.Script(value) => value
        case LedgerCredential.Key(value)    => value
      val valid = Either.cond(
        hash.size == 28 && expiry >= 0 && expiry <= ((BigInt(1) << 64) - 1),
        (),
        shape(Namespace.Committee, "28-byte credential hash and unsigned 64-bit expiry")
      )
      valid *> encoded(ledgerCredentialNode(credential)).map(key =>
        key -> (ledgerCredentialNode(credential) -> uint(expiry))
      )
    }
    (ratioChecked *> members)
      .map { fields =>
        val map = node(V.Map(fields.sortWith((a, b) => a._1.compareTo(b._1) < 0).map(_._2)))
        array(
          Vector(
            map,
            node(V.Tag(30, array(Vector(uint(ratio.numerator), uint(ratio.denominator)))))
          )
        )
      }

  private def unitInterval(
      numerator: BigInt,
      denominator: BigInt,
      namespace: Namespace
  ): Either[Error, xyz.kd5ujc.scarab.codec.Rational] =
    val maxWord = (BigInt(1) << 64) - 1
    Either.cond(
      denominator > 0 && numerator >= 0 && numerator <= denominator && numerator <= maxWord && denominator <= maxWord,
      xyz.kd5ujc.scarab.codec.Rational(numerator, denominator),
      Error.InvalidRatio(namespace, numerator, denominator)
    )
