// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.Show
import cats.data.NonEmptyChain
import cats.syntax.all.*
import io.circe.{Decoder, KeyDecoder, parser}
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.annotation.tailrec
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  ConfigError,
  LedgerDecodeError,
  LedgerDefect,
  LedgerFailure,
  PlutusStats,
  PosixClock,
  PredicateFailure,
  Tx,
  TxEnv,
  TxIn,
  TxOut,
  Utxo
}
import xyz.kd5ujc.scarab.ledger.rules.{
  Counted,
  Judgment,
  ShelleyLedger,
  ShelleyLedgerEnv,
  Sts,
  Tick,
  TickFailure
}
import xyz.kd5ujc.scarab.ledger.state.{
  Babbage,
  Conway,
  EraTag,
  NewEpochState,
  ShelleyBasedEra,
  SlotNo,
  UtxoTables
}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.models.runtime.DecodeError
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.mempool.{
  LocalSubmission,
  MempoolEnv,
  MempoolLedger,
  MempoolState,
  Rejection,
  Source
}
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, UtxoCodecs}

/** The reference-refusal corpus (#401, `fixtures/reference/refusals`): transactions the Haskell
  * node refused, each with the complete rejection it sent over LocalTxSubmission (every accumulated
  * predicate failure, in its order), and the ledger state, UTxO and genesis it was judged against.
  * `scripts/devnet/refusalcorpus.py` records them on the devnet; nothing here is written by hand.
  *
  * Each Conway case puts the submitted bytes through Scarab's own path: `LocalSubmission.unwrap`, a
  * mempool whose ledger is the reference's state ticked to the successor of the recorded tip
  * (`MempoolLedger.tick`), `MempoolState.add`, and `LocalSubmission.reasonItem`. The bytes must
  * equal the reference's. A Babbage case goes through the era's LEDGER rule and encoding
  * ([[replayBabbage]]). Apart from the explicitly incomplete text comparisons below, every
  * difference fails its case. A family the manifest requires for an era and no case covers fails
  * that era's coverage test (an incomplete gate is never a pass).
  *
  * The one recorded gap is [[knownGaps]]: the text of a `PlutusFailure` needs the script-context
  * printer of plutus-ledger-api and the error text of plutus-core, which Scarab does not have yet
  * (#491). A case with a gap is compared exactly in everything but that one text, is reported as
  * canceled (incomplete, never passed), and fails once its text matches the reference, so that the
  * entry is removed when the printers land.
  */
