// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import xyz.kd5ujc.scarab.protocol.StateLayout
import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{
  Address,
  Datum,
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
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx, ConwayTxCert, ConwayUTxO}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  AlonzoUtxow,
  BabbageUtxow,
  ConwayUtxowPredFailure,
  ShelleyUtxow
}
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
  * from each. The scripts a transaction needs and provides are the era types' own (`EraUTxO`'s
  * `getScriptsNeeded` and `getScriptsProvided`), checked against the flat path's on every case. A
  * predicate the corpus never fails is named, and refused on a case crafted from a recorded one.
  * The corpora recorded for the era rules alone
  * ([[xyz.kd5ujc.scarab.node.ntc.ReferenceRefusalSuite.EraOnly]]) are left out: stage 3 retires the
  * flat comparison, and `ReferenceRefusalSuite` binds the era rules to them.
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
      network: Int,
      cert: xyz.kd5ujc.scarab.ledger.state.ConwayCertState,
      families: Vector[Family],
      reason: Bytes
  ):
    lazy val ann: StAnnTx = StAnnTx(flat, FlatUtxo.fromMap(stored))

  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private val seen: Vector[Seen] =
    val loaded = right("the refusal corpus")(load(Path.of("fixtures/reference/refusals")))
    loaded.corpora.filter(c => c.era == RefusalEra.Conway && !EraOnly(c.path)).flatMap { c =>
      val setup = right(s"${c.path} genesis")(
        NodeSetup.fromFiles(c.build)(name => c.genesis.get(name).map(_.toArray))
      )
      val codecs = right(s"${c.path} layout")(stateCodecs(c.build))
      c.cases.map { rc =>
        val what = s"${c.path}/${rc.label}"
        val bytes: Bytes = right(s"$what item")(LocalSubmission.unwrap(rc.item)).raw
        val nes = right(s"$what state")(
          codecs.newEpochState[Conway].decodeBytes(c.states(rc.state), HaskellState.limits)
        )
        val flatPp = ConwayNewEpochState.gov.get(nes).curPParams
        val stored = right(s"$what UTxO")(UtxoCodecs.utxo.decodeBytes(c.utxos(rc.utxo)))
        Seen(
          what,
          bytes,
          right(s"$what flat")(FlatTx.fromEnvelope(bytes, Era.Conway)),
          right(s"$what typed")(ConwayTx.fromEnvelope(bytes, StateLayout.Node11_1)),
          stored,
          right(s"$what typed UTxO")(ConwayUTxO.fromStored(stored)),
          flatPp,
          PParams[Conway](right(s"$what typed parameters")(ConwayPParams.fromStored(flatPp))),
          rc.tipSlot + 1,
          setup.globals.network.wire,
          xyz.kd5ujc.scarab.ledger.state.ConwayNewEpochState.ls
            .andThen(xyz.kd5ujc.scarab.ledger.state.ConwayLedgerState.cert)
            .get(nes),
          rc.families,
          rc.reason
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
    s.ann.needed[Conway].map(_._2).toSet.flatMap(s.ann.scripts.get).map(_.kind).collect {
      case ScriptKind.PlutusV1 => PlutusLanguage.V1
      case ScriptKind.PlutusV2 => PlutusLanguage.V2
      case ScriptKind.PlutusV3 => PlutusLanguage.V3
    }

  /** The scripts the body needs, by `EraUTxO`'s `getScriptsNeeded`. */
  private def scriptsNeeded(s: Seen): AlonzoScriptsNeeded[Conway] =
    EraUTxO.conway.getScriptsNeeded[TxLevel.Top](
      s.utxo,
      tx.body[TxLevel.Top](s.typed)(using top)
    )(using EraUTxO.conway.txOf.bodyOf.top)

  /** The scripts the transaction provides, by `EraUTxO`'s `getScriptsProvided`. */
  private def scriptsProvided(s: Seen): ScriptsProvided[Conway] =
    EraUTxO.conway
      .getScriptsProvided[TxLevel.Top](s.utxo, s.typed)(using EraUTxO.conway.txOf.bodyOf.top)

  /** `missingRequiredDatums`' failures on the era types. */
  private def typedDatums(s: Seen): Vector[PredicateFailure] =
    typedFailures(s, EraUtxoRun.alonzoW)(
      AlonzoUtxow.missingRequiredDatums[Conway, TxLevel.Top](scriptsProvided(s), s.utxo, s.typed)(
        using
        tx,
        top,
        EraUTxO.conway,
        EraUTxO.conway.txOf.bodyOf.top
      )
    )

  /** `hasExactSetOfRedeemers` ' failures on the era types, each missing purpose in the node's flat
    * form ([[ConwayPlutusPurpose.toStored]]).
    */
  private def typedRedeemers(s: Seen): Vector[PredicateFailure] =
    typedFailures(s, EraUtxoRun.alonzoW)(
      AlonzoUtxow.hasExactSetOfRedeemers[Conway, TxLevel.Top](
        s.typed,
        scriptsProvided(s),
        scriptsNeeded(s)
      )(using tx, top)
    )

  /** A UTXOW predicate's failures in the node's flat form, injected into Conway's by `inject`, a
    * missing redeemer's purpose by the case's scripts needed and UTxO set.
    */
  private def typedFailures[A](s: Seen, inject: A => ConwayUtxowPredFailure[Conway])(
      test: Verdict[A]
  ): Vector[PredicateFailure] =
    failuresOf(
      EraUtxoRun
        .utxowFlat(test)(
          inject,
          ConwayUtxowPredFailure.storedPurposes(scriptsNeeded(s), s.utxo)
        )
        .getOrElse(fail(s"${s.label}: no flat form of $test"))
    )

  /** A typed predicate's failures injected into Conway's UTXOW (`inject`), under LEDGER, as LEDGER
    * reports them.
    */
  private def typedEra[A](inject: A => ConwayUtxowPredFailure[Conway])(
      test: Verdict[A]
  ): Vector[xyz.kd5ujc.scarab.ledger.era.rules.ConwayLedgerPredFailure[Conway]] =
    Verdict
      .failures(test)
      .toVector
      .flatMap(_.toNonEmptyVector.toVector)
      .map(a =>
        xyz.kd5ujc.scarab.ledger.era.rules.ConwayLedgerPredFailure.ConwayUtxowFailure[Conway](
          inject(a)
        )
      )

  /** `getReferenceScripts` (`Babbage/UTxO.hs:148-155`, cardano-ledger f649f975) of the inputs and
    * the reference inputs, by hash: what `babbageUtxowTransition` reads (`Babbage/Rules/Utxow.hs`).
    */
  private def referenceScripts(s: Seen): Set[Bytes] =
    val bodies = EraTxBody.conway
    val body = tx.body[TxLevel.Top](s.typed)(using top)
    val ins: Set[TxIn] = bodies.referenceInputs[TxLevel.Top](body)(using bodies.top) ++
      bodies.inputs[TxLevel.Top](body)(using bodies.top)
    BabbageUTxO.getReferenceScripts(s.utxo, ins).keySet

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
          .map(typedFailures(s, EraUtxoRun.shelleyW))
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
          typedFailures(s, EraUtxoRun.shelleyW)(
            BabbageUtxow.babbageMissingScripts[Conway](
              s.pp,
              EraUTxO.conway.getScriptsHashesNeeded(scriptsNeeded(s)),
              referenceScripts(s),
              tx.witsOf.scriptTxWits(wits).keySet
            )
          )
        )
      ),
      (
        "validateNeededWitnesses",
        Right(
          flatFailures(s) {
            case UtxowFailure.MissingVKeyWitnesses(_) => true
            case _                                    => false
          }
        ),
        Right(
          typedFailures(s, EraUtxoRun.shelleyW)(
            ShelleyUtxow.validateNeededWitnesses[Conway, TxLevel.Top](
              EraTxWits.keyHashWitnessesTxWits[Conway](wits)(using tx.witsOf),
              s.cert,
              s.utxo,
              tx.body[TxLevel.Top](s.typed)(using top)
            )(using EraUTxO.conway, EraUTxO.conway.txOf.bodyOf.top)
          )
        )
      ),
      (
        "missingRequiredDatums",
        Right(
          flatFailures(s) {
            case UtxowFailure.UnspendableUTxONoDatumHash(_) |
                UtxowFailure.MissingRequiredDatums(_, _) |
                UtxowFailure.NotAllowedSupplementalDatums(_, _) =>
              true
            case _ => false
          }
        ),
        Right(typedDatums(s))
      ),
      (
        "hasExactSetOfRedeemers",
        Right(
          flatFailures(s) {
            case UtxowFailure.ExtraRedeemers(_) | UtxowFailure.MissingRedeemers(_) => true
            case _                                                                 => false
          }
        ),
        Right(typedRedeemers(s))
      ),
      (
        "checkScriptIntegrityHash",
        Right(
          failuresOf(
            TxChecks.integrityCheck[Conway](s.flatPp, s.flat, s.ann.spentByInput, s.ann.scripts)
          )
        ),
        Right(
          typedFailures(s, EraUtxoRun.alonzoW)(
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

  /** A purpose as the flat path names it: its tag, index and script. */
  private def flatNeeded(s: Seen): Vector[(Int, BigInt, Bytes)] =
    xyz.kd5ujc.scarab.ledger.Purpose
      .collection(s.flat.body, s.ann.spentByInput, ShelleyBasedEra.SConway)
      .flatMap(p => p.scriptHash.map(h => (p.tag, BigInt(p.index), h)))

  private def typedNeeded(s: Seen): Vector[(Int, BigInt, Bytes)] =
    scriptsNeeded(s).unAlonzoScriptsNeeded.map { (purpose, hash) =>
      import xyz.kd5ujc.scarab.ledger.era.conway.ConwayPlutusPurpose.*
      val (tag, ix) = purpose match
        case ConwaySpending(a)    => (0, a.ix.value)
        case ConwayMinting(a)     => (1, a.ix.value)
        case ConwayCertifying(a)  => (2, a.ix.value)
        case ConwayWithdrawing(a) => (3, a.ix.value)
        case ConwayVoting(a)      => (4, a.ix.value)
        case ConwayProposing(a)   => (5, a.ix.value)
      (tag, ix, hash)
    }

  test("getScriptsNeeded is the flat path's, in order with each purpose's index, on every case"):
    seen.foreach(s => assertEquals(typedNeeded(s), flatNeeded(s), s.label))

  // what a wrong order or index would change: the corpus has a guardrails script; the recorded
  // blocks a script's withdrawal and a script voter (`EraUtxowSuite`); the crafted case below a
  // script input after another, policies out of order and a script certificate after another
  test(
    "validateNeededWitnesses' refusal is the reference's, byte for byte, where it is UTXOW's only"
  ):
    // a case the reference refused with a single UTXOW failure that the needed witnesses give:
    // the era-typed failure, encoded as the node sends it, is the reference's refusal
    val compared = seen.filter(_.families == Vector(Family.UTXOW)).flatMap { s =>
      val wits = tx.wits[TxLevel.Top](s.typed)(using top)
      val failures = typedEra(EraUtxoRun.shelleyW)(
        ShelleyUtxow.validateNeededWitnesses[Conway, TxLevel.Top](
          EraTxWits.keyHashWitnessesTxWits[Conway](wits)(using tx.witsOf),
          s.cert,
          s.utxo,
          tx.body[TxLevel.Top](s.typed)(using top)
        )(using EraUTxO.conway, EraUTxO.conway.txOf.bodyOf.top)
      )
      Option.when(failures.nonEmpty) {
        assertEquals(
          xyz.kd5ujc.scarab.node.ntc.ConwayFailureCborCheck.direct(failures).hex,
          s.reason.hex,
          s.label
        )
        s.label
      }
    }
    assert(compared.nonEmpty, "no case the needed witnesses alone refuse")

  test(
    "missingRequiredDatums' refusal is the reference's, byte for byte, where it is UTXOW's only"
  ):
    // a case the reference refused with a single UTXOW failure that the datums give (an unallowed
    // supplemental datum): the era-typed failure, encoded as the node sends it, is the reference's
    // refusal
    val compared = seen.filter(_.families == Vector(Family.UTXOW)).flatMap { s =>
      val failures = typedEra(EraUtxoRun.alonzoW)(
        AlonzoUtxow.missingRequiredDatums[Conway, TxLevel.Top](scriptsProvided(s), s.utxo, s.typed)(
          using
          tx,
          top,
          EraUTxO.conway,
          EraUTxO.conway.txOf.bodyOf.top
        )
      )
      Option.when(failures.nonEmpty) {
        assertEquals(
          xyz.kd5ujc.scarab.node.ntc.ConwayFailureCborCheck.direct(failures).hex,
          s.reason.hex,
          s.label
        )
        s.label
      }
    }
    assert(compared.nonEmpty, "no case the datums alone refuse")

  test("a certificate's flat form is the one the flat decoder read, on every case"):
    val certs = seen.flatMap { s =>
      val typed = EraTxBody.conway
        .certs[TxLevel.Top](tx.body[TxLevel.Top](s.typed)(using top))(using EraTxBody.conway.top)
      assertEquals(typed.map(ConwayTxCert.toStored), s.flat.body.certificates, s.label)
      typed
    }
    assert(certs.nonEmpty, "no certificate in the corpus")

  test("the corpus has a proposal's guardrails script among the scripts needed"):
    assert(seen.exists(s => typedNeeded(s).exists(_._1 == 5)))

  test(
    "crafted: a script input after a key input, policies written out of order, a script certificate second"
  ):
    val s0 = seen
      .find(s => typedNeeded(s).exists(_._1 == 0))
      .getOrElse(fail("no case that spends a script-locked output"))
    val keyLocked = s0.stored.values
      .find(_.address.kind match
        case xyz.kd5ujc.scarab.ledger.Address.Kind.Shelley(
              _,
              xyz.kd5ujc.scarab.ledger.Credential.Key(_),
              _,
              _
            ) =>
          true
        case _ => false)
      .getOrElse(fail("no key-locked output"))
    // an input before every other (the least transaction id), spending a key-locked output
    val first = TxIn(Bytes(Vector.fill(32)(0.toByte)), 0)
    def node(v: Value): Node = leaf(v)
    def bytes(fill: Int, n: Int): Node = node(Value.ByteString(Bytes(Vector.fill(n)(fill.toByte))))
    def uint(n: Int): Node = node(Value.UInt(n))
    val firstInput = node(Value.Arr(Vector(node(Value.ByteString(first.id)), uint(0))))
    def isKey(k: Int)(n: Node): Boolean = n.value == Value.UInt(k)
    // two policies, the greater written first, so only the policies' order indexes them right
    val mint = node(
      Value.Map(
        Vector(
          bytes(0x22, 28) -> node(
            Value.Map(Vector(node(Value.ByteString(Bytes.empty)) -> uint(1)))
          ),
          bytes(0x11, 28) -> node(Value.Map(Vector(node(Value.ByteString(Bytes.empty)) -> uint(1))))
        )
      )
    )
    // a key's registration, then a script's unregistration: the script's certificate is the second
    val certs = node(
      Value.Arr(
        Vector(
          node(
            Value.Arr(Vector(uint(7), node(Value.Arr(Vector(uint(0), bytes(0x31, 28)))), uint(2)))
          ),
          node(
            Value.Arr(Vector(uint(8), node(Value.Arr(Vector(uint(1), bytes(0x32, 28)))), uint(2)))
          )
        )
      )
    )
    val s1 = edited(s0, 0)(fields =>
      fields
        .map {
          case (k, v) if isKey(0)(k) =>
            k -> (v.value match
              case Value.Tag(tag, inner) =>
                inner.value match
                  case Value.Arr(ins) => node(Value.Tag(tag, node(Value.Arr(ins :+ firstInput))))
                  case other          => fail(s"inputs: $other")
              case Value.Arr(ins) => node(Value.Arr(ins :+ firstInput))
              case other          => fail(s"inputs: $other"))
          case kv => kv
        }
        .filterNot((k, _) => isKey(9)(k) || isKey(4)(k)) :+ (node(Value.UInt(9)) -> mint) :+
        (node(Value.UInt(4)) -> certs)
    )
    val stored = s1.stored + (first -> keyLocked)
    val s = s1.copy(stored = stored, utxo = right("crafted UTxO")(ConwayUTxO.fromStored(stored)))
    val needs = typedNeeded(s)
    assertEquals(needs, flatNeeded(s))
    assert(needs.exists((tag, ix, _) => tag == 0 && ix > 0), s"a script input first: $needs")
    assertEquals(
      needs.filter(_._1 == 1).map(n => (n._2, n._3)),
      Vector(
        (BigInt(0), Bytes(Vector.fill(28)(0x11.toByte))),
        (BigInt(1), Bytes(Vector.fill(28)(0x22.toByte)))
      )
    )
    assertEquals(
      needs.filter(_._1 == 2).map(n => (n._2, n._3)),
      Vector((BigInt(1), Bytes(Vector.fill(28)(0x32.toByte))))
    )

  test("getScriptsProvided is the flat path's scripts on every case: hashes, kinds and bytes"):
    val scripts = tx.witsOf.scriptsOf
    val bytesCompared = seen.map { s =>
      val body = s.flat.body
      val outs = (body.inputs ++ body.referenceInputs).flatMap(s.stored.get)
      val flat = TxChecks.providedScripts(s.flat.witnesses, outs.toVector)
      val typed = EraUTxO.conway
        .getScriptsProvided[TxLevel.Top](s.utxo, s.typed)(using EraUTxO.conway.txOf.bodyOf.top)
        .unScriptsProvided
      assertEquals(typed.keySet, flat.keySet, s.label)
      // each script's kind, and its bytes where the flat path keeps them (a witness script; an
      // output's reference script keeps only its hash and tree)
      typed.toVector.map { (h, script) =>
        assertEquals(scripts.scriptPrefixTag(script).toInt, flat(h).kind.tag, s"${s.label}: $h")
        flat(h).plutus.orElse(flat(h).encoding) match
          case Some(payload) =>
            assertEquals(scripts.originalBytes(script), payload, s"${s.label}: bytes of $h")
            1
          case None => 0
      }.sum
    }.sum
    assert(bytesCompared > 0, "no script whose bytes both paths keep")

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
      typed = right("edited typed")(ConwayTx.fromEnvelope(again, StateLayout.Node11_1))
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

  /** A bootstrap witness whose signature cannot verify: a key of `fill` bytes, a zero signature, a
    * chain code of 32 zero bytes and empty attributes (`[vkey, signature, chain_code,
    * attributes]`).
    */
  private def forgedBootstrap(fill: Int): Node =
    def bytes(b: Vector[Byte]) = leaf(Value.ByteString(Bytes(b)))
    leaf(
      Value.Arr(
        Vector(
          bytes(Vector.fill(32)(fill.toByte)),
          bytes(Vector.fill(64)(0.toByte)),
          bytes(Vector.fill(32)(0.toByte)),
          bytes(Vector(0xa0.toByte))
        )
      )
    )

  /** The address root of [[forgedBootstrap]], the reference's order of bootstrap witnesses. */
  private def bootstrapRoot(fill: Int): Bytes =
    xyz.kd5ujc.scarab.ledger
      .BootstrapWitness(
        Bytes(Vector.fill(32)(fill.toByte)),
        Bytes(Vector.fill(64)(0.toByte)),
        Bytes(Vector.fill(32)(0.toByte)),
        Bytes(Vector(0xa0.toByte))
      )
      .addressRoot

  /** The keys of the forged witnesses each path reports, for key witnesses of `fills` and bootstrap
    * witnesses of `boots`, each added to the recorded witnesses of its kind (key 0 or 2).
    */
  private def forgedReported(
      fills: Vector[Int],
      boots: Vector[Int] = Vector.empty
  ): (Vector[Bytes], Vector[Bytes]) =
    def added(more: Vector[Node])(n: Node): Node = n.value match
      case Value.Arr(ws)   => leaf(Value.Arr(ws ++ more))
      case Value.Tag(t, x) => leaf(Value.Tag(t, added(more)(x)))
      case other           => fail(s"witnesses that are not a list: $other")
    def withMore(key: Int, more: Vector[Node])(fields: Vector[(Node, Node)]) =
      if more.isEmpty then fields
      else if fields.exists((k, _) => isKey(key)(k)) then
        fields.map((k, v) => if isKey(key)(k) then k -> added(more)(v) else k -> v)
      else fields :+ (leaf(Value.UInt(key)) -> leaf(Value.Arr(more)))
    val c = edited(seen.head, 1)(fields =>
      withMore(2, boots.map(forgedBootstrap))(withMore(0, fills.map(forged))(fields))
    )
    val (_, flat, typed) = pairs(c).find(_._1 == "validateVerifiedWits").get
    def keys(r: Either[LedgerDefect, Vector[PredicateFailure]]) =
      right(s"forged $fills $boots")(r).collect {
        case PredicateFailure.Utxow(UtxowFailure.InvalidWitnesses(ks)) => ks
      }.flatten
    (keys(flat), keys(typed))

  private def key(fill: Int): Bytes = Bytes(Vector.fill(32)(fill.toByte))

  /** The reference's order of failing keys (`failed <> failedBootstrap`,
    * `Shelley/Rules/Utxow.hs:393, 398-407`, cardano-ledger f649f975): the key witnesses' in `Ord
    * WitVKey` order, then the bootstrap witnesses' in `Ord BootstrapWitness` order (the address
    * root, `Keys/Bootstrap.hs:108-109`).
    */
  private def referenceOrder(fills: Vector[Int], boots: Vector[Int]): Vector[Bytes] =
    fills.distinct.map(key).sortBy(k => WitVKey(k, Bytes(Vector.fill(64)(0.toByte)))) ++
      boots.distinct.sortBy(bootstrapRoot).map(key)

  test(
    "crafted: forged bootstrap witnesses, alone and with forged key witnesses, in the reference's order"
  ):
    Vector(
      (Vector.empty[Int], Vector(5)),
      (Vector.empty[Int], Vector(6, 7)),
      (Vector.empty[Int], Vector(7, 6)),
      (Vector(1, 2), Vector(5)),
      (Vector(2, 1), Vector(7, 6))
    ).foreach { (fills, boots) =>
      val (flat, typed) = forgedReported(fills, boots)
      val expected = referenceOrder(fills, boots)
      assertEquals(typed, expected, s"forged $fills, bootstrap $boots")
      assertEquals(flat, expected, s"forged $fills, bootstrap $boots")
    }

  test("crafted: forged key witnesses refuse in both paths, the same keys"):
    Vector(Vector(1, 2), Vector(2, 1)).foreach { fills =>
      val (flat, typed) = forgedReported(fills)
      val forgedKeys = fills.map(f => Bytes(Vector.fill(32)(f.toByte)))
      assertEquals(typed.toSet, forgedKeys.toSet, s"forged $fills")
      assertEquals(flat.toSet, typed.toSet, s"forged $fills")
    }

  /** Both paths list the keys of the failing key witnesses as the reference does, in its witness
    * set's order (`Set.toList` over `Ord WitVKey`, the key's hash first,
    * `Shelley/Rules/Utxow.hs:398-402` and `Keys/WitVKey.hs:58-68`, cardano-ledger f649f975),
    * whatever order they arrived in.
    */
  test("crafted: both paths list failing keys in the reference's order, whatever their arrival"):
    val one = Bytes(Vector.fill(32)(1.toByte))
    val two = Bytes(Vector.fill(32)(2.toByte))
    val byWitness = Vector(one, two).sortBy(k => WitVKey(k, Bytes(Vector.fill(64)(0.toByte))))
    Vector(Vector(1, 2), Vector(2, 1)).foreach { fills =>
      val (flat, typed) = forgedReported(fills)
      assertEquals(typed, byWitness, s"forged $fills")
      assertEquals(flat, byWitness, s"forged $fills")
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

  /** The first input of `s` whose spent output a script's payment credential locks. */
  private def scriptLocked(s: Seen): (TxIn, FlatTxOut) =
    s.flat.body.inputs.toVector
      .flatMap(in => s.stored.get(in).map(in -> _))
      .find((_, out) =>
        out.address.kind match
          case Address.Kind.Shelley(_, xyz.kd5ujc.scarab.ledger.Credential.Script(_), _, _) =>
            true
          case _ => false
      )
      .getOrElse(fail(s"${s.label}: no script-locked input"))

  /** `s` with the output `in` spends locked by `script` and holding no datum. */
  private def noDatum(s: Seen, in: TxIn, script: Bytes): Seen =
    val out = s.stored.getOrElse(in, fail(s"${s.label}: no output at $in"))
    val raw = Bytes(out.address.raw.value.patch(1, script.value, 28))
    val stored = s.stored.updated(
      in,
      out.copy(address = right("address")(Address.decode(raw)), datum = Datum.NoDatum)
    )
    s.copy(stored = stored, utxo = right("crafted UTxO")(ConwayUTxO.fromStored(stored)))

  private def unspendable(failures: Vector[PredicateFailure]): Vector[Set[TxIn]] =
    failures.collect { case PredicateFailure.Utxow(UtxowFailure.UnspendableUTxONoDatumHash(ins)) =>
      ins
    }

  test(
    "crafted: a spent Plutus V1 output without a datum is unspendable, a Plutus V3 one is not (CIP-69)"
  ):
    val s0 = seen
      .find(_.label.endsWith("/utxow-spend-without-datum-or-redeemer"))
      .getOrElse(fail("no case that spends a script's output without its datum"))
    val (in, out) = scriptLocked(s0)
    val v3 = out.address.kind match
      case Address.Kind.Shelley(_, xyz.kd5ujc.scarab.ledger.Credential.Script(h), _, _) => h
      case other => fail(s"not a script's address: $other")
    // the Plutus V3 script, without a datum: no failure of the datums
    assertEquals(unspendable(compared(noDatum(s0, in, v3), "missingRequiredDatums")), Vector.empty)
    // the same script's bytes as a Plutus V1 script (key 3 of the witnesses, not 7)
    val asV1 = edited(s0, 1)(fields =>
      fields.map((k, v) => if isKey(7)(k) then leaf(Value.UInt(3)) -> v else k -> v)
    )
    val v1 = scriptsProvided(asV1).unScriptsProvided
      .collectFirst {
        case (h, script) if tx.witsOf.scriptsOf.scriptPrefixTag(script) == 1 => h
      }
      .getOrElse(fail("no Plutus V1 script provided"))
    assertEquals(
      unspendable(compared(noDatum(asV1, in, v1), "missingRequiredDatums")),
      Vector(Set(in))
    )

  /** `[k, v]` pairs as a CBOR map node. */
  private def map(entries: (Node, Node)*): Node = leaf(Value.Map(entries.toVector))
  private def arr(items: Node*): Node = leaf(Value.Arr(items.toVector))
  private def uint(n: Int): Node = leaf(Value.UInt(n))
  private def bytes(b: Bytes): Node = leaf(Value.ByteString(b))

  test("crafted: redeemers no purpose needs, written out of their order"):
    val s = seen.head
    // a redeemer `[data, [mem, steps]]` at mint 7, then at spend 9
    val redeemer = arr(uint(0), arr(uint(0), uint(0)))
    val c = edited(s, 1)(fields =>
      fields.filterNot((k, _) => isKey(5)(k)) :+
        (uint(5) -> map(arr(uint(1), uint(7)) -> redeemer, arr(uint(0), uint(9)) -> redeemer))
    )
    val failures = compared(c, "hasExactSetOfRedeemers")
    assertEquals(
      failures.collect { case PredicateFailure.Utxow(UtxowFailure.ExtraRedeemers(ps)) => ps },
      Vector(Vector((0, BigInt(9)), (1, BigInt(7))))
    )

  test("crafted: a Plutus script minting, certifying, withdrawing and voting with no redeemer"):
    val s0 = seen
      .find(s => s.label.endsWith("/utxo-mint-units-too-big"))
      .getOrElse(fail("no case that mints with a Plutus script"))
    val policy = typedNeeded(s0).find(_._1 == 1).getOrElse(fail("no minting script"))._3
    val script = arr(uint(1), bytes(policy))
    val account = Bytes(Vector((0xf0 | s0.network).toByte) ++ policy.value)
    // the script's unregistration (tag 8), a withdrawal of nothing from its account, and its vote
    // as a DRep (voter tag 3) on an action, without the redeemers (key 5 of the witnesses)
    val added = Vector(
      uint(4) -> arr(arr(uint(8), script, uint(2))),
      uint(5) -> map(bytes(account) -> uint(0)),
      uint(19) -> map(
        arr(uint(3), bytes(policy)) -> map(
          arr(bytes(Bytes(Vector.fill(32)(9.toByte))), uint(0)) -> arr(uint(1), leaf(Value.Null))
        )
      )
    )
    val body = edited(s0, 0)(fields =>
      fields.filterNot((k, _) => isKey(4)(k) || isKey(5)(k) || isKey(19)(k)) ++ added
    )
    val c = edited(body, 1)(fields => fields.filterNot((k, _) => isKey(5)(k)))
    val failures = compared(c, "hasExactSetOfRedeemers")
    val missing = failures.collect {
      case PredicateFailure.Utxow(UtxowFailure.MissingRedeemers(ps)) => ps
    }.flatten
    assertEquals(missing.map((p, h) => (p.tag, h)), Vector(3, 2, 1, 4).map(_ -> policy))
