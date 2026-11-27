// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.{Id, Monad}
import cats.data.NonEmptyVector
import cats.mtl.{Ask, Raise, Stateful}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, JudgedSts, StAnnTx, UtxoEnv, Utxos}
import xyz.kd5ujc.scarab.ledger.state.{Alonzo, Babbage, Conway, EraTag, ShelleyBasedEra}
import xyz.kd5ujc.scarab.plutus.*

/** The flat UTXOS's evaluation of the scripts (`Utxos.scripts`, which the eras before Conway run)
  * passes the era through collection to each script's context, including chains whose
  * epoch-triggered hard fork kept the previous protocol version (cardano-ledger f649f975). The
  * fixed evaluator checks the context delivered to it, independently of CEK costs. Conway's era
  * contexts are `EraPlutusContextSuite` 's; its cases here hold the flat contexts, which the
  * collection shares across the languages.
  */
class ScriptContextEraSuite extends FunSuite:
  private val script = Script.plutus(ScriptKind.PlutusV1, Bytes(Vector(1.toByte)))
  private val key = TestLedger.key()
  private val input = TestLedger.txIn(40)
  private val reference = TestLedger.txIn(41)
  private val plain = TestLedger.out(key.address(), 2000000)
  private val bootstrap = xyz.kd5ujc.scarab.ledger.byron.ByronAddress.make(
    xyz.kd5ujc.scarab.ledger.byron.ByronAddress.VerKey,
    Bytes(Vector.fill(64)(7.toByte)),
    None
  )
  private val byron = TxOut(
    Address.decode(bootstrap).fold(e => fail(e.toString), identity),
    Value.coin(2000000)
  )
  private val redeemer = Redeemer(1, 0, Bytes(Vector(1.toByte)), ExUnits(10, 10))
  private val ctx = TestLedger.env.copy(evaluator = TestLedger.scripted(succeeds = true))

  private def tx(
      outputs: Vector[TxOut] = Vector(plain),
      refs: Vector[TxIn] = Vector.empty,
      ttl: Option[BigInt] = None,
      certs: Vector[Certificate] = Vector.empty,
      redeemers: Vector[Redeemer] = Vector(redeemer)
  ): Tx =
    val body = TestLedger.body(
      "context-era",
      Vector(input),
      outputs,
      1,
      referenceInputs = refs,
      ttl = ttl,
      certificates = certs,
      mint = if certs.isEmpty then Map(script.hash -> Map(Bytes.empty -> BigInt(1))) else Map.empty
    )
    Tx(body, Witnesses.empty.copy(scripts = Vector(script), redeemers = redeemers), 0, true, None)

  /** `Utxos.scripts` of the era `E` as a rule of its own. */
  private def scripts[E <: EraTag](using
      ShelleyBasedEra[E]
  ): JudgedSts[UtxoEnv[Any], PlutusStats, StAnnTx, PredicateFailure, LedgerFailure] =
    new JudgedSts[UtxoEnv[Any], PlutusStats, StAnnTx, PredicateFailure, LedgerFailure]:
      def transition[F[_]: Monad](env: UtxoEnv[Any], ann: StAnnTx)(using
          ask: Ask[F, Globals],
          st: Stateful[F, PlutusStats],
          raise: Raise[F, LedgerFailure],
          judge: Judge[F, PredicateFailure]
      ): F[Unit] = Utxos.scripts[F, E](env, ann)

  private def run[E <: EraTag](
      pv: Int,
      transaction: Tx,
      env: TxEnv = ctx
  )(using ShelleyBasedEra[E]): Either[LedgerFailure, PlutusStats] =
    val pp = TestLedger.params(pv).copy(costModels = Map(0 -> Vector.empty))
    JudgedSts(scripts[E])(LedgerFailure.Rejected(transaction.body.id, _))(
      TestLedger.globals,
      UtxoEnv(env, pp, ()),
      PlutusStats.zero,
      StAnnTx(transaction, Utxo.fromMap(Map(input -> plain, reference -> plain)))
    )

  private def bad(transaction: Tx, error: TranslationError): LedgerFailure =
    LedgerFailure.Rejected(
      transaction.body.id,
      PredicateFailure.utxo(
        UtxoFailure.Utxos(
          UtxosFailure.CollectErrors(NonEmptyVector.one(CollectError.BadTranslation(error)))
        )
      )
    )

  test("Conway at version 8 collects V1 reference inputs (Conway/TxInfo.hs:410-411)") {
    val transaction = tx(refs = Vector(reference))
    assert(run[Conway](8, transaction).isRight)
  }

  test("Conway at version 8 ignores a V1 output's reference script (Conway/TxInfo.hs:315-320)") {
    val transaction = tx(outputs = Vector(plain.copy(scriptRef = Some(script))))
    assert(run[Conway](8, transaction).isRight)
  }

  test("Babbage at version 6 refuses a bootstrap output (Babbage/TxInfo.hs:125)") {
    val transaction = tx(outputs = Vector(byron, plain))
    assertEquals(
      run[Babbage](6, transaction),
      Left(bad(transaction, TranslationError.ByronTxOutInContext(TxOutSource.TxOutFromOutput(0))))
    )
  }

  test("Alonzo at version 7 drops bootstrap outputs (Alonzo/Plutus/TxInfo.hs:148-149)") {
    assert(run[Alonzo](7, tx(outputs = Vector(byron, plain))).isRight)
  }

  private def upperBound(inclusive: Boolean, pv: Int): ScriptEvaluator[Id] = request =>
    assertEquals(request.protocolMajor, pv)
    val interval = request.args.last match
      case PlutusData.Constr(_, Vector(PlutusData.Constr(_, fields), _)) => fields(6)
      case other => fail(s"not a V1 context: $other")
    val upper = interval match
      case PlutusData.Constr(_, Vector(_, PlutusData.Constr(_, Vector(_, closed)))) => closed
      case other => fail(s"not an interval: $other")
    assertEquals(upper, PlutusData.bool(inclusive))
    EvalResult.Success(ExUnits(1, 1), Vector.empty)

  test("Conway at version 8 has an exclusive open-lower upper bound (Conway/TxInfo.hs:798-803)") {
    assert(run[Conway](8, tx(ttl = Some(1200)), ctx.copy(evaluator = upperBound(false, 8))).isRight)
  }

  test(
    "Babbage at version 9 keeps its inclusive open-lower upper bound (Alonzo/Plutus/TxInfo.hs:260-263)"
  ) {
    assert(run[Babbage](9, tx(ttl = Some(1200)), ctx.copy(evaluator = upperBound(true, 9))).isRight)
  }

  private val cert = Certificate.Unregister(Credential.Script(script.hash), None)
  private val certRedeemer = redeemer.copy(tag = 2)

  test(
    "Babbage at version 9 reuses a duplicate certificate's first pointer (Alonzo/UTxO.hs:341-357)"
  ) {
    assert(
      run[Babbage](9, tx(certs = Vector(cert, cert), redeemers = Vector(certRedeemer))).isRight
    )
  }

  test("Conway at version 8 uses each certificate's own pointer (Conway/UTxO.hs:72-80)") {
    val transaction = tx(certs = Vector(cert, cert), redeemers = Vector(certRedeemer))
    assertEquals(
      run[Conway](8, transaction),
      Left(
        LedgerFailure.Rejected(
          transaction.body.id,
          PredicateFailure.utxo(
            UtxoFailure.Utxos(
              UtxosFailure.CollectErrors(
                NonEmptyVector.one(CollectError.NoRedeemer(Purpose.Certifying(1, cert)))
              )
            )
          )
        )
      )
    )
  }
