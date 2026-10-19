// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as V}
import xyz.kd5ujc.scarab.ledger.{
  Credential as LedgerCredential,
  DRep as LedgerDRep,
  PoolRelay,
  PoolRelays
}
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellStateCodecs.given
import xyz.kd5ujc.scarab.storage.haskell.state.UtxoCodecs
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.storage.scls.SclsRecord.Namespace
import xyz.kd5ujc.scarab.ledger.state as S

class SclsValueSuite extends FunSuite:
  private val fixture = Path.of("fixtures/scls/conway-4593/entries.tsv")
  private def hex(value: String): Bytes = Bytes.fromHex(value).fold(e => fail(e.message), identity)
  private def namespace(name: String): Namespace =
    Namespace.values.find(_.name == name).getOrElse(fail(s"unknown fixture namespace $name"))

  private val fixtureEntries = Files
    .readString(fixture)
    .linesIterator
    .map { case line =>
      line.split("\t", -1) match
        case Array(ns, key, value) => (namespace(ns), hex(key), hex(value))
        case _                     => fail(s"invalid SCLS entry line: $line")
    }
    .toVector
  private val canonicalValueVectors =
    Path.of("fixtures/scls/namespace-value-vectors/conway-f649.tsv")
  private val sourceVectors = Files
    .readString(canonicalValueVectors)
    .linesIterator
    .map { case line =>
      line.split("\t", -1) match
        case Array(name, ns, value) => (name, namespace(ns), hex(value))
        case _                      => fail(s"invalid canonical Haskell vector line: $line")
    }
    .toVector

  test("fixture-backed value schemas decode and re-encode exact Haskell bytes") {
    val supported = Set(
      Namespace.Blocks,
      Namespace.Utxo,
      Namespace.Accounts,
      Namespace.CommitteeEntities,
      Namespace.PParams,
      Namespace.StakePools,
      Namespace.Committee,
      Namespace.Constitution
    )
    val selected = fixtureEntries.filter((ns, _, _) => supported.contains(ns))
    assertEquals(selected.map(_._1).distinct.toSet, supported)
    selected.foreach { case (ns, _, raw) =>
      val decoded = SclsValue.decode(ns, raw).fold(e => fail(s"$ns: $e"), identity)
      assertEquals(decoded.namespace, ns)
      assertEquals(SclsValue.encode(decoded).fold(e => fail(s"$ns: $e"), identity), raw, ns.name)
    }
  }

  test("pinned Haskell typed value vectors cover nonnull account DReps and credential maps") {
    assertEquals(sourceVectors.size, 10)
    sourceVectors.foreach { case (name, ns, raw) =>
      val decoded = SclsValue.decode(ns, raw).fold(e => fail(s"$name: $e"), identity)
      assertEquals(decoded.namespace, ns, name)
      assertEquals(SclsValue.encode(decoded).fold(e => fail(s"$name: $e"), identity), raw, name)
    }
    val drepVectors = sourceVectors.collect { case (name, Namespace.Accounts, raw) => name -> raw }
    assertEquals(drepVectors.map(_._1), Vector("account-key-drep", "account-script-drep"))
    val stateVector = sourceVectors
      .find(_._1 == "drep-anchor-and-delegations")
      .getOrElse(fail("DRep reference vector"))
    SclsValue.decode(stateVector._2, stateVector._3) match
      case Right(SclsValue.Entry.DRep(value)) =>
        assertEquals(value.anchor.map(_.url), Some("url"))
        assertEquals(value.delegators.size, 1)
      case other => fail(s"nonempty DRep reference vector did not decode: $other")
    val rootVector = sourceVectors
      .find(_._1 == "proposal-root-tuple-index-integer")
      .getOrElse(fail("proposal root vector"))
    assertEquals(
      SclsValue.decode(rootVector._2, rootVector._3).map(_.namespace),
      Right(Namespace.ProposalRoots)
    )
    assertEquals(
      SclsValue.decode(rootVector._2, rootVector._3),
      Right(
        SclsValue.Entry.ProposalRoot(
          xyz.kd5ujc.scarab.ledger.GovActionId(Bytes.wrap(Array.fill[Byte](32)(0)), 7)
        )
      )
    )
    val poolVector = sourceVectors
      .find(_._1 == "stake-pool-current-future-relay-metadata-retirement")
      .getOrElse(fail("stake pool Haskell vector"))
    SclsValue.decode(poolVector._2, poolVector._3) match
      case Right(SclsValue.Entry.StakePool(pool)) =>
        pool.state match
          case Some(state) =>
            assertEquals(state.owners.size, 1)
            assertEquals(state.metadata.isDefined, true)
            val relays = PoolRelays.decode(state.relays).fold(e => fail(e.toString), identity)
            assertEquals(relays.size, 3)
            assert(relays.exists {
              case PoolRelay.SingleHostAddr(_, Some(_), Some(_)) => true
              case _                                             => false
            })
            assert(relays.exists {
              case PoolRelay.MultiHostName(_) => true
              case _                          => false
            })
            assert(state.accountId.isInstanceOf[S.Credential.KeyHashObj[?]])
            assert(state.delegators.exists(_.isInstanceOf[S.Credential.KeyHashObj[?]]))
          case None => fail("Haskell pool vector has no registered state")
        assertEquals(pool.retiringEpoch.map(S.EpochNo.toBigInt), Some(BigInt(9)))
        pool.futureParams match
          case Some(future) =>
            assertEquals(future.rewardAccount.network, 1)
            assertEquals(future.metadata.isDefined, true)
            val relays = PoolRelays.decode(future.relays).fold(e => fail(e.toString), identity)
            assertEquals(relays.size, 3)
            assert(relays.exists {
              case PoolRelay.SingleHostAddr(_, Some(_), Some(_)) => true
              case _                                             => false
            })
            assert(relays.exists {
              case PoolRelay.MultiHostName(_) => true
              case _                          => false
            })
          case None => fail("Haskell pool vector has no future parameters")
      case other => fail(s"nonempty stake pool vector did not decode: $other")
    val proposalVector = sourceVectors
      .find(_._1 == "proposal-procedure-onchain-raw-preservation")
      .getOrElse(fail("proposal Haskell vector"))
    SclsValue.decode(proposalVector._2, proposalVector._3) match
      case Right(SclsValue.Entry.Proposal(proposal)) =>
        assertEquals(S.Word64.toBigInt(proposal.order), BigInt(7))
        assertEquals(S.EpochNo.toBigInt(proposal.proposedIn), BigInt(2))
        assertEquals(S.EpochNo.toBigInt(proposal.expiresAfter), BigInt(10))
        assertEquals(
          proposal.proposal.action,
          xyz.kd5ujc.scarab.ledger.GovAction.NoConfidence(
            Some(
              xyz.kd5ujc.scarab.ledger.GovActionId(
                Bytes.wrap(Array.fill[Byte](32)(0)),
                BigInt(7)
              )
            )
          )
        )
        assertEquals(proposal.drepVotes.size, 1)
        assertEquals(proposal.committeeVotes.size, 1)
        assertEquals(proposal.stakePoolVotes.size, 1)
        assert(
          proposal.proposalProcedureRaw.toArray
            .sliding(2)
            .exists(_.sameElements(Array(0x18.toByte, 0x05.toByte)))
        )
        assertEquals(
          SclsValue.encode(SclsValue.Entry.Proposal(proposal)),
          Right(proposalVector._3)
        )
        val changedProcedure = proposal.copy(
          proposal = proposal.proposal.copy(deposit = proposal.proposal.deposit + 1)
        )
        assertEquals(
          SclsValue.encode(SclsValue.Entry.Proposal(changedProcedure)),
          Left(SclsValue.Error.RetainedProcedureMismatch(Namespace.Proposals))
        )
      case other => fail(s"nonempty proposal vector did not decode: $other")
    val pparamsVector = sourceVectors
      .find(_._1 == "pparams-unbounded-execution-unit-natural")
      .getOrElse(fail("unbounded Conway PParams Haskell vector"))
    assertEquals(pparamsVector._2, Namespace.PParams)
    assertEquals(
      SclsValue.encode(
        SclsValue
          .decode(pparamsVector._2, pparamsVector._3)
          .fold(
            e => fail(e.toString),
            identity
          )
      ),
      Right(pparamsVector._3)
    )
    assert(
      pparamsVector._3.toArray
        .sliding(9)
        .exists(
          _.sameElements(
            Array(0x1b.toByte, 0x80.toByte, 0, 0, 0, 0, 0, 0, 0)
          )
        )
    )
    assert(
      pparamsVector._3.toArray
        .sliding(11)
        .exists(
          _.sameElements(
            Array(0xc2.toByte, 0x49.toByte, 1, 0, 0, 0, 0, 0, 0, 0, 0)
          )
        )
    )
    val pparamsRoot = Cbor.decode(pparamsVector._3).fold(e => fail(e.message), identity)
    val pparamsPairs = pparamsRoot.value match
      case V.Map(pairs) => pairs
      case _            => fail("PParams vector is not a map")
    def changedNatural(replacement: Node): Bytes =
      val changed = pparamsPairs.map { case (key, field) =>
        if key.value == V.UInt(20) then key -> replacement else key -> field
      }
      Cbor.encode(V.Map(changed)).fold(e => fail(e.message), identity)
    val negative = changedNatural(
      Node(V.Arr(Vector(Node(V.NInt(-1), Bytes.empty), Node(V.UInt(1), Bytes.empty))), Bytes.empty)
    )
    assert(SclsValue.decode(Namespace.PParams, negative).isLeft)
    val nonMinimalNatural = changedNatural(
      Node(
        V.Arr(
          Vector(
            Node(V.Tag(2, Node(V.ByteString(hex("8000000000000000")), Bytes.empty)), Bytes.empty),
            Node(V.UInt(1), Bytes.empty)
          )
        ),
        Bytes.empty
      )
    )
    assert(SclsValue.decode(Namespace.PParams, nonMinimalNatural).isLeft)
  }

  test("UTxO OnChain values keep exact embedded output bytes") {
    val rows = fixtureEntries.collect { case (Namespace.Utxo, _, raw) => raw }
    assert(rows.nonEmpty)
    rows.foreach { outer =>
      val entry = SclsValue.decode(Namespace.Utxo, outer).fold(e => fail(e.toString), identity)
      entry match
        case SclsValue.Entry.Utxo(output) =>
          val outerNode = Cbor.decode(outer).fold(e => fail(e.message), identity)
          assertEquals(outerNode.value, V.ByteString(output.raw))
          assertEquals(SclsValue.encode(entry), Right(outer))
        case other => fail(s"wrong UTxO entry $other")
    }
  }

  test("SCLS UTxO applies one caller-selected CBOR limit to the outer value and original TxOut") {
    val base = fixtureEntries
      .collectFirst { case (Namespace.Utxo, _, raw) =>
        SclsValue
          .decode(Namespace.Utxo, raw)
          .fold(
            e => fail(e.toString),
            {
              case SclsValue.Entry.Utxo(output) => output
              case other                        => fail(s"not a UTxO entry: $other")
            }
          )
      }
      .getOrElse(fail("fixture UTxO row"))
    val wide =
      Cbor.Limits(maxInputBytes = 2_000_000, maxItems = 200_000, maxStringBytes = 2_000_000)
    val small =
      Cbor.Limits(maxInputBytes = 1_000_000, maxItems = 100_000, maxStringBytes = 1_000_000)
    val inline = Bytes.wrap(Array.fill[Byte](1_100_000)(1))
    val datum = Node(
      V.Arr(
        Vector(
          Node(V.UInt(1), Bytes.empty),
          Node(V.Tag(24, Node(V.ByteString(inline), Bytes.empty)), Bytes.empty)
        )
      ),
      Bytes.empty
    )
    val inner = Cbor
      .encode(
        V.Map(
          Vector(
            Node(V.UInt(0), Bytes.empty) -> Node(V.ByteString(base.address.raw), Bytes.empty),
            Node(V.UInt(1), Bytes.empty) -> Node(V.UInt(1), Bytes.empty),
            Node(V.UInt(2), Bytes.empty) -> datum
          )
        ),
        wide
      )
      .fold(e => fail(e.message), identity)
    val output = UtxoCodecs.txOut
      .decode(Cbor.decode(inner, wide).fold(e => fail(e.message), identity))
      .fold(e => fail(e.toString), identity)
    val entry = SclsValue.Entry.Utxo(output)
    val wrapped = SclsValue.encode(entry, wide).fold(e => fail(e.toString), identity)
    assert(output.raw.size > 1_048_576)
    assertEquals(SclsValue.decode(Namespace.Utxo, wrapped, wide), Right(entry))
    assertEquals(
      SclsValue.decode(Namespace.Utxo, wrapped, small),
      Left(SclsValue.Error.Cbor(Cbor.Error.InputLimitExceeded))
    )
  }

  test("VRF key-hash counts are positive unsigned words") {
    val raw = hex("01")
    val value =
      SclsValue.decode(Namespace.StakePoolVrfHashes, raw).fold(e => fail(e.toString), identity)
    val word = xyz.kd5ujc.scarab.ledger.state.Word64
      .fromBigInt(
        xyz.kd5ujc.scarab.ledger.state.VocabError.Kind.NonZero,
        BigInt(1)
      )
      .fold(e => fail(e.toString), identity)
    val nonzero =
      xyz.kd5ujc.scarab.ledger.state.NonZero.from(word).fold(e => fail(e.toString), identity)
    assertEquals(value, SclsValue.Entry.VrfKeyCount(nonzero))
    assertEquals(SclsValue.encode(value), Right(raw))
    assert(SclsValue.decode(Namespace.StakePoolVrfHashes, hex("00")).isLeft)
    val largest = hex("1bffffffffffffffff")
    val largestValue =
      SclsValue.decode(Namespace.StakePoolVrfHashes, largest).fold(e => fail(e.toString), identity)
    assertEquals(SclsValue.encode(largestValue), Right(largest))
  }

  test("committee entity credentials use SCLS Script=0 and Key=1 canonical tags") {
    val digest = Bytes.wrap(Array.fill[Byte](28)(0))
    val scriptCredential = Node(
      V.Arr(
        Vector(
          Node(V.UInt(0), Bytes.empty),
          Node(V.ByteString(digest), Bytes.empty)
        )
      ),
      Bytes.empty
    )
    val keyCredential = Node(
      V.Arr(
        Vector(
          Node(V.UInt(1), Bytes.empty),
          Node(V.ByteString(digest), Bytes.empty)
        )
      ),
      Bytes.empty
    )
    val auth = Node(
      V.Arr(
        Vector(
          Node(V.UInt(0), Bytes.empty),
          keyCredential
        )
      ),
      Bytes.empty
    )
    val fields = Node(V.Map(Vector(scriptCredential -> auth)), Bytes.empty)
    val raw = Cbor.encode(fields.value).fold(e => fail(e.message), identity)
    val decoded =
      SclsValue.decode(Namespace.CommitteeEntities, raw).fold(e => fail(e.toString), identity)
    decoded match
      case SclsValue.Entry.CommitteeEntity(state) =>
        val expectedHash = xyz.kd5ujc.scarab.ledger.state.ScriptHash
          .fromBytes(digest)
          .fold(e => fail(e.toString), identity)
        assertEquals(
          state.creds.keys.toVector.head,
          xyz.kd5ujc.scarab.ledger.state.Credential.ScriptHashObj(
            expectedHash
          )
        )
      case other => fail(s"wrong decoded entry $other")
    assertEquals(SclsValue.encode(decoded), Right(raw))
  }

  test("account DRep delegates use the ledger DRep wire tags, not canonical Credential tags") {
    val digest = Bytes.wrap(Array.fill[Byte](28)(0))
    val ledgerDRep = summon[Codec[LedgerDRep]]
    val keyDRep = ledgerDRep.encodeUnchecked(LedgerDRep.Registered(LedgerCredential.Key(digest)))
    val scriptDRep =
      ledgerDRep.encodeUnchecked(LedgerDRep.Registered(LedgerCredential.Script(digest)))
    assertEquals(keyDRep.hex, s"8200581c${digest.hex}")
    assertEquals(scriptDRep.hex, s"8201581c${digest.hex}")
    val keyDRepNode = Cbor.decode(keyDRep).fold(e => fail(e.message), identity)

    val accountFields = Vector(
      V.Text("balance") -> V.UInt(7),
      V.Text("deposit") -> V.UInt(9),
      V.Text("drep_delegation") -> keyDRepNode.value,
      V.Text("stake_pool_delegation") -> V.Null
    ).map { case (key, value) => Node(key, Bytes.empty) -> Node(value, Bytes.empty) }
    val raw = Cbor.encode(V.Map(accountFields)).fold(e => fail(e.message), identity)
    val decoded = SclsValue.decode(Namespace.Accounts, raw).fold(e => fail(e.toString), identity)
    decoded match
      case SclsValue.Entry.Account(value) =>
        assertEquals(
          value.drep,
          Some(
            xyz.kd5ujc.scarab.ledger.state.DRep.DRepKeyHash(
              xyz.kd5ujc.scarab.ledger.state.KeyHash
                .fromBytes[xyz.kd5ujc.scarab.ledger.state.DRepRole](digest)
                .fold(e => fail(e.toString), identity)
            )
          )
        )
      case other => fail(s"wrong decoded entry $other")
    assertEquals(SclsValue.encode(decoded), Right(raw))
  }

  test("Blocks Natural values use tag-2 bignums above uint64") {
    val raw = hex("c249010000000000000000")
    assertEquals(
      SclsValue.decode(Namespace.Blocks, raw),
      Right(SclsValue.Entry.Blocks(BigInt(1) << 64))
    )
    assertEquals(SclsValue.encode(SclsValue.Entry.Blocks(BigInt(1) << 64)), Right(raw))
    assert(
      SclsValue.decode(Namespace.Blocks, hex("c24101")).isLeft
    ) // tag 2 is noncanonical below uint64
  }

  test("ProposalRoots value carries a CBOR integer index, bounded by Word16") {
    val raw = sourceVectors
      .find(_._1 == "proposal-root-tuple-index-integer")
      .getOrElse(fail("proposal root vector"))
      ._3
    val id = xyz.kd5ujc.scarab.ledger.GovActionId(Bytes.wrap(Array.fill[Byte](32)(0)), 7)
    assertEquals(
      SclsValue.decode(Namespace.ProposalRoots, raw),
      Right(SclsValue.Entry.ProposalRoot(id))
    )
    assertEquals(SclsValue.encode(SclsValue.Entry.ProposalRoot(id)), Right(raw))
    assert(SclsValue.encode(SclsValue.Entry.ProposalRoot(id.copy(index = 65536))).isLeft)
  }

  test("PParams are complete typed Conway numeric-key maps, not state arrays or partial updates") {
    val rows = fixtureEntries.collect { case (Namespace.PParams, _, raw) => raw }
    assertEquals(rows.size, 2)
    val expectedKeys = (0 to 11).map(BigInt(_)).toVector ++ Vector(BigInt(14)) ++ (16 to 33)
      .map(BigInt(_))
    rows.foreach { raw =>
      val decoded = SclsValue.decode(Namespace.PParams, raw).fold(e => fail(e.toString), identity)
      assert(decoded.isInstanceOf[SclsValue.Entry.PParams])
      assertEquals(SclsValue.encode(decoded), Right(raw))
      assertEquals(
        SclsValue.encode(decoded).flatMap(SclsValue.decode(Namespace.PParams, _)),
        Right(decoded)
      )
      Cbor.decode(raw).fold(e => fail(e.message), identity).value match
        case V.Map(pairs) =>
          assertEquals(
            pairs.map { case (key, _) =>
              key.value match
                case V.UInt(value) => value
                case other         => fail(s"non-integer PParams key $other")
            },
            expectedKeys
          )
        case other => fail(s"PParams fixture is not a numeric-key map: $other")
    }
    val source = Cbor.decode(rows.head).fold(e => fail(e.message), identity)
    source.value match
      case V.Map(pairs) =>
        val incomplete = Cbor.encode(V.Map(pairs.dropRight(1))).fold(e => fail(e.message), identity)
        assert(SclsValue.decode(Namespace.PParams, incomplete).isLeft)
        val duplicate = Cbor
          .encode(V.Map(pairs.updated(pairs.size - 1, pairs.head)))
          .fold(e => fail(e.message), identity)
        assert(SclsValue.decode(Namespace.PParams, duplicate).isLeft)
        val unknown = Cbor
          .encode(
            V.Map(pairs.updated(pairs.size - 1, Node(V.UInt(34), Bytes.empty) -> pairs.last._2))
          )
          .fold(e => fail(e.message), identity)
        assert(SclsValue.decode(Namespace.PParams, unknown).isLeft)
      case other => fail(s"PParams fixture is not a map: $other")
    val array = Cbor.encode(V.Arr(Vector.empty)).fold(e => fail(e.message), identity)
    assert(SclsValue.decode(Namespace.PParams, array).isLeft)
  }

  test("StakePools refuses invalid manually constructed ledger bounds and retained bytes") {
    val raw = sourceVectors
      .find(_._1 == "stake-pool-current-future-relay-metadata-retirement")
      .getOrElse(fail("stake pool Haskell vector"))
      ._3
    val pool = SclsValue
      .decode(Namespace.StakePools, raw)
      .fold(
        e => fail(e.toString),
        {
          case SclsValue.Entry.StakePool(value) => value
          case other                            => fail(s"not a StakePool entry: $other")
        }
      )
    pool.futureParams match
      case Some(future) =>
        assert(
          SclsValue
            .encode(
              SclsValue.Entry
                .StakePool(pool.copy(futureParams = Some(future.copy(owners = Set(Bytes.empty)))))
            )
            .isLeft
        )
        assert(
          SclsValue
            .encode(
              SclsValue.Entry.StakePool(
                pool.copy(futureParams =
                  Some(future.copy(margin = xyz.kd5ujc.scarab.codec.Rational(11, 10)))
                )
              )
            )
            .isLeft
        )
        assert(
          SclsValue
            .encode(
              SclsValue.Entry.StakePool(
                pool.copy(futureParams =
                  Some(future.copy(metadata = Some(Bytes.wrap(Array[Byte](0)))))
                )
              )
            )
            .isLeft
        )
      case None => fail("Haskell pool vector has no future parameters")
  }

  test("PoolRelays propagates canonical shape and caller-selected CBOR limits") {
    assert(PoolRelays.decode(hex("8180")).isLeft)
    assert(PoolRelays.decode(hex("80"), Cbor.Limits(maxInputBytes = 0)).isLeft)
  }

  test("anchor URL limit counts UTF-8 bytes on decode and encode") {
    val digest = Bytes.wrap(Array.fill[Byte](32)(0))
    val validUrl = "é" * 64 // 128 UTF-8 bytes
    val valid = SclsValue.Entry.GovernanceConstitution(
      xyz.kd5ujc.scarab.ledger.Constitution(xyz.kd5ujc.scarab.ledger.Anchor(validUrl, digest), None)
    )
    val validBytes = SclsValue.encode(valid).fold(e => fail(e.toString), identity)
    assertEquals(SclsValue.decode(Namespace.Constitution, validBytes), Right(valid))
    val tooLong = SclsValue.Entry.GovernanceConstitution(
      xyz.kd5ujc.scarab.ledger.Constitution(xyz.kd5ujc.scarab.ledger.Anchor("é" * 65, digest), None)
    )
    assert(SclsValue.encode(tooLong).isLeft)
    val oversizedNode = Node(
      V.Arr(
        Vector(
          Node(
            V.Arr(
              Vector(Node(V.Text("é" * 65), Bytes.empty), Node(V.ByteString(digest), Bytes.empty))
            ),
            Bytes.empty
          ),
          Node(V.Null, Bytes.empty)
        )
      ),
      Bytes.empty
    )
    val oversizedBytes = Cbor.encode(oversizedNode.value).fold(e => fail(e.message), identity)
    assert(SclsValue.decode(Namespace.Constitution, oversizedBytes).isLeft)
  }

  test("Committee UnitInterval rejects zero denominators and values above one without throwing") {
    def encoded(numerator: BigInt, denominator: BigInt): Bytes =
      val members = Node(V.Map(Vector.empty), Bytes.empty)
      val ratio = Node(
        V.Tag(
          30,
          Node(
            V.Arr(
              Vector(
                Node(V.UInt(numerator), Bytes.empty),
                Node(V.UInt(denominator), Bytes.empty)
              )
            ),
            Bytes.empty
          )
        ),
        Bytes.empty
      )
      Cbor.encode(V.Arr(Vector(members, ratio))).fold(e => fail(e.message), identity)
    assert(SclsValue.decode(Namespace.Committee, encoded(0, 0)).isLeft)
    assert(SclsValue.decode(Namespace.Committee, encoded(2, 1)).isLeft)
    assert(
      SclsValue.decode(Namespace.Committee, encoded(2, 4)).isLeft
    ) // decoder re-encode rejects non-reduced form
  }

  test("Committee encoder refuses manually constructed non-ledger credential lengths") {
    val malformed = xyz.kd5ujc.scarab.ledger.Committee(
      Map(LedgerCredential.Key(Bytes.wrap(Array.fill[Byte](27)(0))) -> BigInt(1)),
      xyz.kd5ujc.scarab.codec.Rational(1, 2)
    )
    assert(SclsValue.encode(SclsValue.Entry.GovernanceCommittee(Some(malformed))).isLeft)
  }

  test("proposal namespace rejects a value with the wrong outer shape") {
    val result = SclsValue.decode(Namespace.Proposals, hex("a0"))
    assertEquals(result, Left(SclsValue.Error.WrongShape(Namespace.Proposals, "array(2)")))
  }
