// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.conformance

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as CborValue}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
  BootstrapWitness,
  GovAction,
  Block,
  ByteStrings,
  Credential,
  DecodeField,
  DecoderVersion,
  DetachedRecords,
  GovActionId,
  Header,
  LedgerDecodeError,
  Metadatum,
  RewardAccount,
  TxIn,
  Value,
  Voter,
  VotingProcedure
}
import xyz.kd5ujc.scarab.ledger.dijkstra.{
  DijkstraAnchor,
  DijkstraAuxData,
  DijkstraCert,
  DijkstraDelegatee,
  DijkstraDatum,
  DijkstraGovActionId,
  DijkstraLevel,
  DijkstraParamUpdate,
  DijkstraProposal,
  DijkstraRelay,
  DijkstraScript,
  DijkstraTx,
  DijkstraTxIn,
  DijkstraTxOut,
  DijkstraValue,
  DijkstraVoting,
  DijkstraVoter,
  DijkstraVotingProcedure,
  DijkstraWitnesses,
  V12
}
import xyz.kd5ujc.scarab.ledger.state.{ProtVer, Word64}
import xyz.kd5ujc.scarab.models.runtime.Codec
import xyz.kd5ujc.scarab.plutus.PlutusData
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.storage.haskell.state.{
  ConwayCodecs,
  DijkstraCodecs,
  HaskellStateCodecs,
  UtxoCodecs,
  Wire
}

/** The rules of the Dijkstra corpus, each mapped to the decoder of the Leios testnet's Dijkstra
  * that Scarab reads a Leios block with ([[DijkstraTx]], [[DijkstraWitnesses]] and the readers of
  * [[V12]], at decoder version 12). A type that shares its encoding with Conway is written from its
  * decoded fields by the shared production encoder. Other samples are written from the bytes their
  * decoded value keeps, by a production codec of a scalar, or by a Haskell-format codec of the
  * ledger state where its value is a type that codec writes. Missing writers remain failures.
  */