class ReferenceRefusalSuite extends FunSuite:
  import ReferenceRefusalSuite.*

  private val root = Path.of("fixtures/reference/refusals")

  private val loaded: Either[CorpusProblem, Loaded] = load(root)

  loaded match
    case Left(problem) =>
      test("the refusal corpus reads")(fail(problem.show))
    case Right(corpus) =>
      test("the refusal corpus reads, every file matching its recorded hash") {
        assert(corpus.corpora.nonEmpty, "the manifest lists no corpus")
        knownGaps.foreach { gap =>
          val named = corpus.corpora.flatMap(_.cases).count(gap.covers)
          assertEquals(named, 1, s"the gap ${gap.era} ${gap.label} (#${gap.issue}) names one case")
        }
      }
      corpus.corpora.foreach { c =>
        c.states.foreach { (file, bytes) =>
          test(s"${c.path}: the reference's state $file reads and writes back byte for byte") {
            c.era match
              case RefusalEra.Conway  => roundTrip[Conway](bytes)
              case RefusalEra.Babbage => roundTrip[Babbage](bytes)
          }
        }
        c.utxos.foreach { (file, bytes) =>
          test(s"${c.path}: the reference's UTxO $file reads and writes back byte for byte") {
            val utxo = UtxoCodecs.utxo.decodeBytes(bytes).fold(e => fail(e.render), identity)
            assertEquals(UtxoCodecs.utxo.write(utxo).map(_.hex), Right(bytes.hex))
          }
        }
        c.cases.foreach { rc =>
          test(s"${c.path}/${rc.label} [${rc.families.mkString(" ")}]") {
            replay(c, rc) match
              case Left(problem) => fail(problem.show)
              case Right(Judgement.Refused(reason)) =>
                knownGaps.find(_.covers(rc)) match
                  case None      => assertEquals(reason.hex, rc.reason.hex, difference(reason, rc))
                  case Some(gap) => checkGap(gap, reason, rc)
              case Right(other) => fail(s"Scarab ${other.show}; the reference refused it")
          }
        }
      }
      corpus.coverage.toVector.sortBy(_._1.ordinal).foreach { (era, required) =>
        test(s"coverage: every required $era family has a reference refusal") {
          val covered =
            corpus.corpora.flatMap(_.cases).filter(_.era == era).flatMap(_.families).toSet
          val missing = required.filterNot(covered)
          assert(
            missing.isEmpty,
            s"incomplete: no $era case covers ${missing.mkString(", ")}"
          )
        }
      }

  private def roundTrip[E <: EraTag](bytes: Bytes)(using ShelleyBasedEra[E]): Unit =
    val nes = HaskellState.decode[E](bytes).fold(e => fail(e.render), identity)
    assertEquals(HaskellState.encode[E](nes).map(_.hex), Right(bytes.hex))

  /** What Scarab answers for one case, from the recorded state. */
  private def replay(c: Corpus, rc: RefusalCase): Either[ReplayProblem, Judgement] = rc.era match
    case RefusalEra.Conway  => replayConway(c, rc)
    case RefusalEra.Babbage => replayBabbage(c, rc)

  /** The recorded state of a case of era `E`, with its UTxO, and the environment of its genesis. */
  private def recorded[E <: EraTag](c: Corpus, rc: RefusalCase)(using
      ShelleyBasedEra[E]
  ): Either[ReplayProblem, Recorded[E]] =
    for
      setup <- NodeSetup
        .fromDirectory(root.resolve(c.path).resolve("genesis"))
        .leftMap(ReplayProblem.Genesis(_))
      state <- c.states.get(rc.state).toRight(ReplayProblem.NoFile(rc.state))
      utxoBytes <- c.utxos.get(rc.utxo).toRight(ReplayProblem.NoFile(rc.utxo))
      nes <- HaskellState.decode[E](state).leftMap(ReplayProblem.State(_))
      utxo <- UtxoCodecs.utxo.decodeBytes(utxoBytes).leftMap(ReplayProblem.Utxo(_))
    yield Recorded(withUtxo(nes, utxo), MempoolEnv(setup.globals, setup.geometry))

  /** A Babbage case through the era's LEDGER rule and its predicate-failure encoding: Scarab's
    * mempool and LocalTxSubmission serve Conway only, so this is the rule and the encoder, not the
    * node-to-client path. The state is ticked to the successor of the tip, as the reference's
    * mempool does, and the transaction judged as the first of a block in that slot.
    */
  private def replayBabbage(c: Corpus, rc: RefusalCase): Either[ReplayProblem, Judgement] =
    for
      at <- recorded[Babbage](c, rc)
      env = at.env
      slot <- SlotNo
        .fromBigInt(rc.tipSlot + 1)
        .leftMap(e => ReplayProblem.Tick(TickFailure.Vocab(e)))
      ticked <- Sts(Tick.sts[Babbage])(env.rules, (), at.nes, slot).leftMap(ReplayProblem.Tick(_))
      tx <- babbageTx(rc.item)
    yield
      val txEnv = TxEnv(
        slot = slot.toBigInt,
        epoch = ticked.epoch.toBigInt,
        network = env.globals.network,
        firstSlotNextEpoch = env.geometry.firstSlot(ticked.epoch.toBigInt + 1),
        stabilityWindow = env.geometry.stabilityWindow,
        txIndex = 0,
        updateQuorum = env.globals.updateQuorum,
        clock = Some(PosixClock.total(env.geometry)),
        evaluator = env.evaluator,
        decoder = env.decoder
      )
      Judgment
        .run(ShelleyLedger.sts[Babbage], Judgment.Policy.Accumulate)(
          env.rules,
          ShelleyLedgerEnv(txEnv, ticked.es.chainAccount),
          Counted(ticked.es.ls, PlutusStats.zero),
          tx
        )
        .fold(
          Judgement.NotRefused(_),
          _.fold(
            failures =>
              PreConwayFailureCbor
                .applyTxError(Era.Babbage, failures.toChain.toVector)
                .fold(Judgement.Unencodable(failures))(Judgement.Refused(_)),
            _ => Judgement.Accepted
          )
        )

  /** The transaction of a Babbage LocalTxSubmission item `[5, #6.24(tx)]`. */
  private def babbageTx(item: Bytes): Either[ReplayProblem, Tx] =
    val inner = Cbor.decode(item).toOption.map(_.value).collect {
      case Value.Arr(Vector(era, payload)) if era.value == Value.UInt(BabbageIndex) =>
        payload.value match
          case Value.Tag(t, bytes) if t == 24 =>
            bytes.value match
              case Value.ByteString(b) => Some(b)
              case _                   => None
          case _ => None
    }
    inner.flatten
      .toRight(ReplayProblem.Item)
      .flatMap(Tx.fromEnvelope(_, Era.Babbage).leftMap(ReplayProblem.Transaction(_)))

  private def replayConway(c: Corpus, rc: RefusalCase): Either[ReplayProblem, Judgement] =
    for
      at <- recorded[Conway](c, rc)
      env = at.env
      ledger <- MempoolLedger.tick(at.nes, Some(rc.tipSlot), env).leftMap(ReplayProblem.Tick(_))
    yield
      val pool = MempoolState.empty(Right(ledger), rc.tipSlot, None)
      LocalSubmission
        .unwrap(rc.item)
        .flatMap(tx => pool.add(tx, env, Source.Local).map(_ => ()))
        .fold(judge, _ => Judgement.Accepted)

  /** Where the two refusals part: the failure counts and the first failure that differs. */
  private def difference(scarab: Bytes, rc: RefusalCase): String =
    val ours = failures(scarab)
    val theirs = failures(rc.reason)
    val first = ours.zip(theirs).indexWhere(_ != _)
    val at = if first >= 0 then first else math.min(ours.size, theirs.size)
    s"Scarab's refusal differs from the reference's: Scarab reports ${ours.size} failure(s), the " +
      s"reference ${theirs.size} (${rc.families.mkString(", ")}); they part at failure ${at + 1}"

  /** A case with a recorded gap: everything the reference sent but the text of its `PlutusFailure`
    * must equal Scarab's, byte for byte; the text itself must still differ, or the gap is stale;
    * and the case is then canceled as incomplete.
    */
  private def checkGap(gap: KnownGap, reason: Bytes, rc: RefusalCase): Unit =
    (plutusFailureText(reason), plutusFailureText(rc.reason)) match
      case (Some(ours), Some(theirs)) =>
        assertEquals(ours.without.hex, theirs.without.hex, difference(reason, rc))
        assertNotEquals(
          ours.text.hex,
          theirs.text.hex,
          s"the text of ${rc.era} ${rc.label} now matches the reference: remove its KnownGap " +
            s"entry (#${gap.issue}), which makes it a full comparison"
        )
        assume(
          false,
          s"incomplete (#${gap.issue}): the PlutusFailure text of ${rc.era} ${rc.label}"
        )
      case _ =>
        fail(s"${rc.era} ${rc.label} (#${gap.issue}) has no single PlutusFailure text to set aside")

  private def judge(rejection: Rejection): Judgement =
    LocalSubmission
      .reasonItem(rejection)
      .fold(Judgement.Unavailable(_), Judgement.Refused(_))

