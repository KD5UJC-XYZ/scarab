// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorState}
import xyz.kd5ujc.scarab.ledger.{LedgerStateSource, StorageError, UnreachableStateProblem}
import xyz.kd5ujc.scarab.ledger.rules.EraLedger
import xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** What a ledger state that enters the node from storage or an import must be besides decoding: not
  * a state that no chain of the configured reference build reaches and that the build's rules would
  * carry to another state than the reference's ([[StorageError.UnreachableLedgerState]]). The
  * reference has no such check (it decodes `PState` as it is, cardano-ledger f649f975
  * `State/CertState.hs:257-265`), and needs none: no chain it runs reaches such a state. The node's
  * snapshots go through it as they are restored ([[restored]]), whatever version they were written
  * in and migrated from; `import-ledger-snapshot` (and the Mithril bootstrap, which runs it) as it
  * reads the reference's `state` file ([[LedgerSnapshotImport]]); and the SCLS import as it
  * rebuilds a state on its base snapshot ([[scls.SclsImport]]), all before anything is published.
  *
  * Refusing such a state, rather than repairing it in a migration step or at the hard fork, is the
  * owner's decision (asked 2026-11-18T08:22:34Z and decided 2026-11-18T14:21:46Z); the operator's
  * side is in `docs/running/snapshots.md`.
  */
object StateAdmission:
  /** Whether `state` is admitted under `build`, or what makes it a state no chain of `build`
    * reaches that its rules would carry elsewhere.
    */
  def admit(build: ReferenceBuild, state: EraLedgerState): Either[UnreachableStateProblem, Unit] =
    build match
      // HARDFORK adds the pools to the map it has on entering 11 (cardano-ledger f649f975
      // `Conway/Rules/HardFork.hs:107-125`), so a map the reference cannot hold below 11 would
      // count each pool a second time there
      case ReferenceBuild.Release11_1 => vrfKeyHashesEmptyBefore11(state)
      // HARDFORK recomputes the map from nothing on entering 11 (cardano-ledger 29a7d656
      // `Conway/Rules/HardFork.hs:74-75`, `State/CertState.hs:525-535`) and POOL reads it only
      // from 11 (`Shelley/Era.hs:259-262`), so the rules take such a state to the reference's at
      // the fork; no layout of 11.2 is read yet, so no state is restored or imported under it
      case ReferenceBuild.Release11_2 => Right(())
      // as 11.2 (cardano-ledger 9df9f98c: the same HARDFORK and POOL gate, `populateVRFKeyHashes`
      // at `State/CertState.hs:528-538`); its snapshots are of its own layout, which no retired
      // conversion wrote, and its rules do not run yet
      case ReferenceBuild.Leios => Right(())

  /** [[admit]] of a restored snapshot, as the snapshot format refuses it, named by the file the
    * node writes the snapshot of its anchor under.
    */
  def restored(build: ReferenceBuild)(
      snapshot: StateSnapshot[MirrorState]
  ): Either[StorageError, Unit] =
    val source = LedgerStateSource.Snapshot(
      snapshot.anchor.map(a => java.nio.file.Path.of(FileLedgerSnapshots.nameOf(a)))
    )
    of(build, source, snapshot.state)

  /** [[admit]] of the state of `mirror`, from `source`, as a [[StorageError]]. */
  def of(
      build: ReferenceBuild,
      source: LedgerStateSource,
      mirror: MirrorState
  ): Either[StorageError, Unit] =
    admit(build, mirror.ext.ledgerState.state)
      .leftMap(StorageError.UnreachableLedgerState(source, build, _))

  /** The map of VRF key hashes is empty below protocol version 11, as the reference's is in every
    * state of 11.1.3: the genesis state has none (`State/CertState.hs:423-424`), POOL writes it
    * only from 11 (`Shelley/Rules/Pool.hs:262-306`, `Shelley/Era.hs:259-262`), HARDFORK first on
    * entering 11 (`Conway/Rules/HardFork.hs:77-78`), POOLREAP only takes from it
    * (`Shelley/Rules/PoolReap.hs:222-237`), each era's translation keeps it as it is
    * (`Conway/Translation.hs:146` and the earlier eras'), and the protocol version never goes down
    * (`pvCanFollow`, `Shelley/PParams.hs:299-307`), all at cardano-ledger f649f975.
    */
  private def vrfKeyHashesEmptyBefore11(
      state: EraLedgerState
  ): Either[UnreachableStateProblem, Unit] =
    state match
      case EraLedgerState.ByronCurrent(_) => Right(())
      case i: EraLedgerState.InShelleyEra[e] =>
        given ShelleyBasedEra[e] = i.era
        val ls = i.state.nes.es.ls
        val major = EraLedger.curPParams(ls.utxo.gov).protocolMajor
        val entries = EraLedger.pState(ls).vrfKeyHashes.size
        Either.cond(
          major >= 11 || entries == 0,
          (),
          UnreachableStateProblem.VRFKeyHashesBeforeVersion11(major, entries)
        )
