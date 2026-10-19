// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.EraLedgerState
import xyz.kd5ujc.scarab.ledger.state.{
  CompactCoin,
  Conway,
  ConwayLedgerState,
  ConwayNewEpochState,
  EpochNo,
  NewEpochState,
  ShelleyBasedEra,
  UtxoTables
}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellLedgerFile, HaskellStateCodecs}
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, Word64}
import xyz.kd5ujc.scarab.storage.scls.SclsRecord.Record

class SclsStateMapperSuite extends FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)

  private val stateBytes =
    Bytes.wrap(Files.readAllBytes(Path.of("fixtures/haskell-state/conway-4593/state")))
  private val base: NewEpochState[Conway] =
    val file = right(HaskellLedgerFile.decode(stateBytes))
    val ext = file.ext.getOrElse(fail("expected complete Shelley-based reference state"))
    ext.ledgerState.state match
      case EraLedgerState.InShelleyEra(era, ledgerState) =>
        era match
          case ShelleyBasedEra.SConway => ledgerState.nes
          case _                       => fail("expected Conway fixture")
      case EraLedgerState.ByronCurrent(_) => fail("expected Conway fixture")

  private val frames =
    val reference =
      Bytes.wrap(Files.readAllBytes(Path.of("fixtures/scls/conway-4593/reference.scls")))
    right(SclsFraming.decode(reference, right(SclsFraming.Limit.fromInt(1024 * 1024))))

  private val fixtureEntries = frames.flatMap(frame =>
    right(SclsRecord.decode(frame)) match
      case chunk: Record.Chunk
          if Set(
            SclsRecord.Namespace.Blocks,
            SclsRecord.Namespace.Accounts,
            SclsRecord.Namespace.CommitteeEntities,
            SclsRecord.Namespace.StakePools,
            SclsRecord.Namespace.StakePoolVrfHashes,
            SclsRecord.Namespace.Committee,
            SclsRecord.Namespace.Constitution,
            SclsRecord.Namespace.PParams,
            SclsRecord.Namespace.ProposalRoots,
            SclsRecord.Namespace.DReps,
            SclsRecord.Namespace.Proposals
          ).contains(chunk.namespace) =>
        right(SclsRecord.entries(chunk)).map { raw =>
          val key = right(SclsKey.decode(chunk.namespace, raw.key))
          val value = right(SclsValue.decode(chunk.namespace, raw.value))
          key -> value
        }
      case _ => Vector.empty
  )

  private def proposalFixtureValue(rowPrefix: String) =
    val line = Files
      .readString(Path.of("fixtures/scls/proposal-roundtrip/f649.tsv"))
      .linesIterator
      .find(_.startsWith(s"$rowPrefix\t"))
      .getOrElse(fail(s"pinned Haskell proposal roundtrip row: $rowPrefix"))
    val encoded = Bytes.fromHex(line.split("\t", -1)(1)).fold(e => fail(e.message), identity)
    right(SclsValue.decode(SclsRecord.Namespace.Proposals, encoded)) match
      case SclsValue.Entry.Proposal(value) => value
      case _                               => fail("proposal value vector")

  private val proposalValue = proposalFixtureValue("original")
  private val normalizedProposalValue = proposalFixtureValue("via-ledger-state")

  test("projects and overlays the implemented families from the real complete Conway base") {
    val projected = right(SclsStateMapper.project(base)).toVector
    val fixtureEncoded = fixtureEntries
      .map { case (key, value) =>
        (right(SclsKey.encode(key)), right(SclsValue.encode(value)))
      }
      .sortWith((left, right) => left._1.compareTo(right._1) < 0)
    val projectedEncoded = projected
      .map { case (key, value) =>
        (right(SclsKey.encode(key)), right(SclsValue.encode(value)))
      }
      .sortWith((left, right) => left._1.compareTo(right._1) < 0)
    assertEquals(projectedEncoded, fixtureEncoded)
    val rebuilt = right(SclsStateMapper.overlay(base, fixtureEntries.iterator))
    assertEquals(rebuilt, base)
    val reexported = right(
      SclsStateMapper
        .project(rebuilt)
    )
      .map { case (key, value) =>
        (right(SclsKey.encode(key)), right(SclsValue.encode(value)))
      }
      .toVector
      .sortWith((left, right) => left._1.compareTo(right._1) < 0)
    assertEquals(reexported, fixtureEncoded)
  }

  test("overlay replaces mapped maps and preserves all unrepresented base state") {
    val (blockKey, SclsValue.Entry.Blocks(oldCount)) = fixtureEntries
      .collectFirst { case pair @ (SclsKey.Key.Blocks(_, _), SclsValue.Entry.Blocks(_)) => pair }
      .getOrElse(fail("reference block entry")): @unchecked
    val replacementBlock = blockKey -> SclsValue.Entry.Blocks(oldCount + 1)

    val (accountKey, SclsValue.Entry.Account(oldAccount)) = fixtureEntries
      .collectFirst { case pair @ (SclsKey.Key.Accounts(_), SclsValue.Entry.Account(_)) => pair }
      .getOrElse(fail("reference account entry")): @unchecked
    val replacementBalance = right(
      CompactCoin.fromBigInt(oldAccount.balance.toBigInt + 1)
    )
    val replacementAccount = accountKey -> SclsValue.Entry.Account(
      oldAccount.copy(balance = replacementBalance)
    )

    val changedEntries = fixtureEntries.filterNot {
      case (_: SclsKey.Key.Blocks, _) | (_: SclsKey.Key.Accounts, _) => true
      case _                                                         => false
    } ++ Vector(replacementBlock, replacementAccount)
    val updated = right(SclsStateMapper.overlay(base, changedEntries.iterator))
    val projected = right(SclsStateMapper.project(updated)).toVector

    assertEquals(
      projected.collect { case (key: SclsKey.Key.Blocks, value) => key -> value },
      Vector(replacementBlock)
    )
    assertEquals(
      projected.collect { case (key: SclsKey.Key.Accounts, value) => key -> value },
      Vector(replacementAccount)
    )
    assertEquals(ConwayNewEpochState.dreps.get(updated), ConwayNewEpochState.dreps.get(base))
    val basePState = ConwayLedgerState.pState.get(ConwayNewEpochState.ls.get(base))
    val updatedPState = ConwayLedgerState.pState.get(ConwayNewEpochState.ls.get(updated))
    assertEquals(updatedPState.stakePools, basePState.stakePools)
    assertEquals(updatedPState.future, basePState.future)
    assertEquals(updatedPState.retiring, basePState.retiring)
    assertEquals(updatedPState.vrfKeyHashes, basePState.vrfKeyHashes)
    assertEquals(updated.bprev, base.bprev)
    assertEquals(updated.es.snapshots, base.es.snapshots)
    assertEquals(updated.es.nonMyopic, base.es.nonMyopic)
    val originalUtxo = UtxoTables.utxo(ConwayNewEpochState.ls.get(base).utxo.utxo)
    val updatedUtxo = UtxoTables.utxo(ConwayNewEpochState.ls.get(updated).utxo.utxo)
    assert(originalUtxo eq updatedUtxo)
  }

  test("overlay rejects block epochs outside its explicit base point") {
    val block = fixtureEntries
      .collectFirst { case pair @ (SclsKey.Key.Blocks(_, _), _) => pair }
      .getOrElse(fail("reference blocks entry"))
    val (SclsKey.Key.Blocks(pool, epoch), value) = block: @unchecked
    val wrong = EpochNo.fromBigInt(epoch.toBigInt + 1).fold(e => fail(e.toString), identity)
    val entries = fixtureEntries.map {
      case (SclsKey.Key.Blocks(`pool`, `epoch`), entry) => SclsKey.Key.Blocks(pool, wrong) -> entry
      case pair                                         => pair
    }
    assert(SclsStateMapper.overlay(base, entries.iterator).left.exists {
      case SclsStateMapper.Error.WrongEpoch(expected, found) =>
        expected == base.epoch.toBigInt && found == wrong.toBigInt
      case _ => false
    })
    assert(value.namespace == SclsRecord.Namespace.Blocks)
  }

  test("overlay rejects count overflow and missing or duplicate singleton rows") {
    val (key, _) = fixtureEntries
      .collectFirst { case pair @ (SclsKey.Key.Blocks(_, _), _) => pair }
      .getOrElse(fail("reference blocks entry"))
    val overflow = (key, SclsValue.Entry.Blocks(BigInt(1) << 64))
    val withOverflow = fixtureEntries.map {
      case (`key`, _) => overflow
      case pair       => pair
    }
    assertEquals(
      SclsStateMapper.overlay(base, withOverflow.iterator),
      Left(SclsStateMapper.Error.BlockCountOutOfRange(BigInt(1) << 64))
    )

    val singleton = fixtureEntries
      .collectFirst { case pair @ (SclsKey.Key.CommitteeEntities, _) =>
        pair
      }
      .getOrElse(fail("reference committee singleton"))
    assert(
      SclsStateMapper.overlay(base, fixtureEntries.filterNot(_ == singleton).iterator).left.exists {
        case SclsStateMapper.Error.MissingSingleton(SclsRecord.Namespace.CommitteeEntities) => true
        case _                                                                              => false
      }
    )
    assert(SclsStateMapper.overlay(base, (fixtureEntries :+ singleton).iterator).left.exists {
      case SclsStateMapper.Error.DuplicateSingleton(SclsRecord.Namespace.CommitteeEntities) => true
      case _                                                                                => false
    })
  }

  test("overlay rejects duplicate pool/VRF rows and empty pool records") {
    val (poolKey, poolEntry) = fixtureEntries
      .collectFirst { case pair @ (SclsKey.Key.StakePools(_), SclsValue.Entry.StakePool(_)) =>
        pair
      }
      .getOrElse(fail("reference stake pool"))
    assert(
      SclsStateMapper
        .overlay(base, (fixtureEntries ++ Vector(poolKey -> poolEntry)).iterator)
        .left
        .exists {
          case SclsStateMapper.Error.DuplicateKey(`poolKey`) => true
          case _                                             => false
        }
    )
    val emptyPool = poolKey -> SclsValue.Entry.StakePool(SclsValue.StakePoolValue(None, None, None))
    val withoutPools = fixtureEntries.filterNot(_._1.isInstanceOf[SclsKey.Key.StakePools])
    assert(SclsStateMapper.overlay(base, (withoutPools :+ emptyPool).iterator).left.exists {
      case SclsStateMapper.Error.EmptyStakePoolValue(`poolKey`) => true
      case _                                                    => false
    })

    val actualPool = poolEntry match
      case SclsValue.Entry.StakePool(value) => value.state.getOrElse(fail("pool state"))
      case _                                => fail("pool state")
    val vrfKey = SclsKey.Key.StakePoolVrfHashes(actualPool.vrf)
    val vrfEntry = SclsValue.Entry.VrfKeyCount(
      xyz.kd5ujc.scarab.ledger.state.NonZero.oneWord
    )
    val vrfRow = vrfKey -> vrfEntry
    assert(
      SclsStateMapper
        .overlay(base, (fixtureEntries ++ Vector(vrfRow, vrfRow)).iterator)
        .left
        .exists {
          case SclsStateMapper.Error.DuplicateKey(`vrfKey`) => true
          case _                                            => false
        }
    )
  }

  test("PParams are required and future selectors must agree with the explicit base") {
    val parameterRows = fixtureEntries.collect { case pair @ (SclsKey.Key.PParams(_), _) =>
      pair
    }
    val withoutPrevious = fixtureEntries.filterNot {
      case (SclsKey.Key.PParams(SclsKey.Parameters.Previous), _) => true
      case _                                                     => false
    }
    assert(SclsStateMapper.overlay(base, withoutPrevious.iterator).left.exists {
      case SclsStateMapper.Error.MissingParameters(SclsKey.Parameters.Previous) => true
      case _                                                                    => false
    })

    val currentValue = parameterRows
      .collectFirst { case (_, SclsValue.Entry.PParams(value)) =>
        value
      }
      .getOrElse(fail("reference current or previous parameters"))
    val conflictingFuture = SclsKey.Key.PParams(SclsKey.Parameters.PossibleFuture) ->
      SclsValue.Entry.PParams(currentValue)
    assert(
      SclsStateMapper.overlay(base, (fixtureEntries :+ conflictingFuture).iterator).left.exists {
        case SclsStateMapper.Error.ConflictingFutureParameters(SclsKey.Parameters.PossibleFuture) =>
          true
        case _ => false
      }
    )
  }

  test("proposal roots are replaced from the incoming partial root namespace") {
    val replacement = xyz.kd5ujc.scarab.ledger.GovActionId(
      Bytes.wrap(Array.fill(32)(0x5a.toByte)),
      7
    )
    val incoming = fixtureEntries.filterNot {
      case (SclsKey.Key.ProposalRoots(SclsKey.Purpose.Parameters), _) => true
      case _                                                          => false
    } :+ (SclsKey.Key.ProposalRoots(SclsKey.Purpose.Parameters) ->
      SclsValue.Entry.ProposalRoot(replacement))
    val rebuilt = right(SclsStateMapper.overlay(base, incoming.iterator))
    assertEquals(
      ConwayNewEpochState.gov.get(rebuilt).proposals.roots.paramUpdate,
      Some(replacement)
    )
    val projected = right(SclsStateMapper.project(rebuilt)).collect {
      case pair @ (SclsKey.Key.ProposalRoots(SclsKey.Purpose.Parameters), _) => pair
    }.toVector
    assertEquals(
      projected,
      Vector(
        SclsKey.Key.ProposalRoots(SclsKey.Purpose.Parameters) ->
          SclsValue.Entry.ProposalRoot(replacement)
      )
    )
  }

  test("ordered proposals normalize retained procedure bytes and enforce source consistency") {
    val id = xyz.kd5ujc.scarab.ledger.GovActionId(
      Bytes.wrap(Array.fill[Byte](32)(0)),
      7
    )
    val key = SclsKey.Key.Proposals(id)
    val canonicalProcedure = right(
      HaskellStateCodecs
        .proposal(using ProtVer(Word64.fromUnsigned(9L), Word64.zero))
        .write(proposalValue.proposal)
    )
    assertNotEquals(proposalValue.proposalProcedureRaw, canonicalProcedure)
    assertEquals(canonicalProcedure, normalizedProposalValue.proposalProcedureRaw)
    val orderedValue = proposalValue.copy(order = Word64.zero)
    val row = key -> SclsValue.Entry.Proposal(orderedValue)
    val rebuilt = right(SclsStateMapper.overlay(base, (fixtureEntries :+ row).iterator))
    val projected = right(SclsStateMapper.project(rebuilt)).collect {
      case pair @ (_: SclsKey.Key.Proposals, _) => pair
    }.toVector
    assertEquals(
      projected,
      Vector(
        key -> SclsValue.Entry.Proposal(
          normalizedProposalValue.copy(order = Word64.zero)
        )
      )
    )

    val retainedSourceRow = key -> SclsValue.Entry.Proposal(orderedValue)
    val encodedSourceValue = right(SclsValue.encode(retainedSourceRow._2))
    assertEquals(
      right(SclsValue.decode(SclsRecord.Namespace.Proposals, encodedSourceValue)),
      retainedSourceRow._2
    )
    assertEquals(
      right(SclsStateMapper.overlay(base, (fixtureEntries :+ retainedSourceRow).iterator)),
      rebuilt
    )

    val outOfOrder = key -> SclsValue.Entry.Proposal(orderedValue.copy(order = proposalValue.order))
    assertEquals(
      SclsStateMapper.overlay(base, (fixtureEntries :+ outOfOrder).iterator),
      Left(SclsStateMapper.Error.ProposalOrder(0, 7))
    )
    val changedProcedure = orderedValue.copy(
      proposal = orderedValue.proposal.copy(deposit = orderedValue.proposal.deposit + 1)
    )
    assertEquals(
      SclsStateMapper.overlay(
        base,
        (fixtureEntries :+ (key -> SclsValue.Entry.Proposal(changedProcedure))).iterator
      ),
      Left(SclsStateMapper.Error.ProposalProcedureMismatch(id))
    )
    val secondId = id.copy(txId = Bytes.wrap(Array.fill[Byte](32)(1)))
    val duplicateOrder = SclsKey.Key.Proposals(secondId) -> SclsValue.Entry.Proposal(orderedValue)
    assertEquals(
      SclsStateMapper.overlay(base, (fixtureEntries ++ Vector(row, duplicateOrder)).iterator),
      Left(SclsStateMapper.Error.ProposalOrder(1, 0))
    )
  }

  test("incremental overlay builder delegates validation and leaves prior state after refusal") {
    val allEntries = fixtureEntries
    val blockRow = allEntries
      .collectFirst { case row @ (_: SclsKey.Key.Blocks, _) =>
        row
      }
      .getOrElse(fail("fixture blocks row"))
    val initial = SclsStateMapper.begin(base)
    val withBlock = right(initial.push(blockRow._1, blockRow._2))
    assertEquals(
      withBlock.push(blockRow._1, blockRow._2),
      Left(SclsStateMapper.Error.DuplicateKey(blockRow._1))
    )

    val finished = allEntries
      .filterNot(_ == blockRow)
      .foldLeft[Either[SclsStateMapper.Error, SclsStateMapper.Builder]](Right(withBlock)) {
        case (builderE, (key, value)) => builderE.flatMap(_.push(key, value))
      }
      .flatMap(_.finish)
    assertEquals(finished, SclsStateMapper.overlay(base, allEntries.iterator))
  }