object ReferenceRefusalSuite:
  /** A recorded gap: the case `label` of `era` is refused with the reference's bytes except for the
    * text of its `PlutusFailure`, which needs the printers tracked by `issue`. The entry is removed
    * when that issue lands; [[ReferenceRefusalSuite]] fails the case once the text matches.
    */
  final case class KnownGap(era: RefusalEra, label: String, issue: Int):
    def covers(rc: RefusalCase): Boolean = rc.era == era && rc.label == label

  /** The cases whose `PlutusFailure` text includes plutus-ledger-api's printed script context and
    * plutus-core's evaluation error (#491).
    */
  val knownGaps: Vector[KnownGap] = Vector(
    KnownGap(RefusalEra.Babbage, "utxos-script-fails-flag-true", 491),
    KnownGap(RefusalEra.Conway, "utxos-script-fails-flag-true", 491)
  )

  /** The text item of the one `PlutusFailure` `[1, text, bytes]` of a rejection, and the rejection
    * without its bytes.
    */
  final case class SetAside(text: Bytes, without: Bytes)

  /** The `PlutusFailure` text of `reason`, when it holds exactly one; none otherwise. */
  private def plutusFailureText(reason: Bytes): Option[SetAside] =
    @tailrec def collect(pending: List[Node], found: Vector[Node]): Vector[Node] = pending match
      case Nil => found
      case node :: rest =>
        node.value match
          case Value.Arr(Vector(tag, text, payload))
              if tag.value == Value.UInt(BigInt(1)) &&
                text.value.isInstanceOf[Value.Text] &&
                payload.value.isInstanceOf[Value.ByteString] =>
            collect(rest, found :+ text)
          case Value.Arr(items) => collect(items.toList ::: rest, found)
          case _                => collect(rest, found)
    for
      root <- Cbor.decode(reason).toOption
      text <- Some(collect(List(root), Vector.empty)).collect { case Vector(only) => only }
      from = text.original.unsafeOffset - reason.unsafeOffset
      until = from + text.original.size
      if from >= 0 && until <= reason.size && reason.slice(from, until) == text.original
    yield SetAside(text.original, reason.slice(0, from) ++ reason.slice(until, reason.size))

  /** The failures of a Conway `ApplyTxErr` `[[era, [failure, ...]]]`, as their bytes; none when the
    * item is not one.
    */
  private def failures(reason: Bytes): Vector[Bytes] =
    Cbor.decode(reason).toOption.map(_.value).toVector.flatMap {
      case Value.Arr(Vector(outer)) =>
        outer.value match
          case Value.Arr(Vector(_, list)) =>
            list.value match
              case Value.Arr(fs) => fs.map(_.original)
              case _             => Vector.empty
          case _ => Vector.empty
      case _ => Vector.empty
    }

  /** The eras the manifest can require coverage of. */
  enum RefusalEra:
    case Conway, Babbage

  object RefusalEra:
    private val byName = values.map(e => e.toString.toLowerCase -> e).toMap
    given Decoder[RefusalEra] = Decoder.decodeString.emap(n => byName.get(n).toRight(s"era $n"))
    given KeyDecoder[RefusalEra] = KeyDecoder.instance(byName.get)

  /** The rule a predicate failure belongs to, as `refusalcorpus.family` reads it from the
    * reference's constructor tags.
    */
  enum Family:
    case UTXO, UTXOW, UTXOS, LEDGER, CERTS, DELEGS, DELEG, POOL, GOVCERT, GOV, MEMPOOL

  object Family:
    private val byName = values.map(f => f.toString -> f).toMap
    given Decoder[Family] = Decoder.decodeString.emap(n => byName.get(n).toRight(s"family $n"))

  /** The reference's state with its UTxO, which UTxO-HD keeps out of `DebugNewEpochState`. */
  private def withUtxo[E <: EraTag](
      nes: NewEpochState[E],
      utxo: Map[TxIn, TxOut]
  ): NewEpochState[E] =
    NewEpochState
      .ledger[E]
      .modify(ls => ls.copy[E](utxo = ls.utxo.copy(utxo = UtxoTables(Utxo.fromMap(utxo)))))(nes)

  /** A case's recorded state, with its UTxO, and the environment of the corpus genesis. */
  final case class Recorded[E <: EraTag](nes: NewEpochState[E], env: MempoolEnv)

  /** The hard-fork-combinator index of Babbage. */
  private val BabbageIndex = BigInt(5)

  enum Judgement:
    case Refused(reason: Bytes)
    case Accepted
    case Unavailable(failure: LedgerFailure.Unavailable)
    case Unencodable(failures: NonEmptyChain[PredicateFailure])
    case NotRefused(defect: LedgerDefect)

  object Judgement:
    given Show[Judgement] = Show.show {
      case Refused(r)     => s"refused it with ${r.hex}"
      case Accepted       => "accepted it"
      case Unavailable(f) => s"could not judge it: ${f.message}"
      case Unencodable(fs) =>
        s"refused it with ${fs.iterator.map(_.detail).mkString("; ")}, which the era's encoding has no form for"
      case NotRefused(f) => s"did not judge it: ${f.message}"
    }

  enum ReplayProblem:
    case Genesis(error: ConfigError)
    case NoFile(file: String)
    case State(error: DecodeError)
    case Utxo(error: DecodeError)
    case Tick(failure: TickFailure)
    case Item
    case Transaction(error: LedgerDecodeError)

  object ReplayProblem:
    given Show[ReplayProblem] = Show.show {
      case Genesis(e)     => s"the corpus genesis does not set up a node: ${e.message}"
      case NoFile(f)      => s"the case names $f, which the corpus does not hold"
      case State(e)       => s"the reference's state does not decode: ${e.render}"
      case Utxo(e)        => s"the reference's UTxO does not decode: ${e.render}"
      case Tick(f)        => s"the state does not tick to the next slot: ${f.show}"
      case Item           => "the case's item is not `[5, #6.24(tx)]`"
      case Transaction(e) => s"Scarab does not decode the transaction: ${e.message}"
    }

  enum CorpusProblem:
    case Unreadable(file: Path)
    case Json(file: Path, error: io.circe.Error)
    case HashMismatch(file: Path, recorded: String, actual: String)
    case Unlisted(file: Path)
    case Hex(file: Path, field: String)

  object CorpusProblem:
    given Show[CorpusProblem] = Show.show {
      case Unreadable(f)         => s"$f cannot be read"
      case Json(f, e)            => s"$f: ${e.getMessage}"
      case HashMismatch(f, r, a) => s"$f has sha256 $a, the corpus records $r"
      case Unlisted(f)           => s"$f is in the corpus but not listed in its corpus.json"
      case Hex(f, field)         => s"$f: $field is not hexadecimal"
    }

  private final case class ManifestEntry(path: String, sha256: String) derives Decoder
  private final case class Manifest(
      format: Int,
      coverage: Map[RefusalEra, Vector[Family]],
      corpora: Vector[ManifestEntry]
  ) derives Decoder
  private final case class CaseEntry(file: String, sha256: String) derives Decoder
  private final case class CorpusFile(
      format: Int,
      era: RefusalEra,
      genesis: Map[String, String],
      cases: Vector[CaseEntry]
  ) derives Decoder
  private final case class Tip(slot: BigInt, hash: String) derives Decoder
  private final case class CaseFile(
      label: String,
      era: RefusalEra,
      families: Vector[Family],
      item: String,
      tip: Tip,
      state: String,
      utxo: String,
      reason: String
  ) derives Decoder

  /** One recorded refusal: the submitted LocalTxSubmission item and the reference's reason item. */
  final case class RefusalCase(
      label: String,
      era: RefusalEra,
      families: Vector[Family],
      item: Bytes,
      tipSlot: BigInt,
      state: String,
      utxo: String,
      reason: Bytes
  )

  /** A corpus directory: its cases and the content-addressed states and UTxOs they name. */
  final case class Corpus(
      path: String,
      era: RefusalEra,
      cases: Vector[RefusalCase],
      states: Map[String, Bytes],
      utxos: Map[String, Bytes]
  )

  final case class Loaded(coverage: Map[RefusalEra, Vector[Family]], corpora: Vector[Corpus])

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString

  private def read(file: Path): Either[CorpusProblem, Array[Byte]] =
    Either
      .catchOnly[java.io.IOException](Files.readAllBytes(file))
      .leftMap(_ => CorpusProblem.Unreadable(file))

  private def pinned(file: Path, recorded: String): Either[CorpusProblem, Array[Byte]] =
    read(file).flatMap { bytes =>
      val actual = sha256(bytes)
      Either.cond(actual == recorded, bytes, CorpusProblem.HashMismatch(file, recorded, actual))
    }

  private def json[A: Decoder](file: Path, bytes: Array[Byte]): Either[CorpusProblem, A] =
    parser.decode[A](new String(bytes, "UTF-8")).leftMap(CorpusProblem.Json(file, _))

  private def hex(file: Path, field: String, text: String): Either[CorpusProblem, Bytes] =
    Bytes.fromHex(text).leftMap(_ => CorpusProblem.Hex(file, field))

  /** The files of `dir` (relative to `base`), sorted; none when it does not exist. */
  private def listed(base: Path, dir: String): Either[CorpusProblem, Vector[String]] =
    val d = base.resolve(dir)
    if !Files.isDirectory(d) then Right(Vector.empty)
    else
      Either
        .catchOnly[java.io.IOException](
          Files.list(d).iterator().asScala.map(p => s"$dir/${p.getFileName}").toVector.sorted
        )
        .leftMap(_ => CorpusProblem.Unreadable(d))

  /** Files whose name is the sha256 of their content. */
  private def addressed(base: Path, dir: String): Either[CorpusProblem, Map[String, Bytes]] =
    listed(base, dir).flatMap(_.traverse { name =>
      val file = base.resolve(name)
      pinned(file, file.getFileName.toString.stripSuffix(".cbor")).map(b =>
        name -> Bytes.fromArray(b)
      )
    }.map(_.toMap))

  private def corpus(root: Path, entry: ManifestEntry): Either[CorpusProblem, Corpus] =
    val base = root.resolve(entry.path)
    val meta = base.resolve("corpus.json")
    for
      bytes <- pinned(meta, entry.sha256)
      file <- json[CorpusFile](meta, bytes)
      _ <- file.genesis.toVector.traverse((name, h) =>
        pinned(base.resolve("genesis").resolve(name), h)
      )
      present <- listed(base, "cases")
      _ <- present
        .find(p => !file.cases.exists(_.file == p))
        .map(base.resolve)
        .map(CorpusProblem.Unlisted(_))
        .toLeft(())
      cases <- file.cases.traverse { e =>
        val path = base.resolve(e.file)
        for
          raw <- pinned(path, e.sha256)
          c <- json[CaseFile](path, raw)
          item <- hex(path, "item", c.item)
          reason <- hex(path, "reason", c.reason)
        yield RefusalCase(c.label, c.era, c.families, item, c.tip.slot, c.state, c.utxo, reason)
      }
      states <- addressed(base, "states")
      utxos <- addressed(base, "utxo")
    yield Corpus(entry.path, file.era, cases, states, utxos)

  /** The manifest and every corpus it lists, each file checked against its recorded hash. */
  def load(root: Path): Either[CorpusProblem, Loaded] =
    val file = root.resolve("manifest.json")
    for
      bytes <- read(file)
      manifest <- json[Manifest](file, bytes)
      corpora <- manifest.corpora.traverse(corpus(root, _))
    yield Loaded(manifest.coverage, corpora)
