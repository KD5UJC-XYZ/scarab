// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.haskell.state.ReferenceLayout
import io.circe.{ACursor, Decoder, HCursor, Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.state.{EraLedgerState, MirrorState}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import xyz.kd5ujc.scarab.storage.{FileLedgerSnapshots, SnapshotV10}
import xyz.kd5ujc.scarab.ledger.byron.{
  AppVersion,
  ByronParams,
  ByronVersion,
  CandidateUpdate,
  ProtocolProposal,
  SoftforkRule,
  SoftwareProposal,
  TxFeePolicy,
  UpdateState
}

/** Byron's `GetUpdateInterfaceState` is answered with `toByronCBOR` of `UPI.State` (#726).
  *
  * `fixtures/reference/byron-update-state/cases.jsonl` holds states described field by field, each
  * with the encoding the released `cardano-ledger-byron` 1.3.0.0 writes for it (oracle
  * `scripts/oracle/byron-update-state`): the initial states of the repository's Byron genesis
  * files, and states with candidates, application versions, protocol and software proposals and
  * their installer metadata, confirmations, votes, endorsements and registration slots, keys out of
  * order and values at the bounds of their types. The node's encoding of each state must be the
  * reference's, byte for byte. The query is answered so while the acquired state is in Byron, and
  * with the era mismatch in a later era.
  */
class ByronUpdateInterfaceSuite extends FunSuite:
  private final case class Row(name: String, state: Json, cbor: String)

  private given Decoder[Row] = Decoder.forProduct3("name", "state", "cbor")(Row.apply)

  private val rows: Vector[Row] =
    Files
      .readString(Path.of("fixtures/reference/byron-update-state/cases.jsonl"))
      .linesIterator
      .filter(_.nonEmpty)
      .map(l => parser.decode[Row](l).fold(e => fail(e.getMessage), identity))
      .toVector

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  private def field[A: Decoder](c: ACursor, name: String): A =
    c.downField(name).as[A].fold(e => fail(s"$name: ${e.getMessage}"), identity)

  private def list(c: ACursor, name: String): Vector[HCursor] =
    field[Vector[Json]](c, name).map(_.hcursor)

  private def version(c: ACursor, name: String): ByronVersion =
    field[Vector[BigInt]](c, name) match
      case Vector(a, b, d) => ByronVersion.from(a, b, d).fold(e => fail(e.message), identity)
      case other           => fail(s"$name: $other")

  private def params(c: ACursor): ByronParams =
    def n(name: String) = field[BigInt](c, name)
    val rule = field[Vector[BigInt]](c, "softforkRule")
    ByronParams(
      n("scriptVersion"),
      n("slotDuration"),
      n("maxBlockSize"),
      n("maxHeaderSize"),
      n("maxTxSize"),
      n("maxProposalSize"),
      n("mpcThd"),
      n("heavyDelThd"),
      n("updateVoteThd"),
      n("updateProposalThd"),
      n("updateProposalTtl"),
      SoftforkRule(rule(0), rule(1), rule(2)),
      TxFeePolicy(n("summand") * BigInt(1000000000), n("multiplier")),
      n("unlockStakeEpoch")
    )

  private def metadata(c: ACursor): Map[String, Bytes] =
    list(c, "metadata").map(e => field[String](e, "tag") -> hex(field[String](e, "hash"))).toMap

  private def slots(c: ACursor, name: String): Map[Bytes, BigInt] =
    list(c, name).map(e => hex(field[String](e, "id")) -> field[BigInt](e, "slot")).toMap

  private def state(json: Json): UpdateState =
    val c = json.hcursor
    UpdateState(
      version(c, "adoptedVersion"),
      params(c.downField("adoptedParameters")),
      list(c, "candidates").map(e =>
        CandidateUpdate(
          field[BigInt](e, "slot"),
          version(e, "version"),
          params(e.downField("parameters"))
        )
      ),
      list(c, "appVersions")
        .map(e =>
          field[String](e, "name") ->
            AppVersion(field[BigInt](e, "number"), field[BigInt](e, "slot"), metadata(e))
        )
        .toMap,
      list(c, "protocolProposals")
        .map(e =>
          hex(field[String](e, "id")) ->
            ProtocolProposal(version(e, "version"), params(e.downField("parameters")))
        )
        .toMap,
      list(c, "softwareProposals")
        .map(e =>
          hex(field[String](e, "id")) ->
            SoftwareProposal(field[String](e, "name"), field[BigInt](e, "number"), metadata(e))
        )
        .toMap,
      slots(c, "confirmed"),
      list(c, "votes")
        .map(e => hex(field[String](e, "id")) -> field[Vector[String]](e, "voters").map(hex).toSet)
        .toMap,
      list(c, "endorsements").map(e => version(e, "version") -> hex(field[String](e, "key"))).toSet,
      slots(c, "registrationSlots")
    )

  test("the corpus holds initial and populated states, with metadata, votes and endorsements") {
    assertEquals(rows.map(_.name).distinct.size, rows.size)
    val states = rows.map(r => state(r.state))
    assert(rows.count(_.name.startsWith("initial-")) >= 4)
    assert(states.exists(_.appVersions.values.exists(_.metadata.nonEmpty)))
    assert(states.exists(_.registeredSoftware.values.exists(_.metadata.nonEmpty)))
    assert(states.exists(_.votes.nonEmpty) && states.exists(_.endorsements.nonEmpty))
    assert(states.exists(_.candidates.size > 1))
  }

  rows.foreach { row =>
    test(s"${row.name}: the reference's bytes") {
      assertEquals(ByronUpdateInterface.encode(state(row.state)).map(_.hex), Right(row.cbor))
    }
  }

  test("a value outside the range of its Haskell type is not written") {
    val initial = state(rows.head.state)
    def unwritable(s: UpdateState) =
      assertEquals(ByronUpdateInterface.encode(s), Left(Unanswered.Unwritable))
    unwritable(initial.copy(confirmed = Map(Bytes(Vector.fill(32)(1)) -> (BigInt(1) << 64))))
    unwritable(initial.copy(confirmed = Map(Bytes(Vector.fill(31)(1)) -> BigInt(1))))
    unwritable(
      initial.copy(appVersions = Map("a" -> AppVersion(BigInt(1) << 32, 0, Map.empty)))
    )
    unwritable(initial.copy(adoptedParams = initial.adoptedParams.copy(maxBlockSize = -1)))
    unwritable(
      initial.copy(adoptedParams =
        initial.adoptedParams.copy(txFeePolicy = TxFeePolicy(BigInt(1), BigInt(0)))
      )
    )
  }

  // ---- the query

  private val setup =
    NodeSetup
      .fromDirectory(ReferenceBuild.Default)(Path.of("fixtures/eras/genesis"))
      .fold(e => fail(e.message), identity)

  private val env = QueryEnv(
    setup.byronParams
      .map(_.startTime)
      .fold(setup.geometry.systemStart)(java.time.Instant.ofEpochSecond),
    setup.geometry,
    setup.praos.forks,
    setup.byronParams.fold(EraHistory.ByronParams(50, 1000, 5))(b =>
      EraHistory.ByronParams(b.epochSlots, b.slotLengthMillis, b.securityParam)
    ),
    setup.globals.maxSupply,
    setup.genesis.raw,
    setup.mirrorEnv().ledger,
    networkMagic = setup.genesis.networkMagic.toLong
  )

  private def answer(state: MirrorState): Either[Unanswered, String] =
    QueryAnswers
      .answerOrWhy(
        env,
        QueryView(Point.Origin, None, state),
        Map.empty,
        NtcQuery.ByronUpdateInterfaceState
      )
      .map(_.hex)

  /** The state of a V9 fixture of the eras chain. */
  private def fixture(name: String): MirrorState =
    val content = FileLedgerSnapshots
      .verified(Files.readAllBytes(Path.of("fixtures/snapshot-v10/eras").resolve(name)))
      .fold(e => fail(e.message), identity)
    SnapshotV10
      .decode(setup.mirrorEnv(), ReferenceLayout.Release11_1, content)
      .fold(e => fail(e.message), _.state)

  private def update(state: MirrorState): UpdateState = state.ext.ledgerState.state match
    case EraLedgerState.ByronCurrent(b) => b.ledger.map(_.update).getOrElse(fail("no ledger"))
    case _                              => fail("not a Byron state")

  test("in Byron at genesis the answer is the reference's encoding of the genesis state") {
    // `initial-eras-devnet` is the reference's `initialState` of this genesis's parameters
    val reference = rows.find(_.name == "initial-eras-devnet").getOrElse(fail("no initial case"))
    assertEquals(answer(setup.mirrorStart), Right("81" + reference.cbor))
  }

  test("in Byron after its blocks the answer is the acquired state's update state") {
    for name <- Vector("byron-1.bin", "byron-25.bin", "byron-49.bin") do
      val state = fixture(name)
      val encoded = ByronUpdateInterface.encode(update(state)).fold(e => fail(e.toString), _.hex)
      assertEquals(answer(state), Right("81" + encoded), name)
  }

  test("past Byron the answer is the era mismatch") {
    def text(t: String): String =
      f"${0x60 | t.length}%02x" + t.getBytes("UTF-8").map(b => f"$b%02x").mkString
    for (name, era, label) <- Vector(
        ("shelley-51.bin", 1, "Shelley"),
        ("conway-730.bin", 6, "Conway")
      )
    do
      assertEquals(
        answer(fixture(name)),
        Right("8282" + "00" + text("Byron") + "82" + f"$era%02x" + text(label)),
        name
      )
  }

  test("a Byron state without its ledger has no update state to answer with") {
    val state = fixture("byron-25.bin")
    val bare = state.ext.ledgerState.state match
      case EraLedgerState.ByronCurrent(b) =>
        state.copy(ext =
          state.ext.copy(ledgerState =
            state.ext.ledgerState.withState(EraLedgerState.ByronCurrent(b.copy(ledger = None)))
          )
        )
      case _ => fail("not a Byron state")
    assertEquals(answer(bare), Left(Unanswered.Unsupported))
  }