object DijkstraRules:
  private val era = Era.Dijkstra

  /** The decoder version every rule of this era is read and written at: the era's `eraProtVerHigh`,
    * 12, as the dataset's verify reads and writes a sample (`app/Generation/EraSpec.hs:81-82, 197`,
    * dataset ed6c49a4). It is also Dijkstra's `eraProtVerLow`, the version the readers of [[V12]]
    * take.
    */
  val version: DecoderVersion = DecoderVersion.high(era)
  private given ByteStrings = ByteStrings.of(version)
  private given ProtVer = ProtVer(Word64.fromUnsigned(version.value.toLong), Word64.zero)

  import Embedding.*
  import Step.*

  /** A part as production reads it: `DetachedRecords.read` with the shape of the era's body at
    * [[version]], which from 12 holds no `RecD` record anywhere ([[DetachedRecords.Shapes]]), so
    * every part of it is an item read as it is written.
    */
  private def node(bytes: Bytes): Either[Refusal, Node] =
    DetachedRecords.read(bytes, Cbor.Limits.Unbounded, DetachedRecords.Shapes.body(era, version))(
      e => Refusal.Ledger(LedgerDecodeError.Syntax(e))
    )(Right(_))

  private def ledger[A](r: Either[LedgerDecodeError, A]) = r.leftMap(Refusal.Ledger(_))

  private def read[A](f: Node => Either[LedgerDecodeError, A])(b: Bytes) =
    node(b).flatMap(n => ledger(f(n)))

  private def written[A](codec: Codec[A])(a: A) = ConwayRules.written(codec)(a)
  private val unsigned: Codec[BigInt] = Codec.uintBig((BigInt(1) << 64) - 1)
  private def leafBytes(b: Bytes) = written(Codec.bytes)(b)

  private def kept[A](decoder: String, keeps: String)(f: Node => Either[LedgerDecodeError, A])(
      raw: A => Bytes
  ) =
    DatasetRule(decoder, Reach.Direct, Writing.Kept(keeps))(read(f))(a => Right(raw(a)))

  private def shaped(shape: Shape)(b: Bytes): Either[Refusal, Node] =
    node(b).flatMap { n =>
      val fits = (shape, n.value) match
        case (Shape.Map, CborValue.Map(_))                   => true
        case (Shape.Array, CborValue.Arr(_))                 => true
        case (Shape.Tag259, CborValue.Tag(t, _)) if t == 259 => true
        case _                                               => false
      Either.cond(fits, n, Refusal.Form(shape))
    }

  private def auxiliaryRule(shape: Shape) =
    DatasetRule(
      s"DijkstraAuxData.decode (${shape.render} only)",
      Reach.Direct,
      Writing.Kept("DijkstraAuxData.raw")
    )(b => shaped(shape)(b).flatMap(n => ledger(DijkstraAuxData.decode(n))))(a => Right(a.raw))

  private def leafHash(size: Int, site: DecodeField) =
    DatasetRule(s"V12.bytesN($size)", Reach.Direct, Writing.Leaf(s"Wire.hash($size)"))(
      read(V12.bytesN(_, size, site))
    )(written(Wire.hash(size)))

  private def word64(site: DecodeField) =
    DatasetRule("V12.word64", Reach.Direct, Writing.Leaf("Codec.uintBig"))(
      read(V12.word64(_, site))
    )(
      written(unsigned)
    )

  private def credential(site: DecodeField) =
    DatasetRule(
      "Credential.decode",
      Reach.Direct,
      Writing.Encoder("HaskellStateCodecs.nodeCredential")
    )(
      read(Credential.decode(_, site))
    )(written(HaskellStateCodecs.nodeCredential))

  /** Plutus data of `shape`, read as a datum of a witness set and written again as the bytes the
    * witness set keeps of it ([[DijkstraWitnesses.datums]]), as the reference keeps those of
    * `Data era`.
    */
  private def dataRule(shape: Option[Shape]) =
    DatasetRule(
      "DijkstraWitnesses.decode",
      Reach.Embedded("a witness set's datums"),
      Writing.Kept("DijkstraWitnesses.datums")
    )(b =>
      read(DijkstraWitnesses.decode)(hex("a10481") ++ b)
        .flatMap(w => w.datums.headOption.toRight(Refusal.Form(Shape.Present)))
        .flatMap { kept =>
          PlutusData.decode(kept).leftMap(Refusal.Data(_)).flatMap { d =>
            (shape, d) match
              case (None, _)                                 => Right(kept)
              case (Some(Shape.Integer), _: PlutusData.I)    => Right(kept)
              case (Some(Shape.ByteString), _: PlutusData.B) => Right(kept)
              case (Some(s), _)                              => Left(Refusal.Form(s))
          }
        }
    )(Right(_))

  /** A Leios header whose body holds the sample at `field`, the item at `path` of the body's
    * original bytes, which the Leios header keeps and signs.
    */
  private def headerLeaf(field: Int, path: List[Step], inner: Bytes => Bytes = identity) =
    DatasetRule(
      "Header.fromRaw (LeiosHeader)",
      Reach.Embedded("a Leios header body"),
      Writing.Kept("the header body's original bytes (LeiosHeader.signable)")
    )(b =>
      Header
        .fromRaw(era, headerAround(headerBody(field, inner(b), leios = true)), version)
        .leftMap(Refusal.Chain(_))
    )(h => Step.at(h.signable, Index(field) :: path))

  /** A top-level Dijkstra body of no inputs, no outputs and no fee, with `key` holding the sample.
    */
  private def body(key: String)(sample: Bytes) =
    read(DijkstraTx.body(_, DijkstraLevel.Top))(hex("a4008001800200" + key) ++ sample)

  private def relay(prefix: String, suffix: String = "")(sample: Bytes) =
    read(DijkstraRelay.decode)(hex(prefix) ++ sample ++ hex(suffix))

  /** A proposal procedure of a zero deposit, a testnet key reward account and an empty anchor. */
  private def proposal(prefix: String, suffix: String = "")(sample: Bytes) =
    read(DijkstraProposal.decode)(
      hex("8400581de0" + zeros(28) + prefix) ++ sample ++ hex(suffix + "8260" + "5820" + zeros(32))
    )

  private def output(prefix: String, suffix: String)(sample: Bytes) =
    read(DijkstraTxOut.decode)(hex(prefix) ++ sample ++ hex(suffix))

  private val govActionCodec = Writing.Encoder("HaskellStateCodecs.govAction")
  private val valueCodec = Writing.Encoder("UtxoCodecs.value")
  private val multiassetCodec = Writing.Encoder("UtxoCodecs.multiasset")

  private def writeMultiasset(assets: Map[Bytes, Map[Bytes, BigInt]]) =
    written(UtxoCodecs.multiasset)(assets)

  private def notEmbedded(parent: String) = DatasetRule.Unmapped(Unmapped.NotEmbedded(parent))

  /** These types have the same `EncCBOR` in both eras: voters and votes
    * (`Conway/Governance/Procedures.hs:365-404,469-474`, cardano-ledger f649f975), vkey and
    * bootstrap witnesses (`Keys/WitVKey.hs:70-74`, `Keys/Bootstrap.hs:93-100`). The Leios pin
    * 9df9f98c keeps these encodings (`Procedures.hs:394-405,508-513`, `WitVKey.hs:100-104`,
    * `Bootstrap.hs:100-107`). Their Dijkstra decoders remain the readers; only decoded fields are
    * passed to the shared writers.
    */
  private def conwayVoter(v: DijkstraVoter): Voter = v match
    case DijkstraVoter.Committee(c) => Voter.CommitteeHot(c)
    case DijkstraVoter.DRep(c)      => Voter.DRepVoter(c)
    case DijkstraVoter.StakePool(h) => Voter.Pool(h)

  private val votingCodec = Writing.Encoder("ConwayCodecs.votingProcedures")

  private def writeVoting(
      all: Vector[(DijkstraVoter, Vector[(DijkstraGovActionId, DijkstraVotingProcedure)])]
  ) = written(ConwayCodecs.votingProcedures)(all.map { (v, votes) =>
    conwayVoter(v) -> votes.map { (id, p) =>
      GovActionId(id.txId, BigInt(id.index)) -> VotingProcedure(
        p.vote,
        p.anchor.map(a => Anchor(a.url, a.hash))
      )
    }
  })

  /** The rules, by the name of their directory in the corpus. */
  /** `certificates`, the set at a body's key 4, which refuses it empty (`DijkstraTx.body`), as the
    * reference's body does where the set's own decoder takes an empty one.
    */
  private def certificates: DatasetRule = DatasetRule
    .apply(
      "DijkstraTx.body (Top)",
      Reach.Embedded("a body's key 4"),
      Writing.Encoder("DijkstraCodecs.certificates")
    )(body("04"))(t => written(DijkstraCodecs.certificates)(t.certificates))

  /** `voting_procedures`, non-empty, as the body that holds them requires (`DijkstraTx.body`'s key
    * 19).
    */
  private def votingProcedures: DatasetRule =
    DatasetRule("DijkstraVoting.procedures, non-empty", Reach.Direct, votingCodec)(
      read(n => DijkstraVoting.procedures(n).flatMap(V12.nonEmpty(_, DecodeField.VotingProcedures)))
    )(writeVoting)

  /** `proposal_procedures`, the set at a body's key 20, which refuses it empty, as for the
    * certificates.
    */
  private def proposalProcedures: DatasetRule = DatasetRule
    .apply(
      "DijkstraTx.body (Top)",
      Reach.Embedded("a body's key 20"),
      Writing.Encoder("DijkstraCodecs.proposals")
    )(body("14"))(t => written(DijkstraCodecs.proposals)(t.proposals))

  val rules: Map[String, DatasetRule] = Map(
    "block" -> DatasetRule.unwritten(
      "Block.decodeShelley (LeiosBlock)",
      Reach.Embedded("[8, block]")
    )(b => Block.decodeShelley(hex("8208") ++ b, _ => version).leftMap(Refusal.Chain(_))),
    "header" -> DatasetRule(
      "Header.fromRaw (LeiosHeader)",
      Reach.Direct,
      Writing.Kept("Header.Shelley.raw")
    )(b => Header.fromRaw(era, b, version).leftMap(Refusal.Chain(_)))(h => Right(h.raw)),
    "header_body" -> DatasetRule(
      "Header.fromRaw (LeiosHeader)",
      Reach.Embedded("[body, kes signature]"),
      Writing.Kept("the header body's original bytes (LeiosHeader.signable)")
    )(b => Header.fromRaw(era, headerAround(b), version).leftMap(Refusal.Chain(_)))(h =>
      Right(h.signable)
    ),
    "operational_cert" -> headerLeaf(8, Nil),
    "kes_vkey" -> headerLeaf(8, List(Index(0)), opCert(0, _)),
    "kes_period" -> headerLeaf(8, List(Index(2)), opCert(2, _)),
    "vrf_vkey" -> headerLeaf(4, Nil),
    "block_number" -> headerLeaf(0, Nil),
    "slot" -> headerLeaf(1, Nil),
    "eb_announcement" -> headerLeaf(11, Nil),
    "block_body" -> notEmbedded("a Leios block, after its header"),
    "transaction" -> DatasetRule(
      "DijkstraTx.fromSubmission",
      Reach.Direct,
      Writing.Kept("DijkstraTx.raw")
    )(b => ledger(DijkstraTx.fromSubmission(b)))(t => Right(t.raw)),
    "transaction_body" -> kept("DijkstraTx.body (Top)", "DijkstraTxBody.raw")(
      DijkstraTx.body(_, DijkstraLevel.Top)
    )(
      _.raw
    ),
    "sub_transaction_body" -> kept("DijkstraTx.body (Sub)", "DijkstraTxBody.raw")(
      DijkstraTx.body(_, DijkstraLevel.Sub)
    )(_.raw),
    "transaction_witness_set" -> kept("DijkstraWitnesses.decode", "DijkstraWitnesses.raw")(
      DijkstraWitnesses.decode
    )(
      _.raw
    ),
    "redeemers" -> DatasetRule(
      "DijkstraWitnesses.decode",
      Reach.Embedded("a witness set's key 5"),
      Writing.Kept("DijkstraWitnesses.raw, key 5")
    )(b => read(DijkstraWitnesses.decode)(hex("a105") ++ b))(w => Step.at(w.raw, List(Key(5)))),
    "vkeywitness" -> DatasetRule(
      "DijkstraWitnesses.decode",
      Reach.Embedded("{0: [vkeywitness]}"),
      Writing.Encoder("ConwayCodecs.vkeyWitness")
    )(b =>
      read(DijkstraWitnesses.decode)(hex("a10081") ++ b).flatMap(w =>
        w.vkeys.headOption.toRight(Refusal.Form(Shape.Present))
      )
    )(written(ConwayCodecs.vkeyWitness)),
    "bootstrap_witness" -> DatasetRule(
      "DijkstraWitnesses.decode",
      Reach.Embedded("{2: [bootstrap_witness]}"),
      Writing.Encoder("ConwayCodecs.bootstrapWitness")
    )(b =>
      read(DijkstraWitnesses.decode)(hex("a10281") ++ b).flatMap(w =>
        w.bootstraps.headOption.toRight(Refusal.Form(Shape.Present))
      )
    )(w =>
      written(ConwayCodecs.bootstrapWitness)(
        BootstrapWitness(w.key, w.signature, w.chainCode, w.attributes)
      )
    ),
    "vkey" -> DatasetRule(
      "DijkstraWitnesses.decode",
      Reach.Embedded("a vkey witness"),
      Writing.Leaf("Codec.bytes")
    )(b => read(DijkstraWitnesses.decode)(hex("a1008182") ++ b ++ hex("5840" + zeros(64))))(w =>
      w.vkeys.headOption.toRight(Unwritten.NotKept).flatMap(v => leafBytes(v._1))
    ),
    "signature" -> DatasetRule(
      "DijkstraWitnesses.decode",
      Reach.Embedded("a vkey witness"),
      Writing.Leaf("Codec.bytes")
    )(b => read(DijkstraWitnesses.decode)(hex("a10081825820" + zeros(32)) ++ b))(w =>
      w.vkeys.headOption.toRight(Unwritten.NotKept).flatMap(v => leafBytes(v._2))
    ),
    "auxiliary_data" -> kept("DijkstraAuxData.decode", "DijkstraAuxData.raw")(
      DijkstraAuxData.decode
    )(_.raw),
    "metadata" -> auxiliaryRule(Shape.Map),
    "auxiliary_data_map" -> auxiliaryRule(Shape.Tag259),
    "auxiliary_data_array" -> auxiliaryRule(Shape.Array),
    "auxiliary_scripts" -> DatasetRule(
      "DijkstraAuxData.decode",
      Reach.Embedded("[{}, scripts]"),
      Writing.Kept("DijkstraAuxData.raw")
    )(b => read(DijkstraAuxData.decode)(hex("82a0") ++ b))(a => Step.at(a.raw, List(Index(1)))),
    "metadatum" -> DatasetRule(
      "DijkstraAuxData.decode",
      Reach.Embedded("{0: metadatum}"),
      Writing.Encoder("ConwayCodecs.metadatum")
    )(b =>
      read(DijkstraAuxData.decode)(hex("a100") ++ b)
        .flatMap(a => a.metadata.headOption.toRight(Refusal.Form(Shape.Present)))
        .flatMap((_, raw) => node(raw))
        .flatMap(n => ledger(Metadatum.decode(n, version)))
    )(
      written(ConwayCodecs.metadatum)
    ),
    "native_script" -> DatasetRule(
      "DijkstraScript.native",
      Reach.Direct,
      Writing.Kept("the bytes DijkstraScript.hash is taken over")
    )(b => node(b).flatMap(n => ledger(DijkstraScript.native(n)).tupleLeft(n.original)))(
      (original, script) =>
        Either.cond(
          script.hash == Blake2b.hash224.hash(Bytes(0.toByte +: original.value)),
          original,
          Unwritten.HashMismatch
        )
    ),
    "script" -> DatasetRule(
      "DijkstraScript.script",
      Reach.Direct,
      Writing.Encoder("DijkstraCodecs.script")
    )(
      read(DijkstraScript.script)
    )(written(DijkstraCodecs.script)),
    "plutus_v1_script" -> plutusScript(DecodeField.PlutusV1),
    "plutus_v2_script" -> plutusScript(DecodeField.PlutusV2),
    "plutus_v3_script" -> plutusScript(DecodeField.PlutusV3),
    "plutus_data" -> dataRule(None),
    "%constr<plutus_data>" -> dataRule(None),
    "big_int" -> dataRule(Some(Shape.Integer)),
    "bounded_bytes" -> dataRule(Some(Shape.ByteString)),
    "datum_option" -> DatasetRule(
      "DijkstraDatum.decode",
      Reach.Direct,
      Writing.Encoder("DijkstraCodecs.datum")
    )(
      read(DijkstraDatum.decode)
    )(written(DijkstraCodecs.datum)),
    "data" -> DatasetRule
      .apply(
        "DijkstraTxOut.decode",
        Reach.Embedded("an output's inline datum"),
        Writing.Encoder("DijkstraCodecs.txOut")
      )(
        output("a300581d61" + zeros(28) + "0100028201", "")
      )(o => written(DijkstraCodecs.txOut)(o).flatMap(Step.at(_, List(Key(2), Index(1))))),
    "transaction_input" -> DatasetRule(
      "DijkstraTxIn.decode",
      Reach.Direct,
      Writing.Encoder("UtxoCodecs.txIn")
    )(
      read(DijkstraTxIn.decode)
    )(i => written(UtxoCodecs.txIn)(TxIn(i.id, BigInt(i.index)))),
    "transaction_output" -> DatasetRule(
      "DijkstraTxOut.decode",
      Reach.Direct,
      Writing.Encoder("DijkstraCodecs.txOut")
    )(read(DijkstraTxOut.decode))(
      written(DijkstraCodecs.txOut)
    ),
    "babbage_transaction_output" -> DatasetRule(
      "DijkstraTxOut.decode",
      Reach.Direct,
      Writing.Encoder("DijkstraCodecs.txOut")
    )(
      read(DijkstraTxOut.decode)
    )(written(DijkstraCodecs.txOut)),
    "alonzo_transaction_output" -> DatasetRule(
      "DijkstraTxOut.decode (an array only)",
      Reach.Direct,
      Writing.Encoder("DijkstraCodecs.txOut")
    )(b => shaped(Shape.Array)(b).flatMap(n => ledger(DijkstraTxOut.decode(n))))(
      written(DijkstraCodecs.txOut)
    ),
    "value" -> DatasetRule("DijkstraValue.decode", Reach.Direct, valueCodec)(
      read(DijkstraValue.decode)
    )(v => written(UtxoCodecs.value)(Value(v.coin, v.assets))),
    "%multiasset<positive_coin>" -> DatasetRule(
      "DijkstraValue.decode",
      Reach.Embedded("[0, multiasset]"),
      multiassetCodec
    )(b => read(DijkstraValue.decode)(hex("8200") ++ b))(v => writeMultiasset(v.assets)),
    "mint" -> DatasetRule(
      "DijkstraTx.body (Top)",
      Reach.Embedded("a body's key 9"),
      multiassetCodec
    )(
      body("09")
    )(t => writeMultiasset(t.mint)),
    "nonzero_int64" -> DatasetRule(
      "DijkstraTx.body (Top)",
      Reach.Embedded("a mint quantity of a body"),
      multiassetCodec
    )(b => body("09")(hex("a1581c" + zeros(28) + "a140") ++ b))(t =>
      writeMultiasset(t.mint).flatMap(Step.at(_, List(FirstValue, FirstValue)))
    ),
    "asset_name" -> DatasetRule(
      "DijkstraTx.body (Top)",
      Reach.Embedded("an asset name of a body's mint"),
      valueCodec
    )(b => body("09")(hex("a1581c" + zeros(28) + "a1") ++ b ++ hex("01")))(t =>
      writeMultiasset(t.mint).flatMap(Step.at(_, List(FirstValue, FirstKey)))
    ),
    "withdrawals" -> DatasetRule(
      "DijkstraTx.body (Top)",
      Reach.Embedded("a body's key 5"),
      govActionCodec
    )(
      body("05")
    )(t =>
      written(HaskellStateCodecs.govAction)(GovAction.TreasuryWithdrawals(t.withdrawals, None))
        .flatMap(Step.at(_, List(Index(1))))
    ),
    "network_id" -> DatasetRule(
      "DijkstraTx.body (Top)",
      Reach.Embedded("a body's key 15"),
      Writing.Leaf("Codec.uintBig")
    )(b => body("0f")(b).flatMap(t => t.networkId.toRight(Refusal.Form(Shape.Present))))(n =>
      written(unsigned)(BigInt(n))
    ),
    "positive_coin" -> DatasetRule(
      "DijkstraTx.body (Top)",
      Reach.Embedded("a body's donation"),
      Writing.Leaf("Codec.uintBig")
    )(
      body("16")
    )(t => written(unsigned)(t.donation)),
    "account_balance_intervals" -> DatasetRule
      .unwritten("DijkstraTx.body (Top)", Reach.Embedded("a body's key 26"))(
        body("181a")
      ),
    "account_balance_interval" -> DatasetRule(
      "DijkstraTx.balanceInterval",
      Reach.Direct,
      Writing.Encoder("DijkstraCodecs.balanceInterval")
    )(read(DijkstraTx.balanceInterval))(written(DijkstraCodecs.balanceInterval)),
    "certificate" -> DatasetRule(
      "DijkstraCert.decode",
      Reach.Direct,
      Writing.Encoder("DijkstraCodecs.certificate")
    )(
      read(DijkstraCert.decode)
    )(written(DijkstraCodecs.certificate)),
    "certificates" -> certificates,
    "relay" -> DatasetRule(
      "DijkstraRelay.decode",
      Reach.Direct,
      Writing.Encoder("DijkstraCodecs.relay")
    )(
      read(DijkstraRelay.decode)
    )(written(DijkstraCodecs.relay)),
    "dns_name" -> DatasetRule
      .apply(
        "DijkstraRelay.decode",
        Reach.Embedded("a multi-host relay"),
        Writing.Encoder("DijkstraCodecs.relay")
      )(relay("8202"))(r => written(DijkstraCodecs.relay)(r).flatMap(Step.at(_, List(Index(1))))),
    "ipv4" -> DatasetRule
      .apply(
        "DijkstraRelay.decode",
        Reach.Embedded("a single-host address relay"),
        Writing.Encoder("DijkstraCodecs.relay")
      )(b =>
        relay("8400f6", "f6")(b).flatMap {
          case r @ DijkstraRelay.SingleHostAddr(_, Some(_), _) => Right(r)
          case _                                               => Left(Refusal.Form(Shape.Present))
        }
      )(r => written(DijkstraCodecs.relay)(r).flatMap(Step.at(_, List(Index(2))))),
    "ipv6" -> DatasetRule
      .apply(
        "DijkstraRelay.decode",
        Reach.Embedded("a single-host address relay"),
        Writing.Encoder("DijkstraCodecs.relay")
      )(b =>
        relay("8400f6f6")(b).flatMap {
          case r @ DijkstraRelay.SingleHostAddr(_, _, Some(_)) => Right(r)
          case _                                               => Left(Refusal.Form(Shape.Present))
        }
      )(r => written(DijkstraCodecs.relay)(r).flatMap(Step.at(_, List(Index(3))))),
    "pool_metadata" -> DatasetRule(
      "DijkstraCert.decode",
      Reach.Embedded("a Dijkstra pool registration"),
      Writing.Encoder("DijkstraCodecs.certificate")
    )(b =>
      read(DijkstraCert.decode)(
        hex(
          "8a03581c" + zeros(28) + "5820" + zeros(32) + "0000d81e820001581de0" + zeros(28) + "8080"
        ) ++ b
      ).flatMap {
        case c @ DijkstraCert.RegisterPool(p) if p.metadata.isDefined => Right(c)
        case _ => Left(Refusal.Form(Shape.Present))
      }
    )(c => written(DijkstraCodecs.certificate)(c).flatMap(Step.at(_, List(Index(9))))),
    "credential" -> credential(DecodeField.CertificateCredential),
    "stake_credential" -> credential(DecodeField.CertificateCredential),
    "drep_credential" -> credential(DecodeField.CertificateCredential),
    "committee_cold_credential" -> credential(DecodeField.CommitteeMember),
    "committee_hot_credential" -> credential(DecodeField.CommitteeMember),
    "drep" -> DatasetRule(
      "DijkstraCert.decode",
      Reach.Embedded("a vote delegation certificate"),
      Writing.Encoder("HaskellStateCodecs.nodeDRep")
    )(b =>
      read(DijkstraCert.decode)(hex("83098200581c" + zeros(28)) ++ b).flatMap {
        case DijkstraCert.Delegate(_, DijkstraDelegatee.Vote(d)) => Right(d)
        case _ => Left(Refusal.Form(Shape.Present))
      }
    )(written(HaskellStateCodecs.nodeDRep)),
    "reward_account" -> DatasetRule(
      "RewardAccount.decode (V12.bytes)",
      Reach.Direct,
      Writing.Encoder("HaskellStateCodecs.rewardAccount")
    )(read(n => V12.bytes(n, DecodeField.RewardAccount).flatMap(RewardAccount.decode)))(
      written(HaskellStateCodecs.rewardAccount)
    ),
    "address" -> DatasetRule(
      "DijkstraTxOut.decode",
      Reach.Embedded("[address, 0]"),
      Writing.Encoder("ByronAddress.reencoded / Address.raw")
    )(
      output("82", "00")
    )(o => ConwayRules.writeAddress(o.address)),
    "voter" -> DatasetRule(
      "DijkstraVoting.voter",
      Reach.Direct,
      Writing.Encoder("ConwayCodecs.voter")
    )(
      read(DijkstraVoting.voter)
    )(v => written(ConwayCodecs.voter)(conwayVoter(v))),
    "voting_procedures" -> votingProcedures,
    "voting_procedure" -> DatasetRule(
      "DijkstraVoting.procedures",
      Reach.Embedded("{voter: {id: procedure}}"),
      votingCodec
    )(b =>
      read(DijkstraVoting.procedures)(
        hex("a18204581c" + zeros(28) + "a1825820" + zeros(32) + "00") ++ b
      )
    )(all => writeVoting(all).flatMap(Step.at(_, List(FirstValue, FirstValue)))),
    "vote" -> DatasetRule
      .apply("DijkstraVoting.procedures", Reach.Embedded("a voting procedure"), votingCodec)(b =>
        read(DijkstraVoting.procedures)(
          hex("a18204581c" + zeros(28) + "a1825820" + zeros(32) + "0082") ++ b ++ hex("f6")
        )
      )(all => writeVoting(all).flatMap(Step.at(_, List(FirstValue, FirstValue, Index(0))))),
    "gov_action_id" -> DatasetRule(
      "DijkstraGovActionId.decode",
      Reach.Direct,
      Writing.Encoder("HaskellStateCodecs.govActionId")
    )(read(DijkstraGovActionId.decode))(i =>
      written(HaskellStateCodecs.govActionId)(GovActionId(i.txId, BigInt(i.index)))
    ),
    "anchor" -> DatasetRule(
      "DijkstraAnchor.decode",
      Reach.Direct,
      Writing.Encoder("HaskellStateCodecs.anchor")
    )(
      read(DijkstraAnchor.decode)
    )(a => written(HaskellStateCodecs.anchor)(Anchor(a.url, a.hash))),
    "url" -> DatasetRule(
      "DijkstraAnchor.decode",
      Reach.Embedded("an anchor"),
      Writing.Encoder("HaskellStateCodecs.anchor")
    )(b => read(DijkstraAnchor.decode)(hex("82") ++ b ++ hex("5820" + zeros(32))))(a =>
      written(HaskellStateCodecs.anchor)(Anchor(a.url, a.hash)).flatMap(Step.at(_, List(Index(0))))
    ),
    "proposal_procedure" -> DatasetRule(
      "DijkstraProposal.decode",
      Reach.Direct,
      Writing.Encoder("DijkstraCodecs.proposal")
    )(
      read(DijkstraProposal.decode)
    )(written(DijkstraCodecs.proposal)),
    "proposal_procedures" -> proposalProcedures,
    "gov_action" -> DatasetRule
      .apply(
        "DijkstraProposal.decode",
        Reach.Embedded("a proposal procedure"),
        Writing.Encoder("DijkstraCodecs.proposal")
      )(b =>
        read(DijkstraProposal.decode)(
          hex("8400581de0" + zeros(28)) ++ b ++ hex("8260" + "5820" + zeros(32))
        )
      )(p => written(DijkstraCodecs.proposal)(p).flatMap(Step.at(_, List(Index(2))))),
    "constitution" -> DatasetRule
      .apply(
        "DijkstraProposal.decode",
        Reach.Embedded("a NewConstitution action"),
        Writing.Encoder("DijkstraCodecs.proposal")
      )(b =>
        read(DijkstraProposal.decode)(
          hex("8400581de0" + zeros(28) + "8305f6") ++ b ++ hex("8260" + "5820" + zeros(32))
        )
      )(p => written(DijkstraCodecs.proposal)(p).flatMap(Step.at(_, List(Index(2), Index(2))))),
    "protocol_version" -> DatasetRule
      .apply(
        "DijkstraProposal.decode",
        Reach.Embedded("a HardFork action"),
        Writing.Encoder("DijkstraCodecs.proposal")
      )(
        proposal("8301f6")
      )(p => written(DijkstraCodecs.proposal)(p).flatMap(Step.at(_, List(Index(2), Index(2))))),
    "major_protocol_version" -> DatasetRule.apply(
      "DijkstraProposal.decode",
      Reach.Embedded("a HardFork action's major version"),
      Writing.Encoder("DijkstraCodecs.proposal")
    )(proposal("8301f682", "00"))(p =>
      written(DijkstraCodecs.proposal)(p).flatMap(Step.at(_, List(Index(2), Index(2), Index(0))))
    ),
    "epoch" -> word64(DecodeField.RetirementEpoch),
    "coin" -> word64(DecodeField.ProposalDeposit),
    "protocol_param_update" -> DatasetRule(
      "DijkstraParamUpdate.decode",
      Reach.Direct,
      Writing.Encoder("DijkstraCodecs.paramUpdate")
    )(
      read(DijkstraParamUpdate.decode)
    )(written(DijkstraCodecs.paramUpdate)),
    "drep_voting_thresholds" -> paramRule("181a", 26),
    "pool_voting_thresholds" -> paramRule("1819", 25),
    "ex_units" -> paramRule("14", 20),
    "ex_unit_prices" -> paramRule("13", 19),
    "cost_models" -> paramRule("12", 18),
    "unit_interval" -> DatasetRule(
      "V12.ratio (Unit)",
      Reach.Direct,
      Writing.Encoder("Wire.unitInterval")
    )(
      read(V12.ratio(_, DecodeField.Quorum, V12.Interval.Unit))
    )(written(Wire.unitInterval)),
    "nonnegative_interval" -> DatasetRule(
      "V12.ratio (NonNegative)",
      Reach.Direct,
      Writing.Encoder("Wire.nonNegativeInterval")
    )(read(V12.ratio(_, DecodeField.PriceMem, V12.Interval.NonNegative)))(
      written(Wire.nonNegativeInterval)
    ),
    "transaction_id" -> leafHash(32, DecodeField.InputId),
    "hash32" -> leafHash(32, DecodeField.AnchorHash),
    "auxiliary_data_hash" -> leafHash(32, DecodeField.AuxiliaryDataHash),
    "script_data_hash" -> leafHash(32, DecodeField.ScriptDataHash),
    "vrf_keyhash" -> leafHash(32, DecodeField.PoolVrf),
    "script_hash" -> leafHash(28, DecodeField.PolicyHash),
    "guardrails_script_hash" -> leafHash(28, DecodeField.PolicyHash),
    "hash28" -> leafHash(28, DecodeField.PolicyHash),
    "addr_keyhash" -> leafHash(28, DecodeField.Signer),
    "pool_keyhash" -> leafHash(28, DecodeField.PoolOperator),
    "policy_id" -> leafHash(28, DecodeField.Mint),
    "language" -> ConwayRules.language
  )

  private def plutusScript(site: DecodeField) =
    DatasetRule(
      "V12.bytes (DijkstraScript.plutus's bytes)",
      Reach.Direct,
      Writing.Leaf("Codec.bytes")
    )(
      read(V12.bytes(_, site))
    )(leafBytes)

  private def paramRule(key: String, field: Int) =
    DatasetRule.apply(
      "DijkstraParamUpdate.decode",
      Reach.Embedded(s"a parameter update's key $field"),
      Writing.Encoder("DijkstraCodecs.paramUpdate")
    )(b => read(DijkstraParamUpdate.decode)(hex("a1" + key) ++ b))(u =>
      written(DijkstraCodecs.paramUpdate)(u).flatMap(Step.at(_, List(Key(field))))
    )
