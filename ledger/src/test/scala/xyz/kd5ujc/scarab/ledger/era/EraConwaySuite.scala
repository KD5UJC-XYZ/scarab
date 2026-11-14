// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import java.nio.file.{Files, Path}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Block,
  CborRead,
  Certificate,
  LedgerDecodeError,
  Purpose,
  TxBody as FlatTxBody
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayTxBody, ConwayTxCert}
import xyz.kd5ujc.scarab.protocol.Era

/** Conway's types (stage 2 of `docs/design/era-typed-transactions.md`): every recorded Conway body
  * read as a `ConwayTxBody` has, through Conway's classes, every field the flat decoder reads; and
  * what only the projection from the flat body refuses, the flat Conway decoder never produces.
  */
class EraConwaySuite extends munit.FunSuite:
  private lazy val bodies: Vector[Bytes] =
    Vector(
      "fixtures/eras/blocks.hex",
      "fixtures/preview/conway.hex",
      "fixtures/preview/conway-governance.hex"
    )
      .flatMap(f => Files.readString(Path.of(f)).linesIterator)
      .map(_.trim.split("\\s+").last)
      .filter(_.nonEmpty)
      .flatMap(h => Bytes.fromHex(h).toOption)
      .flatMap(raw => Block.decodeShelley(raw).toOption)
      .collect { case b: Block.Shelley if b.era == Era.Conway => b }
      .flatMap(_.transactions.map(_.body))

  private val body = EraTxBody.conway
  private given body.Level[TxLevel.Top] = body.top

  test("every recorded Conway body reads with every field of the flat decoder's") {
    assert(bodies.nonEmpty)
    val certs = bodies.map { raw =>
      val flat = FlatTxBody.decode(raw, Era.Conway).toOption.get
      val c = ConwayTxBody.decode(raw).toOption.get
      assertEquals(body.id[TxLevel.Top](c), flat.id)
      assertEquals(body.inputs[TxLevel.Top](c), flat.inputs)
      assertEquals(body.outputs[TxLevel.Top](c).map(body.outputsOf.raw), flat.outputs.map(_.raw))
      assertEquals(body.fee(c), flat.fee)
      assertEquals(body.withdrawals[TxLevel.Top](c), flat.withdrawals)
      assertEquals(body.auxDataHash[TxLevel.Top](c), flat.auxDataHash)
      assertEquals(body.vldt[TxLevel.Top](c), ValidityInterval(flat.validityStart, flat.ttl))
      assertEquals(body.mint[TxLevel.Top](c), flat.mint)
      assertEquals(body.collateralInputs(c), flat.collateral)
      assertEquals(body.reqSignerHashes[TxLevel.Top](c), flat.requiredSigners)
      assertEquals(body.scriptIntegrityHash[TxLevel.Top](c), flat.scriptDataHash)
      assertEquals(body.networkId[TxLevel.Top](c), flat.networkId)
      assertEquals(body.referenceInputs[TxLevel.Top](c), flat.referenceInputs)
      assertEquals(body.totalCollateral(c), flat.totalCollateral)
      assertEquals(
        body.collateralReturn(c).map(body.outputsOf.raw),
        flat.collateralReturn.map(_.raw)
      )
      assertEquals(body.currentTreasuryValue[TxLevel.Top](c), flat.currentTreasury)
      assertEquals(body.votingProcedures[TxLevel.Top](c), flat.votes)
      assertEquals(body.proposalProcedures[TxLevel.Top](c), flat.proposals)
      assertEquals(body.treasuryDonation[TxLevel.Top](c), flat.donation)
      // a certificate's script witness is the one the flat rules ask for
      val typed = body.certs[TxLevel.Top](c)
      assertEquals(typed.size, flat.certificates.size)
      assertEquals(
        typed.map(body.certsOf.getScriptWitnessTxCert),
        flat.certificates.map(Purpose.certificateScript)
      )
      typed.size
    }.sum
    assert(certs > 0, "no recorded Conway body has a certificate")
  }

  private def node(hex: String) = Bytes.fromHex(hex).flatMap(CborRead.decode(_)).toOption.get

  test(
    "Conway's decoder reads no genesis delegation or instantaneous rewards, so neither projects"
  ) {
    val genesis = node(s"8405581c${"11" * 28}581c${"22" * 28}5820${"33" * 32}")
    val mir = node("82068200a0")
    Vector(
      genesis -> LedgerDecodeError.Retired.GenesisDelegation,
      mir -> LedgerDecodeError.Retired.MoveInstantaneousRewards
    ).foreach { (n, retired) =>
      val refused = LedgerDecodeError.RetiredCertificate(retired)
      assertEquals(Certificate.decode(n, Era.Conway), Left(refused))
      assertEquals(ConwayTxCert.decode(n), Left(EraTxError.Decode(refused)))
      // Babbage's decoder reads them, and the projection into Conway refuses them
      assert(Certificate.decode(n, Era.Babbage).isRight)
    }
  }

  test("Conway's decoder reads no protocol-parameter update, so none projects") {
    // `{0: [input], 1: [], 2: 0, 6: [{}, 0]}`
    val withUpdate = Bytes.fromHex(s"a400818258${"20"}${"44" * 32}00018002000682a000").toOption.get
    val flat = FlatTxBody.decode(withUpdate, Era.Conway)
    assert(flat.isLeft, flat)
    assertEquals(
      ConwayTxBody.decode(withUpdate).left.toOption,
      flat.left.toOption.map(EraTxError.Decode(_))
    )
  }
