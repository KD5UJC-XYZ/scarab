// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.plutus.ExUnits
import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.crypto.witness.{StrictEd25519, VerificationResult}
import munit.FunSuite
import scala.jdk.CollectionConverters.*

/** Real Preview (protocol version 11) Conway blocks with governance and Plutus V3: the
  * state-independent parts of the rules. `fixtures/preview/conway-governance.hex` holds the blocks,
  * `conway-governance.json` what an explorer reported for their transactions and the cost models of
  * the epochs, so decoding, transaction ids, witnesses and the script data hash are checked against
  * independent data. Full validation needs the ledger state at the block and is out of reach here.
  */
class PreviewConwayGovernanceSuite extends FunSuite:
  private val manifest: Json =
    parse(Files.readString(Path.of("fixtures/preview/conway-governance.json")))
      .fold(e => fail(e.getMessage), identity)

  private val blocks: Vector[Block.Shelley] =
    Files
      .readAllLines(Path.of("fixtures/preview/conway-governance.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e.toString), identity))
      .map(Block.decodeShelley(_).fold(e => fail(e.message), identity))
      .collect { case b: Block.Shelley => b }

  private val txs: Vector[(Block.Shelley, Tx)] =
    blocks.flatMap(b =>
      b.transactions.map(t =>
        b -> Tx.fromBlock(t, b.era).fold(e => fail(s"tx ${t.id.hex}: $e"), identity)
      )
    )

  private val byId: Map[String, (Block.Shelley, Tx)] =
    txs.map(x => x._2.body.id.hex -> x).toMap

  private def tx(id: String): Tx =
    byId.getOrElse(id, fail(s"transaction $id is not in the fixture"))._2

  private def field(j: Json, name: String): Json =
    j.hcursor.downField(name).focus.getOrElse(Json.Null)
  private def text(j: Json, name: String): String = field(j, name).asString.getOrElse("")
  private def array(j: Json, name: String): Vector[Json] =
    field(j, name).asArray.getOrElse(Vector.empty)

  test(
    "the captured Conway blocks decode, commit to their bodies and run protocol version 10 or 11"
  ) {
    assert(blocks.size >= 20)
    blocks.foreach { b =>
      assertEquals(b.bodyHash, b.header.bodyHash)
      assert(b.header.protocolVersion._1 >= 10)
    }
    assert(txs.size > 40)
  }

  test("every vkey witness of every transaction verifies against the transaction id") {
    txs.foreach { case (_, t) =>
      assertEquals(t.body.id, Blake2b.hash256.hash(t.body.raw))
      t.witnesses.vkeys.foreach { case (vkey, signature) =>
        assertEquals(
          StrictEd25519.verify(vkey, signature, t.body.id),
          Right(VerificationResult.SignatureVerified),
          s"tx ${t.body.id.hex}"
        )
      }
    }
  }

  test("proposal transactions decode to the proposals the explorer lists") {
    val expected = array(manifest, "proposals")
    assertEquals(expected.size, 14)
    expected.foreach { entry =>
      val id = text(entry, "id")
      val body = tx(id).body
      val listed = array(entry, "proposals")
      assertEquals(body.proposals.size, listed.size, s"proposals of $id")
      listed.foreach { p =>
        val decoded = body.proposals(field(p, "index").as[Int].toOption.get)
        assertEquals(decoded.deposit, field(p, "deposit").as[BigInt].toOption.get)
        assertEquals(
          Bytes(decoded.returnAccount.credential.hash.value).hex,
          text(p, "returnAccount").drop(2),
          s"return account of $id"
        )
        val previous = text(p, "previous")
        val (kind, prev) = decoded.action match
          case GovAction.ParameterChange(pr, _, _)    => ("ParameterChange", pr)
          case GovAction.HardFork(pr, _, _)           => ("HardForkInitiation", pr)
          case GovAction.TreasuryWithdrawals(_, _)    => ("TreasuryWithdrawals", None)
          case GovAction.NoConfidence(pr)             => ("NoConfidence", pr)
          case GovAction.UpdateCommittee(pr, _, _, _) => ("NewCommittee", pr)
          case GovAction.NewConstitution(pr, _, _)    => ("NewConstitution", pr)
          case GovAction.Info                         => ("InfoAction", None)
        assertEquals(kind, text(p, "type"), s"kind of $id")
        assertEquals(prev.map(i => s"${i.txId.hex}#${i.index}").getOrElse(""), previous)
        // the explorer's rendering of the action
        val description = field(p, "description")
        decoded.action match
          case GovAction.TreasuryWithdrawals(ws, policy) =>
            val listedAmounts = array(description, "contents").head.asArray.get
              .map(e => e.asArray.get(1).as[BigInt].toOption.get)
            assertEquals(ws.map(_._2).toList, listedAmounts.toList)
            assertEquals(
              policy.map(_.hex),
              array(description, "contents").lift(1).flatMap(_.asString)
            )
          case GovAction.ParameterChange(_, update, policy) =>
            val parameters = array(description, "contents")(1).asObject.get
            assertEquals(update.fields.size, parameters.size)
            assertEquals(
              policy.map(_.hex),
              array(description, "contents").lift(2).flatMap(_.asString)
            )
          case GovAction.HardFork(_, major, minor) =>
            val version = array(description, "contents")(1)
            assertEquals(field(version, "major").as[BigInt].toOption, Some(major))
            assertEquals(field(version, "minor").as[BigInt].toOption, Some(minor))
          case GovAction.UpdateCommittee(_, removed, added, _) =>
            val contents = array(description, "contents")
            assertEquals(contents(1).asArray.get.size, removed.size)
            assertEquals(
              contents(2).asObject.map(_.size).orElse(contents(2).asArray.map(_.size)),
              Some(added.size)
            )
          case GovAction.NewConstitution(_, script, anchor) =>
            val constitution = array(description, "contents")(1)
            assertEquals(text(field(constitution, "anchor"), "url"), anchor.url)
            assertEquals(text(field(constitution, "anchor"), "dataHash"), anchor.hash.hex)
            assertEquals(field(constitution, "script").asString, script.map(_.hex))
          case _ => ()
      }
    }
  }

  test("vote transactions decode to the votes the explorer lists") {
    array(manifest, "votes").foreach { entry =>
      val id = text(entry, "id")
      val body = tx(id).body
      val cast = body.votes.flatMap { case (voter, ps) =>
        ps.map { case (aid, v) => (voter, aid, v.vote) }
      }
      array(entry, "votes").foreach { v =>
        val hash = text(v, "voterHash")
        val proposal = (text(v, "proposalTx"), field(v, "proposalIndex").as[Int].toOption.get)
        val vote = text(v, "vote") match
          case "Yes"     => Vote.Yes
          case "No"      => Vote.No
          case "Abstain" => Vote.Abstain
        val found = cast.exists { case (voter, aid, cv) =>
          val h = voter match
            case Voter.DRepVoter(c)    => c.hash.hex
            case Voter.CommitteeHot(c) => c.hash.hex
            case Voter.Pool(p)         => p.hex
          h == hash && aid.txId.hex == proposal._1 && aid.index == BigInt(proposal._2) && cv == vote
        }
        assert(found, s"vote of ${text(v, "voterKind")} $hash in $id")
      }
    }
  }

  test("the DRep registration certificate carries the deposit and the anchor") {
    val entry = field(manifest, "drepRegistration")
    val body = tx(text(entry, "id")).body
    val registrations = body.certificates.collect { case c: Certificate.RegisterDRep => c }
    assertEquals(registrations.size, 1)
    assertEquals(registrations.head.credential.hash.hex, text(array(entry, "updates").head, "hex"))
    assertEquals(registrations.head.deposit, BigInt(500000000))
  }

  test(
    "transactions that use Plutus V3 have the script data hash the cost models of their epoch give"
  ) {
    val costModels: Map[String, Map[Int, Vector[BigInt]]] =
      field(manifest, "epochParams").asObject.get.toMap.map { case (epoch, params) =>
        epoch -> Vector("PlutusV1" -> 0, "PlutusV2" -> 1, "PlutusV3" -> 2).flatMap { case (n, ix) =>
          field(field(params, "costModels"), n).as[Vector[BigInt]].toOption.map(ix -> _)
        }.toMap
      }
    val withHash = txs.filter(_._2.body.scriptDataHash.isDefined)
    assert(withHash.exists(_._2.witnesses.scripts.exists(_.kind == ScriptKind.PlutusV3)))
    val subsets = Vector(
      Set.empty[ScriptKind],
      Set(ScriptKind.PlutusV1),
      Set(ScriptKind.PlutusV2),
      Set(ScriptKind.PlutusV3),
      Set(ScriptKind.PlutusV2, ScriptKind.PlutusV3),
      Set(ScriptKind.PlutusV1, ScriptKind.PlutusV2, ScriptKind.PlutusV3)
    )
    withHash.foreach { case (b, t) =>
      val epoch = (b.header.slot / 86400).toString
      val models = costModels.getOrElse(epoch, fail(s"no cost models for epoch $epoch"))
      val pp = TestLedger.params(11).copy(costModels = models)
      val matching = subsets.filter(s => ScriptIntegrity.hash(t, pp, s) == t.body.scriptDataHash)
      assert(matching.nonEmpty, s"script data hash of ${t.body.id.hex} in epoch $epoch")
      if t.witnesses.scripts.exists(_.kind == ScriptKind.PlutusV3) then
        assert(matching.forall(_.contains(ScriptKind.PlutusV3)), s"V3 in ${t.body.id.hex}")
    }
  }

  test(
    "redeemers in the map and in the list form decode and every purpose names an existing item"
  ) {
    txs.filter(_._2.witnesses.redeemers.nonEmpty).foreach { case (_, t) =>
      assert(
        t.witnesses.redeemersRaw
          .exists(_.value.headOption.exists(b => (b & 0xe0) == 0xa0 || (b & 0xe0) == 0x80))
      )
      assert(
        t.witnesses.redeemers.size == t.witnesses.redeemers.map(r => (r.tag, r.index)).distinct.size
      )
      t.witnesses.redeemers.foreach { r =>
        r.tag match
          case 0     => assert(r.index < t.body.inputs.size)
          case 1     => assert(r.index < t.body.mint.size)
          case 2     => assert(r.index < t.body.certificates.size)
          case 3     => assert(r.index < t.body.withdrawals.size)
          case 4     => assert(r.index < t.body.votes.size)
          case 5     => assert(r.index < t.body.proposals.size)
          case other => fail(s"redeemer tag $other")
      }
    }
  }

  test("fees cover the Conway minimum fee and outputs the minimum value, as the chain required") {
    val parameters = field(manifest, "epochParams").asObject.get.toMap
    def decimal(j: Json, name: String): Rational =
      Rational.fromDecimal(field(j, name).as[BigDecimal].toOption.get)
    var exact = 0
    // transactions that use no reference input need no knowledge of the ledger state for the fee
    txs.filter(_._2.body.referenceInputs.isEmpty).foreach { case (b, t) =>
      val p = parameters((b.header.slot / 86400).toString)
      val pp = TestLedger
        .params(11)
        .copy(
          minFeeA = field(p, "minFeeA").as[BigInt].toOption.get,
          minFeeB = field(p, "minFeeB").as[BigInt].toOption.get,
          priceMem = decimal(p, "priceMem"),
          priceSteps = decimal(p, "priceSteps"),
          coinsPerUTxOByte = field(p, "coinsPerUtxoByte").as[BigInt].toOption.get
        )
      val units = t.witnesses.totalExUnits
      val scriptFee =
        (pp.priceMem * Rational(units.mem) + pp.priceSteps * Rational(units.steps)).ceil
      val minimum = pp.minFeeA * t.size + pp.minFeeB + scriptFee
      assert(t.body.fee >= minimum, s"fee of ${t.body.id.hex}: ${t.body.fee} < $minimum")
      // wallets estimate the size with a few bytes to spare, so the fee exceeds the minimum by whole
      // multiples of the per-byte fee
      if units == ExUnits.zero then
        assertEquals((t.body.fee - minimum) % pp.minFeeA, BigInt(0), s"fee of ${t.body.id.hex}")
      if t.body.fee - minimum <= pp.minFeeA * 2 then exact += 1
      t.body.outputs.foreach { o =>
        assert(
          o.value.coin >= (160 + o.ledgerSize) * pp.coinsPerUTxOByte,
          s"output of ${t.body.id.hex}"
        )
      }
    }
    assert(exact >= 3, s"only $exact transactions pay within two bytes of the minimum fee")
  }
