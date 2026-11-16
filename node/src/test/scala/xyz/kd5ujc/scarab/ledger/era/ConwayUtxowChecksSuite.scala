// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  LedgerDefect,
  PredicateFailure,
  ScriptKind,
  Tx as FlatTx,
  TxChecks,
  TxEnv,
  TxIn,
  TxOut as FlatTxOut,
  Utxo as FlatUtxo,
  UtxowFailure,
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx, ConwayUTxO}
import xyz.kd5ujc.scarab.ledger.era.rules.{AlonzoUtxow, BabbageUtxow, ShelleyUtxow}
import xyz.kd5ujc.scarab.ledger.rules.{StAnnTx, Test as Verdict, UtxoEnv, Utxow}
import xyz.kd5ujc.scarab.ledger.state.{Conway, ConwayNewEpochState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.mempool.LocalSubmission
import xyz.kd5ujc.scarab.node.ntc.ReferenceRefusalSuite.*
import xyz.kd5ujc.scarab.plutus.{PlutusLanguage, ScarabEvaluator}
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, UtxoCodecs}

/** Conway's UTXOW predicates on the era types (`era/rules/Utxow.scala`) against the flat ones, on
  * every transaction of the recorded Conway refusal corpora: the same verdict, failures included,
  * from each. The scripts a transaction needs come from the flat annotation, as the era types have
  * no `EraUTxO` yet. A predicate the corpus never fails is named, and refused on a case crafted
  * from a recorded one.
  */
