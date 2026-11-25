// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.conformance

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value as CborValue}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{
  Anchor,
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
  NativeScript,
  RewardAccount,
  TxIn,
  Value,
  Voter,
  VotingProcedure
}
import xyz.kd5ujc.scarab.ledger.dijkstra.{
  DijkstraAnchor,
  DijkstraBodyReaders,
  DijkstraCert,
  DijkstraDelegatee,
  DijkstraDatum,
  DijkstraGovActionId,
  DijkstraParamUpdate,
  DijkstraProposal,
  DijkstraRelay,
  DijkstraScript,
  DijkstraTxIn,
  DijkstraTxOut,
  DijkstraValue,
  DijkstraVoting,
  DijkstraVoter,
  DijkstraVotingProcedure,
  V12
}
import xyz.kd5ujc.scarab.ledger.era.{BothLevels, EraTxBody, EraTxError, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{
  DijkstraTx,
  DijkstraTxAuxData,
  DijkstraTxBody,
  DijkstraTxWits
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
  * that Scarab reads a Leios block with (Dijkstra's era decoder, [[DijkstraTx]], [[DijkstraTxWits]]
  * and [[DijkstraTxAuxData]], and the readers of [[V12]], at decoder version 12). A type that
  * shares its encoding with Conway is written from its decoded fields by the shared production
  * encoder. Other samples are written from the bytes their decoded value keeps, by a production
  * codec of a scalar, or by a Haskell-format codec of the ledger state where its value is a type
  * that codec writes. Missing writers remain failures.
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

  // the era decoder's reading: its refusal the decoder's error, a value its bounds rule out its own
  private def eraRead[A](f: Node => Either[EraTxError, A])(b: Bytes): Either[Refusal, A] =
    node(b).flatMap(n =>
      f(n).leftMap {
        case EraTxError.Decode(e) => Refusal.Ledger(e)
        case other                => Refusal.Era(other)
      }
    )

  private val bodies = EraTxBody.dijkstra

  /** A body read by the era decoder at each level, the sample read as one item first: at the top as
    * a transaction's with an empty witness set, at the sub-transaction level as the one
    * sub-transaction of a minimal top-level body (keys 0, 1, 2 and 23), whose fields before it
    * refuse nothing.
    */
  private def topBody(b: Bytes): Either[Refusal, DijkstraTxBody[TxLevel.Top]] =
    node(b).flatMap(n =>
      eraRead(DijkstraTx.inBlock)(hex("84") ++ n.original ++ hex("a0f6f5")).map(_.body)
    )

  private def subBody(b: Bytes): Either[Refusal, DijkstraTxBody[TxLevel.Sub]] =
    node(b).flatMap(n =>
      eraRead(DijkstraTx.inBlock)(hex("84a4008001800200178183") ++ n.original ++ hex("a0f6a0f6f5"))
        .map(t => bodies.subTransactions(t.body).toVector.head.body)
    )

  /** The item at `key` of a body the era decoder read, as its field's reader reads it again. */
  private def field[A](body: DijkstraTxBody[TxLevel.Top], key: Int)(
      f: Node => Either[LedgerDecodeError, A]
  ): Either[Refusal, A] =
    node(body.raw).flatMap(n =>
      ledger(V12.entries(n, DecodeField.TransactionBody)).flatMap(entries =>
        entries
          .collectFirst {
            case (k, v) if (k.value match
                  case CborValue.UInt(u) => u == key
                  case _                 => false
                ) =>
              v
          }
          .toRight(Refusal.Form(Shape.Present))
          .flatMap(v => ledger(f(v)))
      )
    )

  private def written[A](codec: Codec[A])(a: A) = ConwayRules.written(codec)(a)
  private val unsigned: Codec[BigInt] = Codec.uintBig((BigInt(1) << 64) - 1)
  private def leafBytes(b: Bytes) = written(Codec.bytes)(b)

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
      s"DijkstraTxAuxData.decode (${shape.render} only)",
      Reach.Direct,
      Writing.Kept("DijkstraTxAuxData.raw")
    )(b => shaped(shape)(b).flatMap(n => eraRead(DijkstraTxAuxData.decode)(n.original)))(a =>
      Right(a.raw)
    )

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
    * witness set keeps of it ([[DijkstraTxWits.datums]]), as the reference keeps those of
    * `Data era`.
    */
  private def dataRule(shape: Option[Shape]) =
    DatasetRule(
      "DijkstraTxWits.decode",
      Reach.Embedded("a witness set's datums"),
      Writing.Kept("DijkstraTxWits.datums")
    )(b =>
      eraRead(DijkstraTxWits.decode)(hex("a10481") ++ b)
        .flatMap(w =>
          w.datums.datums.values.headOption
            .map(_.originalBytes)
            .toRight(Refusal.Form(Shape.Present))
        )
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
    topBody(hex("a4008001800200" + key) ++ sample)

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
  /** `certificates`, the set at a body's key 4, which refuses it empty (`DijkstraTxBody.readTop`),
    * as the reference's body does where the set's own decoder takes an empty one.
    */
  private def certificates: DatasetRule = DatasetRule
    .apply(
      "DijkstraTxBody.readTop",
      Reach.Embedded("a body's key 4"),
      Writing.Encoder("DijkstraCodecs.certificates")
    )(b => body("04")(b).flatMap(field(_, 4)(DijkstraCert.set)))(
      written(DijkstraCodecs.certificates)
    )

  /** `voting_procedures`, non-empty, as the body that holds them requires
    * (`DijkstraTxBody.readTop`'s key 19).
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
      "DijkstraTxBody.readTop",
      Reach.Embedded("a body's key 20"),
      Writing.Encoder("DijkstraCodecs.proposals")
    )(b => body("14")(b).flatMap(field(_, 20)(DijkstraProposal.set)))(
      written(DijkstraCodecs.proposals)
    )

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
    "block_body" -> DatasetRule.unwritten(
      "Block.decodeShelley (LeiosBlock)",
      Reach.Embedded("[8, [Leios header, block body]]")
    )(b =>
      Block
        .decodeShelley(
          hex("820882") ++ headerAround(headerBody(0, hex("00"), leios = true)) ++ b,
          _ => version
        )
        .leftMap(Refusal.Chain(_))
    ),
    "transaction" -> DatasetRule(
      "DijkstraTx.fromSubmission",
      Reach.Direct,
      Writing.Kept("the transaction's bytes as read")
    )(b =>
      node(b).flatMap(n =>
        DijkstraTx
          .fromSubmission(n.original)
          .bimap(
            {
              case EraTxError.Decode(e) => Refusal.Ledger(e)
              case other                => Refusal.Era(other)
            },
            _ => n.original
          )
      )
    )(Right(_)),
    "transaction_body" -> DatasetRule(
      "DijkstraTxBody.readTop",
      Reach.Direct,
      Writing.Kept("DijkstraTxBody.raw")
    )(topBody)(t => Right(t.raw)),
    "sub_transaction_body" -> DatasetRule(
      "DijkstraTxBody.readSub",
      Reach.Direct,
      Writing.Kept("DijkstraTxBody.raw")
    )(subBody)(t => Right(t.raw)),
    "transaction_witness_set" -> DatasetRule(
      "DijkstraTxWits.decode",
      Reach.Direct,
      Writing.Kept("DijkstraTxWits.raw")
    )(eraRead(DijkstraTxWits.decode))(w => Right(w.raw)),
    "redeemers" -> DatasetRule(
      "DijkstraTxWits.decode",
      Reach.Embedded("a witness set's key 5"),
      Writing.Kept("DijkstraTxWits.raw, key 5")
    )(b => eraRead(DijkstraTxWits.decode)(hex("a105") ++ b))(w => Step.at(w.raw, List(Key(5)))),
    "vkeywitness" -> DatasetRule(
      "DijkstraTxWits.decode",
      Reach.Embedded("{0: [vkeywitness]}"),
      Writing.Encoder("ConwayCodecs.vkeyWitness")
    )(b =>
      eraRead(DijkstraTxWits.decode)(hex("a10081") ++ b).flatMap(w =>
        w.vkeys.headOption.map(v => (v.vkey, v.signature)).toRight(Refusal.Form(Shape.Present))
      )
    )(written(ConwayCodecs.vkeyWitness)),
    "bootstrap_witness" -> DatasetRule(
      "DijkstraTxWits.decode",
      Reach.Embedded("{2: [bootstrap_witness]}"),
      Writing.Encoder("ConwayCodecs.bootstrapWitness")
    )(b =>
      eraRead(DijkstraTxWits.decode)(hex("a10281") ++ b).flatMap(w =>
        w.bootstrap.headOption.toRight(Refusal.Form(Shape.Present))
      )
    )(written(ConwayCodecs.bootstrapWitness)),
    "vkey" -> DatasetRule(
      "DijkstraTxWits.decode",
      Reach.Embedded("a vkey witness"),
      Writing.Leaf("Codec.bytes")
    )(b => eraRead(DijkstraTxWits.decode)(hex("a1008182") ++ b ++ hex("5840" + zeros(64))))(w =>
      w.vkeys.headOption.toRight(Unwritten.NotKept).flatMap(v => leafBytes(v.vkey))
    ),
    "signature" -> DatasetRule(
      "DijkstraTxWits.decode",
      Reach.Embedded("a vkey witness"),
      Writing.Leaf("Codec.bytes")
    )(b => eraRead(DijkstraTxWits.decode)(hex("a10081825820" + zeros(32)) ++ b))(w =>
      w.vkeys.headOption.toRight(Unwritten.NotKept).flatMap(v => leafBytes(v.signature))
    ),
    "auxiliary_data" -> DatasetRule(
      "DijkstraTxAuxData.decode",
      Reach.Direct,
      Writing.Kept("DijkstraTxAuxData.raw")
    )(eraRead(DijkstraTxAuxData.decode))(a => Right(a.raw)),
    "metadata" -> auxiliaryRule(Shape.Map),
    "auxiliary_data_map" -> auxiliaryRule(Shape.Tag259),
    "auxiliary_data_array" -> auxiliaryRule(Shape.Array),
    "auxiliary_scripts" -> DatasetRule(
      "DijkstraTxAuxData.decode and NativeScript.decode (Timelock)",
      Reach.Embedded("[{}, scripts]"),
      Writing.Kept("DijkstraTxAuxData.raw")
    )(b =>
      // EraSpec.hs:280 (dataset ed6c49a4) declares StrictSeq (Timelock DijkstraEra). The full
      // auxiliary-data decoder also reads Dijkstra's guard constructor; this rule's type does not.
      eraRead(DijkstraTxAuxData.decode)(hex("82a0") ++ b).flatMap { auxiliary =>
        node(b)
          .flatMap(n => ledger(V12.list(n, DecodeField.AuxiliaryNativeScripts)))
          .flatMap(
            _.traverse_(n =>
              NativeScript.decode(n, NativeScript.Form.Timelock).leftMap(Refusal.Native(_))
            )
          )
          .as(auxiliary)
      }
    )(a => Step.at(a.raw, List(Index(1)))),
    "metadatum" -> DatasetRule(
      "DijkstraTxAuxData.decode",
      Reach.Embedded("{0: metadatum}"),
      Writing.Encoder("ConwayCodecs.metadatum")
    )(b =>
      eraRead(DijkstraTxAuxData.decode)(hex("a100") ++ b)
        .flatMap(_ => node(b))
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
      "DijkstraTxBody.readTop",
      Reach.Embedded("a body's key 9"),
      multiassetCodec
    )(
      body("09")
    )(t => writeMultiasset(bodies.mint[TxLevel.Top](t)(using BothLevels.Top))),
    "nonzero_int64" -> DatasetRule(
      "DijkstraTxBody.readTop",
      Reach.Embedded("a mint quantity of a body"),
      multiassetCodec
    )(b => body("09")(hex("a1581c" + zeros(28) + "a140") ++ b))(t =>
      writeMultiasset(bodies.mint[TxLevel.Top](t)(using BothLevels.Top))
        .flatMap(Step.at(_, List(FirstValue, FirstValue)))
    ),
    "asset_name" -> DatasetRule(
      "DijkstraTxBody.readTop",
      Reach.Embedded("an asset name of a body's mint"),
      valueCodec
    )(b => body("09")(hex("a1581c" + zeros(28) + "a1") ++ b ++ hex("01")))(t =>
      writeMultiasset(bodies.mint[TxLevel.Top](t)(using BothLevels.Top))
        .flatMap(Step.at(_, List(FirstValue, FirstKey)))
    ),
    "withdrawals" -> DatasetRule(
      "DijkstraTxBody.readTop",
      Reach.Embedded("a body's key 5"),
      govActionCodec
    )(b =>
      body("05")(b).flatMap(
        field(_, 5)(
          DijkstraBodyReaders.accountMap(_, DecodeField.Withdrawals)(
            V12.word64(_, DecodeField.WithdrawalAmount)
          )
        )
      )
    )(w =>
      written(HaskellStateCodecs.govAction)(GovAction.TreasuryWithdrawals(w, None))
        .flatMap(Step.at(_, List(Index(1))))
    ),
    "network_id" -> DatasetRule(
      "DijkstraTxBody.readTop",
      Reach.Embedded("a body's key 15"),
      Writing.Leaf("Codec.uintBig")
    )(b => body("0f")(b).flatMap(t => field(t, 15)(DijkstraBodyReaders.networkId)))(n =>
      written(unsigned)(BigInt(n))
    ),
    "positive_coin" -> DatasetRule(
      "DijkstraTxBody.readTop",
      Reach.Embedded("a body's donation"),
      Writing.Leaf("Codec.uintBig")
    )(
      body("16")
    )(t => written(unsigned)(bodies.treasuryDonation[TxLevel.Top](t)(using BothLevels.Top).value)),
    "account_balance_intervals" -> DatasetRule
      .unwritten("DijkstraTxBody.readTop", Reach.Embedded("a body's key 26"))(
        body("181a")
      ),
    "account_balance_interval" -> DatasetRule(
      "DijkstraBodyReaders.balanceInterval",
      Reach.Direct,
      Writing.Encoder("DijkstraCodecs.balanceInterval")
    )(read(DijkstraBodyReaders.balanceInterval))(written(DijkstraCodecs.balanceInterval)),
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
