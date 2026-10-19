// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.EraLedgerState
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  ConwayLedgerState,
  ConwayNewEpochState,
  Credential,
  DRepRole,
  DRepState,
  EpochNo,
  KeyHash,
  NewEpochState,
  ShelleyBasedEra,
  Sorted
}
import xyz.kd5ujc.scarab.storage.haskell.state.HaskellLedgerFile
import xyz.kd5ujc.scarab.storage.scls.SclsRecord.Namespace

class SclsDRepMapperSuite extends FunSuite:
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def epoch(value: BigInt): EpochNo = right(EpochNo.fromBigInt(value))
  private val maximum = (BigInt(1) << 64) - 1
  private val credential = Credential.KeyHashObj(
    right(KeyHash.fromBytes[DRepRole](Bytes.wrap(Array.fill[Byte](28)(0))))
  )
  private val voting = ConwayNewEpochState.ls.andThen(ConwayLedgerState.vState)
  private val base =
    val file = right(
      HaskellLedgerFile.decode(
        Bytes.wrap(Files.readAllBytes(Path.of("fixtures/haskell-state/conway-4593/state")))
      )
    )
    file.ext.getOrElse(fail("complete reference state")).ledgerState.state match
      case EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, state) => state.nes
      case _ => fail("Conway reference state")
  private val drep =
    val raw = Files
      .readString(Path.of("fixtures/scls/namespace-value-vectors/conway-f649.tsv"))
      .linesIterator
      .collectFirst {
        case line if line.startsWith("drep-anchor-and-delegations\t") =>
          line.split("\t", -1) match
            case Array(_, _, hex) => right(Bytes.fromHex(hex))
            case _                => fail("reference DRep vector")
      }
      .getOrElse(fail("reference DRep vector"))
    right(SclsValue.decode(Namespace.DReps, raw)) match
      case SclsValue.Entry.DRep(value) => value
      case _                           => fail("typed reference DRep")

  private def state(current: BigInt, dormant: BigInt, expiry: BigInt): NewEpochState[Conway] =
    voting.modify(
      _.copy(
        dreps = Sorted.map(Vector(credential -> drep.copy(expiry = epoch(expiry)))),
        numDormantEpochs = epoch(dormant)
      )
    )(NewEpochState.epoch[Conway].replace(epoch(current))(base))

  private def projected(value: NewEpochState[Conway]): Vector[(Credential[DRepRole], DRepState)] =
    SclsDRepMapper
      .project(value)
      .map {
        case (SclsKey.Key.DReps(key), SclsValue.Entry.DRep(record)) => key -> record
        case other => fail(s"unexpected DRep projection $other")
      }
      .toVector

  test(
    "dormancy normalization matches the unmodified pinned Haskell function at Word64 boundaries"
  ) {
    val rows = Files.readString(Path.of("fixtures/scls/dormancy/f649.tsv")).linesIterator.toVector
    assertEquals(rows.size, 10)
    rows.foreach { row =>
      row.split("\t", -1).map(BigInt(_)) match
        case Array(current, dormant, expiry, actual, remaining) =>
          assertEquals(
            SclsDRepMapper.normalizeExpiry(epoch(current), epoch(dormant), epoch(expiry)),
            epoch(actual)
          )
          assertEquals(remaining, BigInt(0))
          val original = state(current, dormant, expiry)
          val entries = projected(original)
          assertEquals(entries.map(_._2.expiry.toBigInt), Vector(actual))
          val rebuilt = right(SclsDRepMapper.overlay(original, entries.iterator))
          assertEquals(rebuilt, original)
          assertEquals(voting.get(rebuilt).numDormantEpochs.toBigInt, dormant)
        case _ => fail(s"invalid Haskell dormancy vector: $row")
    }
  }

  test(
    "two reference preimages have the same canonical expiry and retain their distinct originals"
  ) {
    val wrapped = state(10, 2, maximum)
    val unwrapped = state(10, 2, maximum - 2)
    assertEquals(projected(wrapped), projected(unwrapped))
    val rows = projected(wrapped)
    assertEquals(right(SclsDRepMapper.overlay(wrapped, rows.iterator)), wrapped)
    assertEquals(right(SclsDRepMapper.overlay(unwrapped, rows.iterator)), unwrapped)
  }

  test("missing, duplicate, unexpected and changed canonical DReps are typed refusals") {
    val original = state(10, 2, 8)
    val rows = projected(original)
    assertEquals(
      SclsDRepMapper.overlay(original, Iterator.empty),
      Left(SclsDRepMapper.Error.MissingCredential(credential))
    )
    assertEquals(
      SclsDRepMapper.overlay(original, (rows ++ rows).iterator),
      Left(SclsDRepMapper.Error.DuplicateCredential(credential))
    )
    val changed = rows.map((key, value) => key -> value.copy(expiry = epoch(11)))
    assertEquals(
      SclsDRepMapper.overlay(original, changed.iterator),
      Left(SclsDRepMapper.Error.ProjectionMismatch(credential))
    )
    val empty =
      ConwayNewEpochState.dreps.replace(Sorted.empty[Credential[DRepRole], DRepState])(original)
    assertEquals(
      SclsDRepMapper.overlay(empty, rows.iterator),
      Left(SclsDRepMapper.Error.UnexpectedCredential(credential))
    )
    assertEquals(SclsDRepMapper.overlay(empty, Iterator.empty), Right(empty))
  }
