// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.{Assertions, Location}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.era.UtxoRecordings
import xyz.kd5ujc.scarab.ledger.era.rules.{
  CertsPredFailure,
  ConwayLedgerPredFailure,
  ConwayUtxoPredFailure,
  ConwayUtxowPredFailure
}
import xyz.kd5ujc.scarab.ledger.state.{
  Conway,
  ConwayCertState,
  LedgerState,
  PState,
  ProtVer,
  UTxOState,
  ShelleyBasedEra,
  Word64
}
import xyz.kd5ujc.scarab.protocol.{ReferenceBuild, StateLayout}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellLedgerFile, HaskellState, LedgerFileCodecs}

/** The Conway refusals the flat rules' encoder wrote before it was deleted, and the judgments of
  * the flat LEDGER, LEDGERS, GOV and CERTS, recorded for the suites that hold Conway's era encoder
  * and rules to them (`fixtures/conway-flat-refusals`, #85).
  */
object FlatRecordings:
  private val Dir = Path.of("fixtures/conway-flat-refusals")

  /** The first 16 hex digits of the SHA-256 of `bytes`, the recordings' digest. */
  def digest(bytes: Array[Byte]): String =
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .take(8)
      .map(b => f"${b & 0xff}%02x")
      .mkString

  def digest(bytes: Bytes): String = digest(bytes.toArray)

  private def lines(name: String): Vector[io.circe.Json] =
    Files
      .readAllLines(Dir.resolve(name))
      .asScala
      .toVector
      .map(l => io.circe.parser.parse(l).fold(e => Assertions.fail(s"$name: $e"), identity))

  private def field(name: String, j: io.circe.Json, key: String): String =
    j.hcursor.get[String](key).fold(e => Assertions.fail(s"$name: $key, $e"), identity)

  /** The records of `name` by their field `key`, failing the test where a key occurs twice. */
  private def keyed(name: String, key: String)(
      value: io.circe.Json => String
  ): Map[String, String] =
    val rows = lines(name).map(j => field(name, j, key) -> value(j))
    val ids = rows.map(_._1)
    val twice = ids.diff(ids.distinct).distinct
    if twice.nonEmpty then Assertions.fail(s"$name: $key more than once: $twice")
    rows.toMap

  /** `ConwayLedgerChecksSuite` 's judgments of each case: the digest of each refusal's bytes, `-`
    * for an admission, in the recording's order.
    */
  lazy val ledgerChecks: Map[String, Vector[String]] =
    keyed("ledger-checks.jsonl", "case")(field("ledger-checks.jsonl", _, "r"))
      .map((k, v) => k -> v.split(' ').toVector)

  /** `ConwayMempoolRunSuite` 's root judgments of each corpus case: `R:` and the digest of a
    * refusal's bytes, or `A:` and that of the ledger state an admission leaves, in the recording's
    * order.
    */
  lazy val mempoolRun: Map[String, Vector[String]] =
    keyed("mempool-run.jsonl", "case")(field("mempool-run.jsonl", _, "r"))
      .map((k, v) => k -> v.split(' ').toVector)

  /** `ConwayFailureCborSuite` 's cases the flat encoder wrote: each case's id to the digest of its
    * bytes.
    */
  lazy val failureConstructors: Map[String, String] =
    keyed("failure-constructors.jsonl", "id")(field("failure-constructors.jsonl", _, "bytes"))

  /** The digest of the bytes the flat encoder wrote for the case `id`, failing the test where none
    * was recorded.
    */
  def wrote(id: String): String =
    failureConstructors.getOrElse(id, Assertions.fail(s"no recording of $id"))

  /** The judgments of Conway's era LEDGER and LEDGERS that the flat ones gave the same of, in the
    * suites that compared them (`ledger-judgments.jsonl`): each id to its cells ([[refused]],
    * [[admitted]]), separated by spaces.
    */
  lazy val ledgerJudgments: Map[String, Vector[String]] =
    keyed("ledger-judgments.jsonl", "id")(field("ledger-judgments.jsonl", _, "r"))
      .map((k, v) => k -> v.split(' ').toVector)

  /** The recorded judgments whose ids start with `prefix`, failing the test where none is. */
  def judgments(prefix: String): Map[String, Vector[String]] =
    val found = ledgerJudgments.filter((id, _) => id.startsWith(prefix))
    if found.isEmpty then Assertions.fail(s"no recording under $prefix")
    found

  /** The judgments of Conway's era GOV and its predicates that the flat GOV gave the same of, in
    * `ConwayGovChecksSuite` (`gov-judgments.jsonl`): each id to its cells, separated by spaces.
    */
  lazy val govJudgments: Map[String, Vector[String]] =
    keyed("gov-judgments.jsonl", "id")(field("gov-judgments.jsonl", _, "r"))
      .map((k, v) => k -> v.split(' ').toVector)

  /** The judgments of Conway's era CERTS that the flat CERTS gave the same of, in
    * `ConwayCertsChecksSuite` (`certs-judgments.jsonl`): each id to its cells, separated by spaces.
    */
  lazy val certsJudgments: Map[String, Vector[String]] =
    keyed("certs-judgments.jsonl", "id")(field("certs-judgments.jsonl", _, "r"))
      .map((k, v) => k -> v.split(' ').toVector)

  /** The case files `--plutus-cases` writes of the recorded Conway Plutus cases from the era's
    * observation (`ConwayPlutusCasesSuite`, `plutus-case-files.jsonl`): each file, by its set and
    * name, to the digest of its bytes.
    */
  lazy val plutusCaseFiles: Map[String, String] =
    keyed("plutus-case-files.jsonl", "file")(field("plutus-case-files.jsonl", _, "bytes"))

  /** The judgments of Conway's era UTXO, UTXOW and their predicates that the flat ones gave the
    * same of, in `ConwayUtxoChecksSuite`, `ConwayUtxowChecksSuite` and `EraUtxoSuite`
    * (`utxo-judgments.jsonl`): each id to its cells, separated by spaces.
    */
  lazy val utxoJudgments: Map[String, Vector[String]] =
    keyed("utxo-judgments.jsonl", "id")(field("utxo-judgments.jsonl", _, "r"))
      .map((k, v) => k -> v.split(' ').toVector)

  /** A UTXO refusal's cell: `R:` and the digest of the `ApplyTxErr` bytes of its failures in their
    * order, each under LEDGER's UTXOW, as the node sends UTXO's refusal.
    */
  def utxoRefused(failures: Iterable[ConwayUtxoPredFailure[Conway]])(using Location): String =
    utxowRefused(failures.map(ConwayUtxowPredFailure.UtxoFailure[Conway](_)))

  /** A UTXOW refusal's cell: `R:` and the digest of the `ApplyTxErr` bytes of its failures in their
    * order, each under LEDGER's `ConwayUtxowFailure`, as the node sends UTXOW's refusal.
    */
  def utxowRefused(failures: Iterable[ConwayUtxowPredFailure[Conway]])(using Location): String =
    refused(failures.map(ConwayLedgerPredFailure.ConwayUtxowFailure[Conway](_)))

  /** A UTXO or UTXOW admission's cell: `A:` and the digest of the UTxO state it leaves in the form
    * of `UtxoRecordings.state`, which holds the UTxO set whole (its diff and stamps among it), then
    * the counters of the scripts it ran: succeeded, failed, run.
    */
  def utxoAdmitted(state: UTxOState[Conway], counters: (Long, Long, Long))(using
      Location
  ): String =
    val (succeeded, failed, scripts) = counters
    s"${UtxoRecordings.stateCell(state)}/$succeeded/$failed/$scripts"

  /** A CERTS refusal's cell: `R:` and the digest of the `ApplyTxErr` bytes of its failures in their
    * order, each under LEDGER's `ConwayCertsFailure`, as the node sends CERTS's refusal.
    */
  def certsRefused(failures: Iterable[CertsPredFailure[Conway]])(using Location): String =
    refused(failures.map(ConwayLedgerPredFailure.ConwayCertsFailure[Conway](_)))

  /** The certificate state's encoding in the Haskell state's form of `codecs` ' layout at protocol
    * version 9, written as a value of unknown origin is (checked).
    */
  def certsEncoded(state: ConwayCertState, codecs: LedgerFileCodecs)(using Location): Bytes =
    given ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)
    given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
    codecs
      .certState[Conway]
      .write(state)
      .fold(e => Assertions.fail(s"the certificate state: $e"), identity)

  /** The bytes a certificate state keeps as a certificate gave them and its encoding writes in its
    * own form, so that their digest is not the encoding's: each pool's relays and metadata, then
    * each re-registration's waiting parameters', in key order, as a CBOR array of byte strings
    * (`null` for no metadata).
    */
  def certsKept(state: ConwayCertState)(using Location): Bytes =
    def item(b: Bytes): Node = Node(Value.ByteString(b), Bytes.empty)
    def pair(relays: Bytes, metadata: Option[Bytes]): Node =
      Node(
        Value.Arr(Vector(item(relays), metadata.fold(Node(Value.Null, Bytes.empty))(item))),
        Bytes.empty
      )
    val p = state.pState
    val pairs = p.stakePools.values.toVector.map(s => pair(s.relays, s.metadata)) ++
      p.future.values.toVector.map(f => pair(f.relays, f.metadata.map(_.bytes)))
    Cbor
      .encode(Value.Arr(pairs), Cbor.Limits.Unbounded)
      .fold(e => Assertions.fail(s"the kept bytes: $e"), identity)

  /** `state` with the bytes [[certsKept]] holds set aside: no relays and no metadata. */
  def certsWithoutKept(state: ConwayCertState): ConwayCertState =
    ConwayCertState.pState.modify((p: PState) =>
      p.copy(
        stakePools =
          p.stakePools.transform((_, s) => s.copy(relays = Bytes.empty, metadata = None)),
        future = p.future.transform((_, f) => f.copy(relays = Bytes.empty, metadata = None))
      )
    )(state)

  /** Whether the certificate state's encoding ([[certsEncoded]]) holds all of it but the bytes
    * [[certsKept]] holds: read back, it is the state again, those bytes aside.
    */
  def certsHeldWhole(state: ConwayCertState, codecs: LedgerFileCodecs)(using Location): Boolean =
    given ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)
    given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
    codecs
      .certState[Conway]
      .decodeBytes(certsEncoded(state, codecs), HaskellState.limits)
      .exists(back => certsWithoutKept(back) == certsWithoutKept(state))

  /** A CERTS admission's cell: `A:`, the digest of the certificate state it leaves
    * ([[certsEncoded]]), then `/` and the digest of the bytes the state keeps ([[certsKept]]). The
    * encoding is first checked to hold the rest of the state whole ([[certsHeldWhole]]), so that
    * the cell is the state's, as the flat comparison was of the whole state.
    */
  def certsAdmitted(state: ConwayCertState, codecs: LedgerFileCodecs)(using Location): String =
    Assertions.assert(
      certsHeldWhole(state, codecs),
      "the certificate state's encoding does not hold it whole"
    )
    s"A:${digest(certsEncoded(state, codecs))}/${digest(certsKept(state))}"

  /** A refusal's cell: `R:` and the digest of its `ApplyTxErr` bytes, every failure in its order.
    */
  def refused(failures: Iterable[ConwayLedgerPredFailure[Conway]])(using Location): String =
    "R:" + digest(ConwayFailureCborCheck.direct(failures))

  /** The layout an admission's state is written in, for a judgment under `build`: the build's own,
    * and 11.1's for 11.2's, whose layout is not written yet (`ReferenceBuildNotYet.Layout`).
    */
  def layoutOf(build: ReferenceBuild): StateLayout =
    StateLayout.of(build).getOrElse(StateLayout.Node11_1)

  /** An admission's cell: `A:` and the digest of the ledger state it leaves, in the Haskell state's
    * encoding of `layout` at protocol version 9, then the counters of the scripts it ran: those
    * that succeeded, those that failed, and all it ran.
    */
  def admitted(
      state: LedgerState[Conway],
      succeeded: Long,
      failed: Long,
      scripts: Long,
      layout: StateLayout = StateLayout.Node11_1
  )(using Location): String =
    given ProtVer = ProtVer(Word64.fromUnsigned(9), Word64.zero)
    given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway
    val bytes = HaskellLedgerFile
      .codecs(layout)
      .ledgerState[Conway]
      .write(state)
      .fold(e => Assertions.fail(s"the state: $e"), identity)
    s"A:${digest(bytes)}/$succeeded/$failed/$scripts"
