// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.{PredicateFailure, Tx, TxIn, TxOut, Utxo}
import xyz.kd5ujc.scarab.ledger.era.{EraTx, EraTxBody, Network, PParams, TxLevel, UTxO}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayPParams, ConwayTx, ConwayUTxO}
import xyz.kd5ujc.scarab.ledger.era.rules.{AllegraUtxo, AlonzoUtxo, BabbageUtxo, ShelleyUtxo}
import xyz.kd5ujc.scarab.ledger.rules.{Test as Verdict, UtxoTests}
import xyz.kd5ujc.scarab.ledger.state.{Conway, ConwayNewEpochState, ShelleyBasedEra}
import xyz.kd5ujc.scarab.node.NodeSetup
import xyz.kd5ujc.scarab.node.mempool.LocalSubmission
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{HaskellState, UtxoCodecs}

/** Conway's UTXO predicates on the era types against the flat ones, on every transaction of the
  * recorded Conway refusal corpora: the same verdict, failures included, from each. A predicate the
  * corpus never fails is named, since only a failing case tells the two apart.
  */
class ConwayUtxoChecksSuite extends FunSuite:
  import ReferenceRefusalSuite.*

  private given ShelleyBasedEra[Conway] = ShelleyBasedEra.SConway

  private val body = EraTxBody.conway
  private val top = body.top

  /** One Conway case as both paths see it. */
  private final case class Seen(
      label: String,
      bytes: Bytes,
      slot: BigInt,
      network: Int,
      netId: Network,
      flat: Tx,
      typed: ConwayTx,
      stored: Map[TxIn, TxOut],
      utxo: UTxO[Conway],
      flatPp: xyz.kd5ujc.scarab.ledger.ProtocolParams,
      pp: PParams[Conway]
  )

  private def right[E, A](what: String)(e: Either[E, A]): A =
    e.fold(x => fail(s"$what: $x"), identity)

  private val seen: Vector[Seen] =
    val loaded = right("the refusal corpus")(load(Path.of("fixtures/reference/refusals")))
    loaded.corpora.filter(_.era == RefusalEra.Conway).flatMap { c =>
      val setup = right(s"${c.path} genesis")(
        NodeSetup.fromFiles(c.build)(name => c.genesis.get(name).map(_.toArray))
      )
      val codecs = right(s"${c.path} layout")(stateCodecs(c.build))
      val network = setup.globals.network
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
          rc.tipSlot + 1,
          network,
          Network.of(BigInt(network)).getOrElse(fail(s"$what network $network")),
          right(s"$what flat")(Tx.fromEnvelope(bytes, Era.Conway)),
          right(s"$what typed")(ConwayTx.fromEnvelope(bytes)),
          stored,
          right(s"$what typed UTxO")(ConwayUTxO.fromStored(stored)),
          flatPp,
          PParams[Conway](right(s"$what typed parameters")(ConwayPParams.fromStored(flatPp)))
        )
      }
    }

  /** Each predicate: its name, the flat verdict and the typed one. */
  private def pairs(
      s: Seen
  ): Vector[(String, Verdict[PredicateFailure], Verdict[PredicateFailure])] =
    val fb = s.flat.body
    val tx = EraTx.conway
    val tb = tx.body[TxLevel.Top](s.typed)(using tx.bodyOf.top)
    val outs = fb.outputs ++ fb.collateralReturn.toVector
    Vector(
      (
        "disjointRefInputs",
        UtxoTests.disjointRefInputs(s.flatPp, fb),
        BabbageUtxo
          .disjointRefInputs[Conway](
            s.pp,
            body.inputs[TxLevel.Top](tb)(using top),
            body.referenceInputs[TxLevel.Top](tb)(using top)
          )
      ),
      (
        "validateOutsideValidityIntervalUTxO",
        UtxoTests.validityInterval(fb, s.slot),
        AllegraUtxo.validateOutsideValidityIntervalUTxO[Conway, TxLevel.Top](s.slot, tb)(using
          body,
          top
        )
      ),
      (
        "validateInputSetEmptyUTxO",
        UtxoTests.inputSetEmpty(fb),
        ShelleyUtxo.validateInputSetEmptyUTxO[Conway, TxLevel.Top](tb)(using body, top)
      ),
      (
        "validateBadInputsUTxO",
        UtxoTests
          .badInputs(Utxo.fromMap(s.stored), fb.inputs ++ fb.collateral ++ fb.referenceInputs),
        ShelleyUtxo.validateBadInputsUTxO[Conway](s.utxo, body.allInputs(tb))
      ),
      (
        "validateWrongNetwork",
        UtxoTests.wrongNetwork(s.network, outs),
        ShelleyUtxo
          .validateWrongNetwork[Conway](
            s.netId,
            body.allSizedOutputs[TxLevel.Top](tb)(using top).map(_.value)
          )(using
            body.outputsOf
          )
      ),
      (
        "validateWrongNetworkWithdrawal",
        UtxoTests.wrongNetworkWithdrawal(s.network, fb),
        ShelleyUtxo.validateWrongNetworkWithdrawal[Conway, TxLevel.Top](s.netId, tb)(using
          body,
          top
        )
      ),
      (
        "validateWrongNetworkInTxBody",
        UtxoTests.wrongNetworkInTxBody(s.network, fb),
        AlonzoUtxo.validateWrongNetworkInTxBody[Conway, TxLevel.Top](s.netId, tb)(using body, top)
      ),
      (
        "validateTooManyCollateralInputs",
        UtxoTests.tooManyCollateralInputs(s.flatPp, fb),
        AlonzoUtxo.validateTooManyCollateralInputs[Conway](s.pp, tb)
      )
    )

  test("the corpus has Conway cases, each read by both paths"):
    assert(clue(seen.size) > 0)

  test("each typed predicate gives the flat one's verdict on every Conway case"):
    seen.foreach { s =>
      pairs(s).foreach { (name, flat, typed) =>
        assertEquals(typed, flat, s"${s.label}: $name")
      }
    }

  /** The predicates some case fails, so the comparison above saw them refuse. */
  private val refusedSomewhere: Set[String] =
    seen.flatMap(s => pairs(s).collect { case (name, flat, _) if flat.isInvalid => name }).toSet

  /** The bytes of `s` 's transaction with its body's fields edited by `f`, and both paths' reading
    * of them. The predicates are pure, so a body whose witnesses no longer sign it still tells them
    * apart.
    */
  private def edited(s: Seen, bytes: Bytes)(
      f: Vector[(Node, Node)] => Vector[(Node, Node)]
  ): Seen =
    val root = right("envelope")(Cbor.decode(bytes))
    val again = root.value match
      case Value.Arr(parts) =>
        parts.head.value match
          case Value.Map(fields) =>
            val body = Node(Value.Map(f(fields)), Bytes.empty)
            right("re-encode")(Cbor.encode(Value.Arr(body +: parts.tail), Cbor.Limits.Unbounded))
          case other => fail(s"a body that is not a map: $other")
      case other => fail(s"an envelope that is not an array: $other")
    s.copy(
      flat = right("edited flat")(Tx.fromEnvelope(again, Era.Conway)),
      typed = right("edited typed")(ConwayTx.fromEnvelope(again))
    )

  private def key(k: Int): Node = Node(Value.UInt(k), Bytes.empty)
  private def isKey(k: Int)(n: Node): Boolean = n.value == Value.UInt(k)

  /** The same parameters in both forms, edited by `f`. */
  private def withParams(s: Seen)(
      f: xyz.kd5ujc.scarab.ledger.ProtocolParams => xyz.kd5ujc.scarab.ledger.ProtocolParams
  ): Seen =
    val flatPp = f(s.flatPp)
    s.copy(
      flatPp = flatPp,
      pp = PParams[Conway](right("edited parameters")(ConwayPParams.fromStored(flatPp)))
    )

  private def compared(s: Seen, name: String): Unit =
    val (_, flat, typed) = pairs(s).find(_._1 == name).getOrElse(fail(s"no predicate $name"))
    assert(flat.isInvalid, s"${s.label}: the crafted case passes $name")
    assertEquals(typed, flat, s"${s.label}: $name")

  private def envelope(s: Seen): Bytes = s.bytes

  test("crafted from a recorded case: an empty input set"):
    val s = seen.head
    compared(
      edited(s, envelope(s))(fields =>
        fields.map((k, v) =>
          if isKey(0)(k) then k -> Node(Value.Arr(Vector.empty), Bytes.empty) else k -> v
        )
      ),
      "validateInputSetEmptyUTxO"
    )

  test("crafted from a recorded case: reference inputs that are also spent, at version 10"):
    val s = withParams(seen.head)(_.copy(protocolMajor = 10))
    compared(
      edited(s, envelope(s))(fields =>
        val inputs =
          fields.collectFirst { case (k, v) if isKey(0)(k) => v }.getOrElse(fail("no inputs"))
        fields.filterNot((k, _) => isKey(18)(k)) :+ (key(18) -> inputs)
      ),
      "disjointRefInputs"
    )

  test("crafted from a recorded case: more collateral inputs than the parameters allow"):
    val s = seen.find(_.flat.body.collateral.nonEmpty).getOrElse(fail("no case with collateral"))
    compared(withParams(s)(_.copy(maxCollateralInputs = 0)), "validateTooManyCollateralInputs")

  /** A collateral set of `n` distinct inputs: indices below 50,000 of one transaction id after
    * another, since an index is a `Word16`.
    */
  private def collateral(n: Int): Node =
    def leaf(v: Value) = Node(v, Bytes.empty)
    def input(i: Int) =
      val id = leaf(Value.ByteString(Bytes(Vector.fill(31)(7.toByte) :+ (i / 50000).toByte)))
      leaf(Value.Arr(Vector(id, leaf(Value.UInt(i % 50000)))))
    leaf(Value.Arr(Vector.tabulate(n)(input)))

  private def withCollateral(s: Seen, n: Int): Seen =
    edited(s, envelope(s))(fields =>
      fields.filterNot((k, _) => isKey(13)(k)) :+ (key(13) -> collateral(n))
    )

  test("the collateral count is narrowed to a Word16, as the reference compares it, in both paths"):
    val s = withParams(seen.head)(_.copy(maxCollateralInputs = 3))
    def verdicts(n: Int) =
      val (_, flat, typed) =
        pairs(withCollateral(s, n)).find(_._1 == "validateTooManyCollateralInputs").get
      assertEquals(typed, flat, s"$n collateral inputs")
      flat
    assert(verdicts(1 << 16).isValid, "65,536 inputs count as 0")
    assert(verdicts((1 << 16) + 3).isValid, "65,539 inputs count as 3")
    assertEquals(
      verdicts((1 << 16) + 4),
      Verdict.failureUnless(
        false,
        PredicateFailure.utxo(
          xyz.kd5ujc.scarab.ledger.UtxoFailure
            .TooManyCollateralInputs(xyz.kd5ujc.scarab.ledger.Mismatch(4, 3))
        )
      )
    )

  test("the corpus fails the predicates it can tell apart, and names the rest"):
    val all = pairs(seen.head).map(_._1).toSet
    assertEquals(all -- refusedSomewhere, NeverRefused)

  /** The predicates no Conway case of the corpus fails: for these the comparison saw passes only.
    */
  private val NeverRefused: Set[String] =
    Set("disjointRefInputs", "validateTooManyCollateralInputs", "validateInputSetEmptyUTxO")