class ConwayUtxowChecksSuite extends FunSuite:
  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway

  private val tx = EraTx.conway
  private val top: tx.bodyOf.Level[TxLevel.Top] = tx.bodyOf.top

  /** One Conway case as both paths see it. */
  private final case class Seen(
      label: String,
      bytes: Bytes,
      flat: FlatTx,
      typed: ConwayTx,
      stored: Map[TxIn, FlatTxOut],
      utxo: UTxO[Conway],
      flatPp: xyz.kd5ujc.scarab.ledger.ProtocolParams,
      pp: PParams[Conway],
      slot: BigInt,
      network: Int
  ):
    lazy val ann: StAnnTx = StAnnTx(flat, FlatUtxo.fromMap(stored))

  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private val seen: Vector[Seen] =
    val loaded = right("the refusal corpus")(load(Path.of("fixtures/reference/refusals")))
    loaded.corpora.filter(_.era == RefusalEra.Conway).flatMap { c =>
      val setup = right(s"${c.path} genesis")(
        NodeSetup.fromFiles(c.build)(name => c.genesis.get(name).map(_.toArray))
      )
      val codecs = right(s"${c.path} layout")(stateCodecs(c.build))
      c.cases.map { rc =>
        val what = s"${c.path}/${rc.label}"
        val bytes: Bytes = right(s"$what item")(LocalSubmission.unwrap(rc.item))
        val nes = right(s"$what state")(
          codecs.newEpochState[Conway].decodeBytes(c.states(rc.state), HaskellState.limits)
        )
        val flatPp = ConwayNewEpochState.gov.get(nes).curPParams
        val stored = right(s"$what UTxO")(UtxoCodecs.utxo.decodeBytes(c.utxos(rc.utxo)))
        Seen(
          what,
          bytes,
          right(s"$what flat")(FlatTx.fromEnvelope(bytes, Era.Conway)),
          right(s"$what typed")(ConwayTx.fromEnvelope(bytes)),
          stored,
          right(s"$what typed UTxO")(ConwayUTxO.fromStored(stored)),
          flatPp,
          PParams[Conway](right(s"$what typed parameters")(ConwayPParams.fromStored(flatPp))),
          rc.tipSlot + 1,
          setup.globals.network
        )
      }
    }

  private val decoder = ScarabEvaluator.interpreter

  /** The flat UTXOW's failures of the given kinds, in its order: the flat rule checks the scripts
    * received inside its checks (`Utxow.checks`), not in a function of its own.
    */
  private def flatFailures(s: Seen)(keep: UtxowFailure => Boolean): Vector[PredicateFailure] =
    val env = TxEnv(
      slot = s.slot,
      epoch = 0,
      network = s.network,
      evaluator = decoder,
      decoder = decoder
    )
    Utxow
      .checks[Conway](UtxoEnv(env, s.flatPp, ()), Map.empty, s.ann, Verdict.pass)
      .collect { case Right(t) =>
        Verdict.failures(t).toVector.flatMap(_.toNonEmptyVector.toVector)
      }
      .flatten
      .filter {
        case PredicateFailure.Utxow(f) => keep(f)
        case _                         => false
      }

  private def failuresOf(t: Verdict[PredicateFailure]): Vector[PredicateFailure] =
    Verdict.failures(t).toVector.flatMap(_.toNonEmptyVector.toVector)

  /** The Plutus languages of the scripts the transaction needs and has
    * (`plutusLanguagesUsedStAnnTx`), from the flat annotation.
    */
  private def languages(s: Seen): Set[PlutusLanguage] =
    s.ann.needed.keySet.flatMap(s.ann.scripts.get).map(_.kind).collect {
      case ScriptKind.PlutusV1 => PlutusLanguage.V1
      case ScriptKind.PlutusV2 => PlutusLanguage.V2
      case ScriptKind.PlutusV3 => PlutusLanguage.V3
    }

  /** `getReferenceScripts` (`Babbage/UTxO.hs:150-166`, cardano-ledger f649f975) by hash, over the
    * typed UTxO set restricted to the inputs and the reference inputs.
    */
  private def referenceScripts(s: Seen): Set[Bytes] =
    val bodies = EraTxBody.conway
    val body = tx.body[TxLevel.Top](s.typed)(using top)
    val ins = bodies.referenceInputs[TxLevel.Top](body)(using bodies.top) ++
      bodies.inputs[TxLevel.Top](body)(using bodies.top)
    val outs = EraTxOut.conway
    s.utxo.unUTxO.view
      .filterKeys(ins.contains)
      .values
      .flatMap(outs.referenceScript)
      .map(outs.scripts.hashScript)
      .toSet

  /** Each predicate: its name, the flat verdict and the typed one, as failures (a defect is a
    * `Left`).
    */
  private def pairs(s: Seen): Vector[
    (
        String,
        Either[LedgerDefect, Vector[PredicateFailure]],
        Either[LedgerDefect, Vector[PredicateFailure]]
    )
  ] =
    val wits = tx.wits[TxLevel.Top](s.typed)(using top)
    val integrity =
      ScriptIntegrity.mkScriptIntegrity[Conway, TxLevel.Top](s.pp, s.typed, languages(s))(using
        tx,
        summon[AlonzoEraPParams[Conway]],
        tx.witsOf,
        top
      )
    Vector(
      (
        "validateVerifiedWits",
        TxChecks.verifiedWits(s.flat, Verdicts.Direct).map(failuresOf),
        ShelleyUtxow
          .validateVerifiedWits[Conway, TxLevel.Top](s.typed, Verdicts.Direct)(using tx, top)
          .map(failuresOf)
      ),
      (
        "babbageMissingScripts",
        Right(
          flatFailures(s) {
            case UtxowFailure.MissingScriptWitnesses(_) |
                UtxowFailure.ExtraneousScriptWitnesses(_) =>
              true
            case _ => false
          }
        ),
        Right(
          failuresOf(
            BabbageUtxow.babbageMissingScripts[Conway](
              s.pp,
              s.ann.needed.keySet,
              referenceScripts(s),
              tx.witsOf.scriptTxWits(wits).keySet
            )
          )
        )
      ),
      (
        "checkScriptIntegrityHash",
        Right(
          failuresOf(
            TxChecks.integrityCheck[Conway](s.flatPp, s.flat, s.ann.spentByInput, s.ann.scripts)
          )
        ),
        Right(
          failuresOf(
            AlonzoUtxow.checkScriptIntegrityHash[Conway, TxLevel.Top](s.typed, s.pp, integrity)(
              using
              tx,
              summon[EraPParams[Conway]],
              summon[xyz.kd5ujc.scarab.ledger.era.Era[Conway]],
              top
            )
          )
        )
      )
    )

  private def compared(s: Seen, name: String): Vector[PredicateFailure] =
    val (_, flat, typed) = pairs(s).find(_._1 == name).getOrElse(fail(s"no predicate $name"))
    assertEquals(typed, flat, s"${s.label}: $name")
    right(s"${s.label}: $name")(flat)

  test("the corpus has Conway cases, each read by both paths"):
    assert(clue(seen.size) > 0)

  test("each typed predicate gives the flat one's verdict on every Conway case"):
    seen.foreach(s =>
      pairs(s).foreach((name, flat, typed) => assertEquals(typed, flat, s"${s.label}: $name"))
    )

  /** The cases on which each predicate refuses. */
  private lazy val refusals: Map[String, Int] =
    seen
      .flatMap(s => pairs(s).collect { case (name, Right(fs), _) if fs.nonEmpty => name })
      .groupMapReduce(identity)(_ => 1)(_ + _)

  test("the corpus fails the predicates it can tell apart, and names the rest"):
    val all = pairs(seen.head).map(_._1).toSet
    assertEquals(all -- refusals.keySet, NeverRefused, s"refusals by predicate: $refusals")

  /** The predicates no Conway case of the corpus fails: for these the comparison saw passes only,
    * and the crafted cases below refuse them.
    */
  private val NeverRefused: Set[String] = Set("validateVerifiedWits")

  // Crafted cases: a recorded transaction's envelope with its body or witness set edited. The
  // predicates are pure, so a body whose witnesses no longer sign it still tells them apart.

  private def leaf(v: Value): Node = Node(v, Bytes.empty)
  private def isKey(k: Int)(n: Node): Boolean = n.value == Value.UInt(k)

  /** `s` with the map at `part` of its envelope (0 the body, 1 the witness set) edited by `f`. */
  private def edited(s: Seen, part: Int)(f: Vector[(Node, Node)] => Vector[(Node, Node)]): Seen =
    val root = right("envelope")(Cbor.decode(s.bytes))
    val again = root.value match
      case Value.Arr(parts) =>
        parts(part).value match
          case Value.Map(fields) =>
            val changed = parts.updated(part, leaf(Value.Map(f(fields))))
            right("re-encode")(Cbor.encode(Value.Arr(changed), Cbor.Limits.Unbounded))
          case other => fail(s"a part that is not a map: $other")
      case other => fail(s"an envelope that is not an array: $other")
    s.copy(
      bytes = again,
      flat = right("edited flat")(FlatTx.fromEnvelope(again, Era.Conway)),
      typed = right("edited typed")(ConwayTx.fromEnvelope(again))
    )

  private def withMajor(s: Seen, major: Int): Seen =
    val flatPp = s.flatPp.copy(protocolMajor = major)
    s.copy(
      flatPp = flatPp,
      pp = PParams[Conway](right("parameters")(ConwayPParams.fromStored(flatPp)))
    )

  /** The body's script integrity hash replaced by 32 bytes of 7. */
  private def wrongHash(s: Seen): Seen =
    edited(s, 0)(fields =>
      fields.filterNot((k, _) => isKey(11)(k)) :+
        (leaf(Value.UInt(11)) -> leaf(Value.ByteString(Bytes(Vector.fill(32)(7.toByte)))))
    )

  test("crafted: a wrong script integrity hash, below version 11 and from 11"):
    val s =
      seen.find(c => c.flat.witnesses.redeemers.nonEmpty).getOrElse(fail("no case with redeemers"))
    val below = compared(wrongHash(withMajor(s, 10)), "checkScriptIntegrityHash")
    assert(
      below.exists {
        case PredicateFailure.Utxow(UtxowFailure.PPViewHashesDontMatch(_)) => true
        case _                                                             => false
      },
      below
    )
    val from = compared(wrongHash(withMajor(s, 11)), "checkScriptIntegrityHash")
    assert(
      from.exists {
        case PredicateFailure.Utxow(UtxowFailure.ScriptIntegrityHashMismatch(_, Some(_))) => true
        case _                                                                            => false
      },
      from
    )

  test("crafted: datums without redeemers, whose integrity is over Conway's empty redeemers"):
    val datum = leaf(Value.UInt(42))
    val s = withMajor(seen.head, 11)
    val noRedeemers = edited(s, 1)(fields =>
      fields.filterNot((k, _) => isKey(5)(k) || isKey(4)(k)) :+
        (leaf(Value.UInt(4)) -> leaf(Value.Arr(Vector(datum))))
    )
    assertEquals(noRedeemers.flat.witnesses.redeemersRaw, None)
    val failures = compared(wrongHash(noRedeemers), "checkScriptIntegrityHash")
    assert(
      failures.exists {
        case PredicateFailure.Utxow(UtxowFailure.ScriptIntegrityHashMismatch(_, Some(pre))) =>
          pre.headOption.contains(0xa0.toByte)
        case _ => false
      },
      failures
    )

  /** A key witness whose signature cannot verify: a key of `fill` bytes, a zero signature. */
  private def forged(fill: Int): Node =
    leaf(
      Value.Arr(
        Vector(
          leaf(Value.ByteString(Bytes(Vector.fill(32)(fill.toByte)))),
          leaf(Value.ByteString(Bytes(Vector.fill(64)(0.toByte))))
        )
      )
    )

  /** The keys of the forged witnesses each path reports, for `fills`. */
  private def forgedReported(fills: Vector[Int]): (Vector[Bytes], Vector[Bytes]) =
    val c = edited(seen.head, 1)(fields =>
      fields.map((k, v) =>
        if isKey(0)(k) then
          def added(n: Node): Node = n.value match
            case Value.Arr(ws)   => leaf(Value.Arr(ws ++ fills.map(forged)))
            case Value.Tag(t, x) => leaf(Value.Tag(t, added(x)))
            case other           => fail(s"key witnesses that are not a list: $other")
          k -> added(v)
        else k -> v
      )
    )
    val (_, flat, typed) = pairs(c).find(_._1 == "validateVerifiedWits").get
    def keys(r: Either[LedgerDefect, Vector[PredicateFailure]]) =
      right(s"forged $fills")(r).collect {
        case PredicateFailure.Utxow(UtxowFailure.InvalidWitnesses(ks)) => ks
      }.flatten
    (keys(flat), keys(typed))

  test("crafted: forged key witnesses refuse in both paths, the same keys"):
    Vector(Vector(1, 2), Vector(2, 1)).foreach { fills =>
      val (flat, typed) = forgedReported(fills)
      val forgedKeys = fills.map(f => Bytes(Vector.fill(32)(f.toByte)))
      assertEquals(typed.toSet, forgedKeys.toSet, s"forged $fills")
      assertEquals(flat.toSet, typed.toSet, s"forged $fills")
    }

  /** A difference in what the refusal reports, not in the verdict: the reference lists the keys of
    * the failing key witnesses in its witness set's order (`Set.toList` over `Ord WitVKey`, the
    * key's hash first, `Shelley/Rules/Utxow.hs:398-402` and `Keys/WitVKey.hs:58-68`, cardano-ledger
    * f649f975), as the typed predicate does; the flat check lists them in the order they arrived.
    * Reported to the lead; the flat path is not changed here.
    */
  test(
    "crafted: the typed predicate lists failing keys in the reference's order, the flat as they arrived"
  ):
    val one = Bytes(Vector.fill(32)(1.toByte))
    val two = Bytes(Vector.fill(32)(2.toByte))
    val byWitness = Vector(one, two).sortBy(k => WitVKey(k, Bytes(Vector.fill(64)(0.toByte))))
    Vector(Vector(1, 2), Vector(2, 1)).foreach { fills =>
      val (flat, typed) = forgedReported(fills)
      assertEquals(typed, byWitness, s"forged $fills")
      assertEquals(flat, fills.map(f => Bytes(Vector.fill(32)(f.toByte))), s"forged $fills")
    }

  test("crafted: an extraneous script witness"):
    val s = seen.head
    // `[0, [0, h]]`, a signature script, in the native scripts (key 1)
    val native = leaf(
      Value.Arr(
        Vector(leaf(Value.UInt(0)), leaf(Value.ByteString(Bytes(Vector.fill(28)(5.toByte)))))
      )
    )
    val c = edited(s, 1)(fields =>
      fields.filterNot((k, _) => isKey(1)(k)) :+ (leaf(Value.UInt(1)) -> leaf(
        Value.Arr(Vector(native))
      ))
    )
    val failures = compared(c, "babbageMissingScripts")
    assert(
      failures.exists {
        case PredicateFailure.Utxow(UtxowFailure.ExtraneousScriptWitnesses(_)) => true
        case _                                                                 => false
      },
      failures
    )
