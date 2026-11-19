// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.effect.IO
import cats.mtl.Ask
import cats.syntax.all.*
import java.nio.file.Path as JPath
import munit.CatsEffectSuite
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.consensus.state.{
  ConsensusMirrorEnv,
  EraLedgerState,
  ExtLedgerState,
  MirrorState
}
import xyz.kd5ujc.scarab.ledger.{
  FileKind,
  FormatVersion,
  LedgerStateSource,
  StorageError,
  UnreachableStateProblem
}
import xyz.kd5ujc.scarab.ledger.rules.HardFork
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.node.{NativeSnapshots, NodeSetup}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellLedgerFile, ReferenceLayout}

import LedgerSnapshots.Fallback
import StorageTestKit.*

/** A restored or imported ledger state below protocol version 11 whose pools' VRF key hashes are
  * not empty is one no chain of 11.1.3 reaches (cardano-ledger f649f975: POOL writes the map only
  * from 11, `Shelley/Era.hs:259-262`; HARDFORK first fills it on entering 11,
  * `Conway/Rules/HardFork.hs:77-78`), and one the retired flat conversion wrote, deriving the map
  * from 10. The 11.1 HARDFORK adds to the map it has (`HardFork.hs:107-125`), so such a state would
  * count a pool twice on entering 11: the 11.1 build refuses it where it is restored, a clean one
  * restores and enters 11 with the reference's count. The 11.2 and Leios builds recompute the map
  * on entering 11 and admit it.
  */
