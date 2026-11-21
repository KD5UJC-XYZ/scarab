// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.nio.file.Files
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.EraLedgerState
import xyz.kd5ujc.scarab.ledger.{ConwayParamUpdate, DijkstraKeysUpdate, GovAction, PoolRelays}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellLedgerFile, LedgerFileEra}

import V10BodyCases.{Case, Edits, FutureParams}

/** The golden V10 bodies ([[V10BodyCases]]): each file is what the current codecs write for its
  * case, reads back with the snapshot's reader and writes the same bytes, and holds the values its
  * case put in. The indefinite body reads as the canonical one and writes it. The guard of the
  * storage format pins these files' hashes, so any change to the bytes a V10 body holds, from code
  * it lists or not, fails here or there.
  */
class V10BodyGoldenSuite extends FunSuite:
  private def fixture(name: String): Bytes =
    Bytes.wrap(Files.readAllBytes(V10BodyCases.Directory.resolve(name)))

  private def read(c: Case, bytes: Bytes): HaskellLedgerFile =
    V10BodyCases.layout(c).decodeNative(bytes).fold(e => fail(s"${c.file}: $e"), identity)

  private def written(c: Case, file: HaskellLedgerFile): Bytes =
    V10BodyCases.layout(c).encode(file).fold(e => fail(s"${c.file}: $e"), identity)

  private def conway(c: Case, file: HaskellLedgerFile): NewEpochState[Conway] =
    file.ledger.state match
      case LedgerFileEra.Mirrored(EraLedgerState.InShelleyEra(ShelleyBasedEra.SConway, s)) => s.nes
      case _ => fail(s"${c.file}: not a Conway state")

  Case.values.foreach { c =>
    test(s"${c.file} is what the current codecs write for its case"):
      assertEquals(fixture(c.file), V10BodyCases.body(c))

    test(s"${c.file} reads back with the snapshot's reader and writes the same bytes"):
      val bytes = fixture(c.file)
      assertEquals(written(c, read(c, bytes)), bytes)

    test(s"${c.file} holds the values its case put in"):
      c.edits match
        case Edits.Conway(future)        => conwayValues(c, future)
        case Edits.DijkstraParamProposal => dijkstraValues(c)
  }

  private def conwayValues(c: Case, edits: FutureParams): Unit =
    val state = conway(c, read(c, fixture(c.file)))
    val (id, pool) = ConwayNewEpochState.pools.get(state).headOption.getOrElse(fail("a pool"))
    assertEquals(pool.owners.toVector, V10BodyCases.owners.toVector)
    assert(pool.owners.sizeIs == 2)
    assertEquals((pool.pledge, pool.cost), (V10BodyCases.pledge, V10BodyCases.cost))
    assertEquals(PoolRelays.decodeState(pool.relays), Right(V10BodyCases.relays))
    assertEquals(pool.metadata, Some(V10BodyCases.metadataBytes))
    assertEquals(
      ConwayNewEpochState.es
        .andThen(EpochState.nonMyopic[Conway])
        .get(state)
        .likelihoods
        .get(id)
        .map(_.weights.map(w => java.lang.Float.floatToIntBits(w.float))),
      Some(V10BodyCases.likelihood.weights.map(w => java.lang.Float.floatToIntBits(w.float)))
    )
    val actions = ConwayNewEpochState.ls.andThen(ConwayLedgerState.proposals).get(state).actions
    actions.lastOption.map(_.action) match
      case Some(GovAction.ParameterChange(None, update: ConwayParamUpdate, None)) =>
        assertEquals(update.keys, Set(19, 20, 21))
      case other => fail(s"the proposal: $other")
    assertEquals(actions.lastOption.map(_.poolVotes.size), Some(2))
    val future = ConwayNewEpochState.ls
      .andThen(ConwayLedgerState.pState)
      .andThen(PState.future)
      .get(state)
    edits match
      case FutureParams.Added =>
        val params = future.getOrElse(id, fail("the future parameters"))
        assertNotEquals(params.pledge, params.cost)
        assertEquals(params.owners, V10BodyCases.owners)
        // a URL longer than 64 bytes, which a restore reads at the state's decoder version
        assertEquals(params.metadata.map(_.bytes), Some(V10BodyCases.metadataBytes))
      case FutureParams.Absent => assertEquals(future.get(id), None)

  /** The proposal of Dijkstra's keys, pending in the proposals and the pulser's snapshot and among
    * the actions its ratification enacted, with the update the case put in, whose bytes the body
    * holds as the reference writes them.
    */
  private def dijkstraValues(c: Case): Unit =
    val bytes = fixture(c.file)
    val gov: ConwayGovState = read(c, bytes).ledger.state match
      case LedgerFileEra.Mirrored(EraLedgerState.InShelleyEra(ShelleyBasedEra.SDijkstra, s)) =>
        s.nes.es.ls.utxo.gov
      case _ => fail(s"${c.file}: not a Dijkstra state")
    def update(gas: Option[xyz.kd5ujc.scarab.ledger.GovActionState]) = gas.map(_.action) match
      case Some(GovAction.ParameterChange(None, d: DijkstraKeysUpdate, None)) => d
      case other => fail(s"the proposal: $other")
    val expected = V10BodyCases.dijkstraKeysUpdate
    assertEquals(update(gov.proposals.byId.get(V10BodyCases.pendingId)), expected)
    assertEquals(
      update(gov.drepPulsing.snapshot.proposals.find(_.id == V10BodyCases.pendingId)),
      expected
    )
    assertEquals(
      update(gov.drepPulsing.ratify.enacted.find(_.id == V10BodyCases.enactedId)),
      expected
    )
    assertEquals(expected.update.fields.keySet, Set(0, 34, 36, 37, 38, 47, 53))
    // the update's bytes occur once in each of the three places
    assertEquals(
      bytes.hex
        .sliding(V10BodyCases.dijkstraUpdate.hex.length, 2)
        .count(_ == V10BodyCases.dijkstraUpdate.hex),
      3
    )

  test(s"${V10BodyCases.IndefiniteFile} reads as the canonical body and writes it"):
    val c = Case.Node11_1Conway
    val canonical = fixture(c.file)
    val stored = fixture(V10BodyCases.IndefiniteFile)
    assertEquals(stored, V10BodyCases.indefinite(canonical))
    assert(stored.hex.contains(V10BodyCases.indefiniteUpdate.hex))
    assertEquals(written(c, read(c, stored)), canonical)