class StateAdmissionSuite extends CatsEffectSuite:
  private val env: ConsensusMirrorEnv = NodeSetup
    .fromDirectory(ReferenceBuild.Default)(JPath.of("fixtures/eras/genesis"))
    .fold(e => fail(e.message), _.mirrorEnv())
  private given Ask[IO, ConsensusMirrorEnv] = Ask.const(env)

  /** The current native fixture of a Conway chain, read in the current format. */
  private val fixture: IO[StateSnapshot[MirrorState]] =
    NativeSnapshots
      .read[IO](JPath.of("fixtures/snapshot-v10/conway-native-730.bin"), env)
      .map(_.fold(e => fail(e.message), identity))

  private def nesOf(m: MirrorState): NewEpochState[Conway] =
    m.ext.ledgerState.state match
      case EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, s) => s.nes
      case other => fail(s"not Conway: ${other.era}")

  private def onConwayExt(ext: ExtLedgerState)(
      f: NewEpochState[Conway] => NewEpochState[Conway]
  ): ExtLedgerState =
    ext.ledgerState.state match
      case EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, s) =>
        ext.copy(ledgerState =
          ext.ledgerState.withState(
            EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, s.copy(nes = f(s.nes)))
          )
        )
      case other => fail(s"not Conway: ${other.era}")

  private def onConway(m: MirrorState)(f: NewEpochState[Conway] => NewEpochState[Conway]) =
    m.copy(ext = onConwayExt(m.ext)(f))

  private def withPools(nes: NewEpochState[Conway], ps: PState): NewEpochState[Conway] =
    nes.copy(es = nes.es.copy(ls = nes.es.ls.copy[Conway](cert = nes.es.ls.cert.copy(pState = ps))))

  /** `m` at protocol version `major`.0, with `counts` as its pools' VRF key hashes. */
  private def at(
      m: MirrorState,
      major: Int,
      counts: SortedMap[VRFVerKeyHash, NonZero[Word64]]
  ): MirrorState =
    onConway(m) { nes =>
      val gov = nes.es.ls.utxo.gov
      val versioned = nes.copy(es =
        nes.es.copy(ls =
          nes.es.ls.copy[Conway](utxo =
            nes.es.ls.utxo.copy(gov =
              gov.copy(curPParams =
                gov.curPParams.copy(protocolMajor = BigInt(major), protocolMinor = 0)
              )
            )
          )
        )
      )
      withPools(versioned, versioned.es.ls.cert.pState.copy(vrfKeyHashes = counts))
    }

  /** One count of each registered pool's VRF key hash: the map the retired conversion derived. */
  private def derived(m: MirrorState): SortedMap[VRFVerKeyHash, NonZero[Word64]] =
    nesOf(m).es.ls.cert.pState.stakePools.valuesIterator
      .map(_.vrf)
      .foldLeft(Sorted.empty[VRFVerKeyHash, NonZero[Word64]])((m, k) =>
        m.updated(k, NonZero.oneWord)
      )

  private val empty = Sorted.empty[VRFVerKeyHash, NonZero[Word64]]

  /** `state` written as the node writes a snapshot and restored under `build` 's admission, as the
    * node's start restores one (`Main`: the V10 format of the 11.1 layout, admitting).
    */
  private def restored(
      build: ReferenceBuild,
      written: StateSnapshot[MirrorState]
  ): IO[Either[StorageError, Option[MirrorState]]] =
    given SnapshotFormat[IO, MirrorState] =
      SnapshotV10
        .format[IO](SnapshotCheck.Structure, ReferenceLayout.Release11_1)
        .admitting(StateAdmission.restored(build))
    tempDir.use { dir =>
      attempt {
        for
          snapshots <- FileLedgerSnapshots.open[IO, MirrorState](dir)
          wrote <- snapshots.write(written)
          _ = assertEquals(wrote.map(_.name), Right(fileOf(written).toString), "written")
          back <- snapshots.restore(_ => IO.pure(Right(())))
        yield back.snapshot.map(_._2.state)
      }
    }

  /** The file the node writes `snapshot` under, which the refusal names. */
  private def fileOf(snapshot: StateSnapshot[MirrorState]): JPath =
    JPath.of(FileLedgerSnapshots.nameOf(snapshot.anchor.getOrElse(fail("no anchor"))))

  /** The counts HARDFORK of `build` leaves on entering 11 from `m`. */
  private def entered(
      build: ReferenceBuild,
      m: MirrorState
  ): SortedMap[VRFVerKeyHash, NonZero[Word64]] =
    HardFork(build, nesOf(m).es, 11).fold(e => fail(e.toString), _.ls.cert.pState.vrfKeyHashes)

  test("under 11.1 a restored state at 10 whose VRF key hashes are not empty is refused") {
    fixture.flatMap { snapshot =>
      val contaminated = at(snapshot.state, 10, derived(snapshot.state))
      val entries = derived(snapshot.state).size
      assert(entries > 0, "the fixture registers no pool")
      restored(ReferenceBuild.Release11_1, snapshot.copy(state = contaminated)).map { result =>
        val refusal = StorageError.UnreachableLedgerState(
          LedgerStateSource.Snapshot(Some(fileOf(snapshot))),
          ReferenceBuild.Release11_1,
          UnreachableStateProblem.VRFKeyHashesBeforeVersion11(BigInt(10), entries)
        )
        assertEquals(result, Left(refusal))
        // the restore stops the start on it rather than fall back to an older one
        assertEquals(Fallback.of(refusal), Fallback.Stop)
        // why: the 11.1 HARDFORK would count each pool a second time on entering 11
        derived(snapshot.state).keysIterator.foreach(k =>
          assertEquals(
            entered(ReferenceBuild.Release11_1, contaminated).get(k).map(_.value.bits),
            Some(2L)
          )
        )
      }
    }
  }

  test("under 11.1 a clean state at 10 restores and enters 11 with one count for each pool") {
    fixture.flatMap { snapshot =>
      val clean = at(snapshot.state, 10, empty)
      restored(ReferenceBuild.Release11_1, snapshot.copy(state = clean)).map { result =>
        val back = result.fold(e => fail(e.message), _.getOrElse(fail("nothing restored")))
        assertEquals(nesOf(back).es.ls.cert.pState.vrfKeyHashes, empty)
        val counts = entered(ReferenceBuild.Release11_1, back)
        val pools = nesOf(back).es.ls.cert.pState
        // the reference's result from its empty map: one count for each pool that uses the key,
        // registered or waiting to re-register (`HardFork.hs:107-125`, cardano-ledger f649f975)
        val expected = (pools.stakePools.valuesIterator.map(_.vrf) ++
          pools.future.valuesIterator.flatMap(p =>
            VRFVerKeyHash.fromBytes(p.vrfKeyHash).toOption
          )).toVector
          .groupMapReduce(identity)(_ => 1L)(_ + _)
        assertEquals(counts.view.mapValues(_.value.bits).toMap, expected)
        assert(
          pools.stakePools.valuesIterator.exists(p =>
            counts.get(p.vrf).map(_.value.bits) == Some(1L)
          ),
          "no registered pool counted once"
        )
      }
    }
  }

  test("a state at 11 or later is admitted whatever its VRF key hashes hold, in every build") {
    fixture.flatMap { snapshot =>
      Vector(11, 12).traverse_ { major =>
        val later = at(snapshot.state, major, derived(snapshot.state))
        ReferenceBuild.values.foreach(build =>
          assertEquals(
            StateAdmission.admit(build, later.ext.ledgerState.state),
            Right(()),
            s"${build.name} at $major"
          )
        )
        restored(ReferenceBuild.Release11_1, snapshot.copy(state = later)).map(result =>
          assertEquals(
            result.map(_.map(nesOf(_).es.ls.cert.pState.vrfKeyHashes)),
            Right(Some(derived(snapshot.state))),
            s"restored at $major"
          )
        )
      }
    }
  }

  test("11.2 and the Leios build admit a state at 10 with VRF key hashes, which they recompute") {
    fixture.map { snapshot =>
      val contaminated = at(snapshot.state, 10, derived(snapshot.state))
      val clean = at(snapshot.state, 10, empty)
      val state = contaminated.ext.ledgerState.state
      assertEquals(
        StateAdmission.admit(ReferenceBuild.Release11_1, state),
        Left(
          UnreachableStateProblem.VRFKeyHashesBeforeVersion11(
            BigInt(10),
            derived(snapshot.state).size
          )
        )
      )
      Vector(ReferenceBuild.Release11_2, ReferenceBuild.LeiosB004fe0c).foreach { build =>
        assertEquals(StateAdmission.admit(build, state), Right(()), build.name)
        // their HARDFORK counts from nothing, so it reaches the reference's counts either way
        assertEquals(entered(build, contaminated), entered(build, clean), build.name)
        assertEquals(entered(build, clean), entered(ReferenceBuild.Release11_1, clean), build.name)
      }
      // a clean state is admitted by every build
      ReferenceBuild.values.foreach(build =>
        assertEquals(
          StateAdmission.admit(build, clean.ext.ledgerState.state),
          Right(()),
          build.name
        )
      )
    }
  }

  test(
    "under 11.1 a V5 snapshot of a state below 11 with VRF key hashes is refused after its migration"
  ) {
    // the retired flat conversion wrote V5 and V7 files; the registered migration keeps their
    // Haskell bytes (`SnapshotMigration.converted`), and the restore admits the state they hold.
    // The committed V5 file's Haskell section is read and written again with the map set, and
    // framed as a V5 file in memory; no fixture changes.
    val v5 = FileLedgerSnapshots
      .verified(
        java.nio.file.Files.readAllBytes(JPath.of("fixtures/snapshot-v5/conway-native-730.bin"))
      )
      .fold(e => fail(e.message), identity)
    val (sidecar, haskell) = SnapshotV5.sections(v5).fold(e => fail(e.message), identity)
    val ext = ReferenceLayout.Release11_1
      .decodeNative(haskell)
      .fold(e => fail(e.toString), identity)
      .ext
      .getOrElse(fail("a Byron fixture"))
    val pools = ext.ledgerState.state match
      case EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, s) => s.nes.es.ls.cert.pState
      case other => fail(s"not Conway: ${other.era}")
    val major = ext.ledgerState.state match
      case EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, s) =>
        s.nes.es.ls.utxo.gov.curPParams.protocolMajor
      case other => fail(s"not Conway: ${other.era}")
    val counts = pools.stakePools.valuesIterator
      .map(_.vrf)
      .foldLeft(empty)((m, k) => m.updated(k, NonZero.oneWord))
    val name = FileLedgerSnapshots.nameOf(sidecar.anchor.getOrElse(fail("no anchor")))

    /** The V5 file of the fixture with `held` as its pools' VRF key hashes. */
    def v5With(held: SortedMap[VRFVerKeyHash, NonZero[Word64]]): Array[Byte] =
      val edited = onConwayExt(ext)(nes => withPools(nes, pools.copy(vrfKeyHashes = held)))
      val file = HaskellLedgerFile.of(edited).fold(e => fail(e.toString), identity)
      val bytes = ReferenceLayout.Release11_1.encode(file).fold(e => fail(e.toString), identity)
      FileLedgerSnapshots.closed(SnapshotV5.framed(sidecar, bytes))

    /** The file migrated as the node's start migrates it, then restored as it restores one. */
    def migratedAndRestored(
        file: Array[Byte]
    ): IO[(SnapshotMigration.Report, Either[StorageError, Option[MirrorState]])] =
      given SnapshotFormat[IO, MirrorState] =
        SnapshotV10
          .format[IO](SnapshotCheck.Structure, ReferenceLayout.Release11_1)
          .admitting(StateAdmission.restored(ReferenceBuild.Release11_1))
      tempDir.use { dir =>
        for
          _ <- IO.blocking(java.nio.file.Files.write((dir / name).toNioPath, file))
          report <- ok(SnapshotMigration.toCurrent[IO].run(dir))
          back <- attempt(
            FileLedgerSnapshots
              .open[IO, MirrorState](dir)
              .flatMap(_.restore(_ => IO.pure(Right(()))))
              .map(_.snapshot.map(_._2.state))
          )
        yield (report, back)
      }

    assert(major < 11, s"the fixture is at protocol version $major")
    assert(counts.nonEmpty, "the fixture registers no pool")
    val migrated =
      List(Migrated(FileKind.SnapshotFile, FormatVersion.V5, FormatVersion.V10, 1))
    for
      contaminated <- migratedAndRestored(v5With(counts))
      clean <- migratedAndRestored(v5With(empty))
    yield
      assertEquals(contaminated._1.migrated, migrated)
      assertEquals(
        contaminated._2.map(_.isDefined),
        Left(
          StorageError.UnreachableLedgerState(
            LedgerStateSource.Snapshot(Some(JPath.of(name))),
            ReferenceBuild.Release11_1,
            UnreachableStateProblem.VRFKeyHashesBeforeVersion11(major, counts.size)
          )
        )
      )
      assertEquals(clean._1.migrated, migrated)
      assertEquals(clean._2.map(_.map(nesOf(_).es.ls.cert.pState.vrfKeyHashes)), Right(Some(empty)))
  }
