// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Address, TxIn, Unsigned}
import xyz.kd5ujc.scarab.plutus.PlutusData
import xyz.kd5ujc.scarab.ledger.state.{
  Accounts,
  CertState,
  Coin,
  Conway,
  Credential,
  Dijkstra,
  EraTag,
  KeyHash,
  PState,
  StakePool,
  Staking
}

/** What a transaction of era `E` consumes and produces, and its minimum fee given the UTxO set
  * (`class EraTx era => EraUTxO era`, cardano-ledger f649f975
  * `libs/cardano-ledger-core/src/Cardano/Ledger/State/UTxO.hs:242-285`). The superclass `EraTx era`
  * is a member ([[txOf]]), as the other classes' superclasses of other families are.
  */
trait EraUTxO[E <: EraTag]:
  /** The transactions' class (the superclass `EraTx era`). */
  val txOf: EraTx[E]

  /** `getConsumedValue`: what the body consumes, given the deposit of a registered staking
    * credential.
    */
  def getConsumedValue[L <: TxLevel](
      pp: PParams[E],
      lookupStakingDeposit: Credential[Staking] => Option[Coin],
      utxo: UTxO[E],
      body: TxBody[L, E]
  )(using txOf.bodyOf.Level[L]): Value[E]

  /** `getProducedValue`: what a top-level body produces, given whether a pool is registered. */
  def getProducedValue(
      pp: PParams[E],
      isPoolRegistered: KeyHash[StakePool] => Boolean,
      body: TxBody[TxLevel.Top, E]
  ): Value[E]

  /** `getScriptsProvided`: the scripts the transaction provides, from its witnesses and, from
    * Babbage, the outputs of its inputs the UTxO set holds.
    */
  def getScriptsProvided[L <: TxLevel](utxo: UTxO[E], tx: Tx[L, E])(using
      txOf.bodyOf.Level[L]
  ): ScriptsProvided[E]

  /** `getScriptsNeeded`: what finds the scripts the body needs, by purpose. */
  def getScriptsNeeded[L <: TxLevel](utxo: UTxO[E], body: TxBody[L, E])(using
      txOf.bodyOf.Level[L]
  ): ScriptsNeeded[E]

  /** `getScriptsHashesNeeded`: the hashes of the scripts needed. */
  def getScriptsHashesNeeded(scriptsNeeded: ScriptsNeeded[E]): Set[Bytes]

  /** `getWitsVKeyNeeded`: the hashes of the keys that must sign the body. */
  def getWitsVKeyNeeded[L <: TxLevel](certState: CertState[E], utxo: UTxO[E], body: TxBody[L, E])(
      using txOf.bodyOf.Level[L]
  ): Set[Bytes]

  /** `getMinFeeTxUtxo`: the minimum fee, its witnesses excluded, the reference scripts the UTxO set
    * gives it counted.
    */
  def getMinFeeTxUtxo[L <: TxLevel](pp: PParams[E], tx: Tx[L, E], utxo: UTxO[E])(using
      txOf.bodyOf.Level[L]
  ): Coin

/** The datums of a transaction of era `E` from Alonzo, and what its annotated form holds (`class
  * EraUTxO era => AlonzoEraUTxO era`, cardano-ledger f649f975
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/UTxO.hs:129-162`).
  */
trait AlonzoEraUTxO[E <: EraTag] extends EraUTxO[E]:
  /** `getSupplementalDataHashes`: the hashes of the datums a witness set may carry that no spent
    * input requires.
    */
  def getSupplementalDataHashes[L <: TxLevel](utxo: UTxO[E], txBody: TxBody[L, E])(using
      txOf.bodyOf.Level[L]
  ): Set[Bytes]

  /** `getSpendingDatum`: the datum a spending purpose's input needs; none for another purpose. An
    * inline datum is read as data here ([[BabbageEraTxOut.data]]), and the failure of one taken
    * unread ([[BabbageTxOutRep.fromStored]]) returned, where the reference raises `error`.
    */
  def getSpendingDatum[L <: TxLevel](utxo: UTxO[E], tx: Tx[L, E], sp: PlutusPurpose[AsItem, E])(
      using txOf.bodyOf.Level[L]
  ): Either[PlutusData.DecodeError, Option[Data]]

  /** `scriptsProvidedStAnnTx`: the scripts the annotated transaction provides. */
  def scriptsProvidedStAnnTx[L <: TxLevel](stAnnTx: StAnnTx[L, E])(using
      txOf.bodyOf.Level[L]
  ): ScriptsProvided[E]

  /** `scriptsNeededStAnnTx`: the scripts the annotated transaction needs. */
  def scriptsNeededStAnnTx[L <: TxLevel](stAnnTx: StAnnTx[L, E])(using
      txOf.bodyOf.Level[L]
  ): ScriptsNeeded[E]

  /** `plutusScriptsWithContextStAnnTx`: its Plutus scripts with their contexts, or why they cannot
    * be collected.
    */
  def plutusScriptsWithContextStAnnTx[L <: TxLevel](stAnnTx: StAnnTx[L, E])(using
      txOf.bodyOf.Level[L]
  ): Either[cats.data.NonEmptyList[CollectError[E]], List[PlutusWithContext]]

  /** `plutusLanguagesUsedStAnnTx`: the languages of the Plutus scripts it uses. */
  def plutusLanguagesUsedStAnnTx[L <: TxLevel](stAnnTx: StAnnTx[L, E])(using
      txOf.bodyOf.Level[L]
  ): Set[xyz.kd5ujc.scarab.plutus.PlutusLanguage]

/** What only a Dijkstra transaction's annotated form holds (`class AlonzoEraUTxO era =>
  * DijkstraEraUTxO era`, cardano-ledger 9df9f98c
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/UTxO.hs:63-66`): its sub-transactions annotated,
  * whether its scripts run in the legacy mode, and a sub-transaction's script hashes needed.
  */
trait DijkstraEraUTxO[E <: EraTag] extends AlonzoEraUTxO[E]:
  /** `subTransactionsStAnnTx`. */
  def subTransactionsStAnnTx(stAnnTx: StAnnTx[TxLevel.Top, E]): List[StAnnTx[TxLevel.Sub, E]]

  /** `plutusLegacyModeStAnnTxG`: the reference's `Bool` as an enum (TYP-4). */
  def plutusLegacyModeStAnnTx(stAnnTx: StAnnTx[TxLevel.Top, E]): dijkstra.PlutusLegacyMode

  /** `scriptsHashesNeededStAnnTx`, of a sub-transaction's annotated form ([[SubOnly]]). */
  def scriptsHashesNeededStAnnTx[L <: TxLevel](stAnnTx: StAnnTx[L, E])(using SubOnly[L]): Set[Bytes]

/** `ScriptsProvided` (`State/UTxO.hs:229-232`, cardano-ledger f649f975): the scripts a transaction
  * provides, by hash. Each key is its script's hash (`getScriptsProvided` makes it so); the map's
  * readers trust it, as the reference's do, and the constructor does not check it.
  */
final case class ScriptsProvided[E <: EraTag](unScriptsProvided: Map[Bytes, Script[E]])

/** `AlonzoScriptsNeeded` (`Alonzo/UTxO.hs:102-104`, cardano-ledger f649f975): each script needed
  * with the purpose that needs it, in the reference's order; a script may be needed for several
  * purposes.
  */
final case class AlonzoScriptsNeeded[E <: EraTag](
    unAlonzoScriptsNeeded: Vector[(PlutusPurpose[AsIxItem, E], Bytes)]
):
  /** The derived `Semigroup` (`Alonzo/UTxO.hs:104`, cardano-ledger f649f975): both, in order. */
  def ++(that: AlonzoScriptsNeeded[E]): AlonzoScriptsNeeded[E] =
    AlonzoScriptsNeeded(unAlonzoScriptsNeeded ++ that.unAlonzoScriptsNeeded)

/** Alonzo's functions of the UTxO set (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/UTxO.hs`,
  * cardano-ledger f649f975, the same at 9df9f98c). Each purpose is built through the era's script
  * class (`SpendingPurpose` and the rest), whose builders are members of [[ConwayEraScript]] here.
  */
object AlonzoUTxO:
  private type Ix = Unsigned[Unsigned.Width.W32]

  /** `zipAsIxItem` (`Alonzo/UTxO.hs:364-368`, cardano-ledger f649f975): each item with its
    * position, the `Int` narrowed to a `Word32` (`fromIntegral`).
    */
  def zipAsIxItem[A, C](items: Iterable[A])(f: AsIxItem[Ix, A] => C): Vector[C] =
    items.iterator.zipWithIndex
      .map((item, ix) => f(AsIxItem(Unsigned.wrapping[Unsigned.Width.W32](BigInt(ix)), item)))
      .toVector

  /** The script hash of a credential (`credScriptHash`). */
  private def credScriptHash(credential: xyz.kd5ujc.scarab.ledger.Credential): Option[Bytes] =
    credential match
      case xyz.kd5ujc.scarab.ledger.Credential.Script(h) => Some(h)
      case xyz.kd5ujc.scarab.ledger.Credential.Key(_)    => None

  /** `getSpendingScriptsNeeded` (`Alonzo/UTxO.hs:370-383`, cardano-ledger f649f975): each input, by
    * its position among the inputs, whose output the UTxO set holds at a script's payment address
    * (`getScriptHash`).
    */
  def getSpendingScriptsNeeded[E <: EraTag, L <: TxLevel](utxo: UTxO[E], body: TxBody[L, E])(using
      b: EraTxBody[E],
      l: b.Level[L],
      s: ConwayEraScript[E]
  ): AlonzoScriptsNeeded[E] =
    val o = b.outputsOf
    AlonzoScriptsNeeded(
      zipAsIxItem(b.inputs[L](body)) { asIxItem =>
        for
          out <- utxo.unUTxO.get(asIxItem.item)
          hash <- o.address(out).kind match
            case Address.Kind.Shelley(_, payment, _, _) => credScriptHash(payment)
            case Address.Kind.Byron(_)                  => None
        yield (s.mkSpendingPurpose(asIxItem), hash)
      }.flatten
    )

  /** `getWithdrawingScriptsNeeded` (`Alonzo/UTxO.hs:385-394`, cardano-ledger f649f975): each
    * withdrawal from a script's account, by its position in the withdrawals' order (`Map.keys` of
    * the accounts).
    */
  def getWithdrawingScriptsNeeded[E <: EraTag, L <: TxLevel](body: TxBody[L, E])(using
      b: EraTxBody[E],
      l: b.Level[L],
      s: ConwayEraScript[E]
  ): AlonzoScriptsNeeded[E] =
    AlonzoScriptsNeeded(
      zipAsIxItem(b.withdrawals[L](body).unWithdrawals.keys.toVector) { asIxItem =>
        asIxItem.item.id.credential match
          case Credential.ScriptHashObj(hash) =>
            Some((s.mkWithdrawingPurpose(asIxItem), hash.bytes))
          case Credential.KeyHashObj(_) => None
      }.flatten
    )

  /** `getMintingScriptsNeeded` (`Alonzo/UTxO.hs:403-411`, cardano-ledger f649f975): each policy the
    * body mints or burns, by its position in the policies' order (`mintedTxBodyF`, a set).
    */
  def getMintingScriptsNeeded[E <: EraTag, L <: TxLevel](body: TxBody[L, E])(using
      b: MaryEraTxBody[E],
      l: b.Level[L],
      s: ConwayEraScript[E]
  ): AlonzoScriptsNeeded[E] =
    AlonzoScriptsNeeded(
      zipAsIxItem(b.mint[L](body).keys.toVector.sorted) { asIxItem =>
        (s.mkMintingPurpose(asIxItem), asIxItem.item)
      }
    )

  /** `getInputDataHashesTxBody` (`Alonzo/UTxO.hs:250-279`, cardano-ledger f649f975): of the spent
    * outputs the UTxO set holds at a Plutus script's payment address (`getScriptHash`, the script
    * among `scriptsProvided`), the hashes of their datums, and the inputs of those with no datum
    * whose language is before Plutus V3 (CIP-69). An inline datum, and a datum at a native script's
    * address, count for neither.
    */
  def getInputDataHashesTxBody[E <: EraTag, L <: TxLevel](
      utxo: UTxO[E],
      txBody: TxBody[L, E],
      scriptsProvided: ScriptsProvided[E]
  )(using
      b: EraTxBody[E],
      l: b.Level[L],
      o: AlonzoEraTxOut[E],
      s: AlonzoEraScript[E]
  ): (Set[Bytes], Set[TxIn]) =
    def spendingPlutusScriptLanguage(addr: Address) =
      for
        scriptHash <- getScriptHash(addr)
        plutusScript <- AlonzoScripts.lookupPlutusScript(
          scriptHash,
          scriptsProvided.unScriptsProvided
        )
      yield AlonzoScripts.plutusScriptLanguage(plutusScript)
    def isSpendingPlutusScript(addr: Address) = spendingPlutusScriptLanguage(addr).isDefined
    val spendInputs = b.inputs[L](txBody)
    val spendUTxO = UTxOs.txInsFilter(utxo, spendInputs).unUTxO
    spendUTxO.foldLeft((Set.empty[Bytes], Set.empty[TxIn])) {
      case (ans @ (hashSet, inputSet), (txIn, txOut)) =>
        val addr = o.address(txOut)
        o.datum(txOut) match
          case Datum.NoDatum
              if spendingPlutusScriptLanguage(addr)
                .exists(_.id < xyz.kd5ujc.scarab.plutus.PlutusLanguage.V3.id) =>
            (hashSet, inputSet + txIn)
          case Datum.DatumHash(dataHash) if isSpendingPlutusScript(addr) =>
            (hashSet + dataHash, inputSet)
          case _ => ans
    }

  /** `getScriptHash` (`State/UTxO.hs:222-224`, cardano-ledger f649f975): the script of an address's
    * payment credential, none for a key's or a bootstrap address.
    */
  private def getScriptHash(addr: Address): Option[Bytes] = addr.kind match
    case Address.Kind.Shelley(_, payment, _, _) => credScriptHash(payment)
    case Address.Kind.Byron(_)                  => None

  /** `getAlonzoScriptsHashesNeeded` (`Alonzo/UTxO.hs:241-242`, cardano-ledger f649f975). */
  def getAlonzoScriptsHashesNeeded[E <: EraTag](scriptsNeeded: AlonzoScriptsNeeded[E]): Set[Bytes] =
    scriptsNeeded.unAlonzoScriptsNeeded.map(_._2).toSet

/** The core's functions of the UTxO set (`State/UTxO.hs`, cardano-ledger f649f975). */
object UTxOs:
  /** `txouts` (`State/UTxO.hs:146-158`, cardano-ledger f649f975): the outputs a body creates, each
    * at its index, as [[xyz.kd5ujc.scarab.ledger.TxIn.txouts]] numbers them (no output past index
    * 65,535).
    */
  def txouts[E <: EraTag, L <: TxLevel](txBody: TxBody[L, E])(using
      b: EraTxBody[E],
      l: b.Level[L]
  ): UTxO[E] =
    UTxO(TxIn.txouts(b.id[L](txBody), b.outputs[L](txBody)).toMap)

  /** `txInsFilter` (`State/UTxO.hs:168-174`, cardano-ledger f649f975): the outputs of `txIns`. The
    * reference restricts the map to the inputs (`Map.restrictKeys`, in time of the smaller of the
    * two); so here, each input looked up where they are fewer than the set's entries, which a
    * block's growing set of outputs is (`totalRefScriptSizeInBlock`, #85). The same entries either
    * way.
    */
  def txInsFilter[E <: EraTag](utxo: UTxO[E], txIns: Set[TxIn]): UTxO[E] =
    val entries = utxo.unUTxO
    if txIns.sizeIs < entries.size then
      UTxO(txIns.iterator.flatMap(in => entries.get(in).map(in -> _)).toMap)
    else UTxO(entries.filter((in, _) => txIns.contains(in)))

  /** `sumAllValue` (`State/UTxO.hs:199-200`, cardano-ledger f649f975): `foldMap`, the values'
    * `mconcat` ([[Val.sumAll]]).
    */
  def sumAllValue[E <: EraTag](outs: Iterable[TxOut[E]])(using o: EraTxOut[E]): Value[E] =
    o.valueOps.sumAll(outs.iterator.map(o.value))

  /** `sumUTxO` (`State/UTxO.hs:185-186`, cardano-ledger f649f975). */
  def sumUTxO[E <: EraTag](utxo: UTxO[E])(using EraTxOut[E]): Value[E] =
    sumAllValue(utxo.unUTxO.values)

/** Shelley's functions of the UTxO set (`eras/shelley/impl/src/Cardano/Ledger/Shelley/UTxO.hs`,
  * cardano-ledger f649f975).
  */
object ShelleyUTxO:
  /** `getShelleyWitsVKeyNeededNoGov` (`Shelley/UTxO.hs:203-251`, cardano-ledger f649f975): the keys
    * of the certificates that a key must witness, of the outputs the spendable inputs spend (a
    * key's payment address, or a bootstrap address's root, `bootstrapKeyHash`), of the owners of
    * each pool the body registers, and of the accounts it withdraws from that a key holds; the
    * governance keys aside.
    */
  def getShelleyWitsVKeyNeededNoGov[E <: EraTag, L <: TxLevel](utxo: UTxO[E], body: TxBody[L, E])(
      using
      b: EraTxBody[E],
      l: b.Level[L]
  ): Set[Bytes] =
    val o = b.outputsOf
    val certs = b.certs[L](body)
    val inputAuthors = b
      .spendableInputs[L](body)
      .flatMap(in =>
        utxo.unUTxO
          .get(in)
          .flatMap(out =>
            o.address(out).kind match
              case Address.Kind.Shelley(_, xyz.kd5ujc.scarab.ledger.Credential.Key(pay), _, _) =>
                Some(pay)
              case Address.Kind.Byron(root)         => Some(root)
              case Address.Kind.Shelley(_, _, _, _) => None
          )
      )
    val wdrlAuthors = b
      .withdrawals[L](body)
      .unWithdrawals
      .keySet
      .flatMap(account =>
        account.id.credential match
          case Credential.KeyHashObj(h)    => Some(h.bytes)
          case Credential.ScriptHashObj(_) => None
      )
    val owners = certs
      .flatMap(b.certsOf.getRegPoolTxCert(_).toVector.flatMap(_.owners.toVector.map(_.bytes)))
      .toSet
    val certAuthors = certs.flatMap(b.certsOf.getVKeyWitnessTxCert).toSet
    certAuthors ++ inputAuthors ++ owners ++ wdrlAuthors

  /** `shelleyConsumed` (`Shelley/UTxO.hs:109-118`, cardano-ledger f649f975): what the body
    * consumes, each refunded credential's deposit looked up in `accounts`.
    */
  def shelleyConsumed[E <: EraTag, L <: TxLevel](
      pp: PParams[E],
      accounts: Accounts[E],
      utxo: UTxO[E],
      body: TxBody[L, E]
  )(using u: EraUTxO[E], c: EraCertState[E], l: u.txOf.bodyOf.Level[L]): Value[E] =
    u.getConsumedValue(
      pp,
      EraAccounts.lookupAccountDeposit[E](_, accounts)(using c.accountsOf),
      utxo,
      body
    )

  /** `produced` (`Shelley/UTxO.hs:120-129`, cardano-ledger f649f975): what the body produces, a
    * pool registered when `pState` has it.
    */
  def produced[E <: EraTag](pp: PParams[E], pState: PState, body: TxBody[TxLevel.Top, E])(using
      u: EraUTxO[E]
  ): Value[E] =
    u.getProducedValue(pp, pState.stakePools.contains, body)

  /** `shelleyProducedValue` (`Shelley/UTxO.hs:131-143`, cardano-ledger f649f975): the outputs, the
    * fee and the deposits.
    */
  def shelleyProducedValue[E <: EraTag](
      pp: PParams[E],
      isRegPoolId: KeyHash[StakePool] => Boolean,
      body: TxBody[TxLevel.Top, E]
  )(using b: EraTxBody[E]): Value[E] =
    given EraTxOut[E] = b.outputsOf
    val v = b.outputsOf.valueOps
    v.plus(
      UTxOs.sumAllValue(b.outputs[TxLevel.Top](body)(using b.top)),
      v.inject(Coin(b.fee(body).value + b.getTotalDepositsTxBody(pp, isRegPoolId, body).value))
    )

/** Mary's functions of the UTxO set (`eras/mary/impl/src/Cardano/Ledger/Mary/UTxO.hs`,
  * cardano-ledger f649f975), of the eras whose value is `MaryValue` (`Value era ~ MaryValue`, given
  * as `v`).
  */
object MaryUTxO:
  /** `getConsumedMaryValue` (`Mary/UTxO.hs:65-81`, cardano-ledger f649f975): the spent outputs, the
    * refunds, the withdrawals and the assets the body mints.
    */
  def getConsumedMaryValue[E <: EraTag, L <: TxLevel](
      pp: PParams[E],
      lookupStakingDeposit: Credential[Staking] => Option[Coin],
      utxo: UTxO[E],
      body: TxBody[L, E]
  )(using b: MaryEraTxBody[E], l: b.Level[L], v: Value[E] =:= MultiAssetValue): MultiAssetValue =
    given EraTxOut[E] = b.outputsOf
    val ops = summon[Val[MultiAssetValue]]
    val mintedMultiAsset =
      MultiAssetValue.of(xyz.kd5ujc.scarab.ledger.Value.minted(b.mint(body)))
    val refunds = b.getTotalRefundsTxBody(pp, lookupStakingDeposit, body)
    val withdrawals = b.withdrawals(body).unWithdrawals.valuesIterator.map(_.value).sum
    val consumedValue = ops.plus(
      v(UTxOs.sumUTxO(UTxOs.txInsFilter(utxo, b.inputs(body)))),
      ops.inject(Coin(refunds.value + withdrawals))
    )
    ops.plus(consumedValue, mintedMultiAsset)

  /** `getProducedMaryValue` (`Mary/UTxO.hs:83-91`, cardano-ledger f649f975): Shelley's produced
    * value and the assets the body burns.
    */
  def getProducedMaryValue[E <: EraTag](
      pp: PParams[E],
      isPoolRegistered: KeyHash[StakePool] => Boolean,
      body: TxBody[TxLevel.Top, E]
  )(using b: MaryEraTxBody[E], v: Value[E] =:= MultiAssetValue): MultiAssetValue =
    summon[Val[MultiAssetValue]].plus(
      v(ShelleyUTxO.shelleyProducedValue(pp, isPoolRegistered, body)),
      burnedMultiAssets[E, TxLevel.Top](body)(using b, b.top)
    )

  /** `burnedMultiAssets` (`Mary/UTxO.hs:93-97`, cardano-ledger f649f975): the assets the body mints
    * a negative quantity of, negated.
    */
  def burnedMultiAssets[E <: EraTag, L <: TxLevel](body: TxBody[L, E])(using
      b: MaryEraTxBody[E],
      l: b.Level[L]
  ): MultiAssetValue =
    MultiAssetValue.of(xyz.kd5ujc.scarab.ledger.Value.burned(b.mint(body)))

/** Babbage's functions of the UTxO set (`eras/babbage/impl/src/Cardano/Ledger/Babbage/UTxO.hs`,
  * cardano-ledger f649f975).
  */
object BabbageUTxO:
  /** `getReferenceScriptsNonDistinct` (`Babbage/UTxO.hs:157-166`, cardano-ledger f649f975): the
    * reference script of each output of `inputs` that has one, a script as many times as outputs
    * carry it, with its hash, in the order of the inputs (`Map.elems`).
    */
  def getReferenceScriptsNonDistinct[E <: EraTag](utxo: UTxO[E], inputs: Set[TxIn])(using
      o: BabbageEraTxOut[E]
  ): Vector[(xyz.kd5ujc.scarab.codec.Bytes, Script[E])] =
    referenceScriptsNonDistinct(utxo, inputs).map(script => (o.scripts.hashScript(script), script))

  /** The scripts of [[getReferenceScriptsNonDistinct]] without their hashes, for a reader of the
    * scripts alone (`txNonDistinctRefScriptsSize`), which never forces the reference's lazy hashes:
    * so a script is not hashed to be measured (#85).
    */
  def referenceScriptsNonDistinct[E <: EraTag](utxo: UTxO[E], inputs: Set[TxIn])(using
      o: BabbageEraTxOut[E]
  ): Vector[Script[E]] =
    UTxOs
      .txInsFilter(utxo, inputs)
      .unUTxO
      .toVector
      .sortBy(_._1)
      .flatMap((_, out) => o.referenceScript(out))

  /** `getReferenceScripts` (`Babbage/UTxO.hs:150-155`, cardano-ledger f649f975): the reference
    * scripts of `inputs` ' outputs by hash (`Map.fromList`, a later one of a hash kept).
    */
  def getReferenceScripts[E <: EraTag](utxo: UTxO[E], inputs: Set[TxIn])(using
      BabbageEraTxOut[E]
  ): Map[Bytes, Script[E]] =
    getReferenceScriptsNonDistinct(utxo, inputs).toMap

  /** `getBabbageSupplementalDataHashes` (`Babbage/UTxO.hs:74-83`, cardano-ledger f649f975): the
    * hashes of the datums of the body's outputs, its collateral return among them
    * (`allSizedOutputsTxBodyF`), and of the outputs of its reference inputs the UTxO set holds.
    */
  def getBabbageSupplementalDataHashes[E <: EraTag, L <: TxLevel](
      utxo: UTxO[E],
      txBody: TxBody[L, E]
  )(using b: BabbageEraTxBody[E], l: b.Level[L]): Set[Bytes] =
    val newOuts = b.allSizedOutputs[L](txBody).map(_.value)
    val referencedOuts = UTxOs.txInsFilter(utxo, b.referenceInputs[L](txBody)).unUTxO.values
    val outs = newOuts ++ referencedOuts
    outs.flatMap(b.outputsOf.dataHash).toSet

  /** `getBabbageSpendingDatum` (`Babbage/UTxO.hs:88-101`, cardano-ledger f649f975): of a spending
    * purpose whose input the UTxO set holds, the output's inline datum, or else the witnesses'
    * datum of its datum hash. The output's inline datum is read as data first, as the reference's
    * `<|>` forces its strict `SJust`; its failure, which only an inline datum taken unread
    * ([[BabbageTxOutRep.fromStored]]) can reach, is returned where the reference raises `error`.
    */
  def getBabbageSpendingDatum[E <: EraTag, L <: TxLevel](
      utxo: UTxO[E],
      tx: Tx[L, E],
      sp: PlutusPurpose[AsItem, E]
  )(using
      t: AlonzoEraTx[E],
      l: t.bodyOf.Level[L],
      o: BabbageEraTxOut[E],
      s: ConwayEraScript[E]
  ): Either[PlutusData.DecodeError, Option[Data]] =
    val spent =
      for
        AsItem(txIn) <- s.toSpendingPurpose(sp)
        txOut <- utxo.unUTxO.get(txIn)
      yield txOut
    spent.flatTraverse { txOut =>
      val txOutDataFromWits =
        o.dataHash(txOut).flatMap(t.witsOf.datsTxWits(t.wits[L](tx)).datums.get)
      o.data(txOut).map(_.orElse(txOutDataFromWits))
    }

  /** `getBabbageScriptsProvided` (`Babbage/UTxO.hs:136-146`, cardano-ledger f649f975): the
    * reference scripts of the spent and reference inputs, and the witnesses' scripts, a reference
    * script kept where both have a hash (`Map.union`, left-biased).
    */
  def getBabbageScriptsProvided[E <: EraTag, L <: TxLevel](utxo: UTxO[E], tx: Tx[L, E])(using
      t: EraTx[E],
      b: BabbageEraTxBody[E],
      l: t.bodyOf.Level[L],
      lb: b.Level[L],
      o: BabbageEraTxOut[E]
  ): ScriptsProvided[E] =
    val txBody = t.body[L](tx)
    val ins: Set[TxIn] = b.referenceInputs[L](txBody)(using lb) ++ b.inputs[L](txBody)(using lb)
    ScriptsProvided(t.witsOf.scriptTxWits(t.wits[L](tx)) ++ getReferenceScripts(utxo, ins))

/** The instances of each era, the one place that lists them: Conway's (`instance EraUTxO ConwayEra`
  * and `instance AlonzoEraUTxO ConwayEra`,
  * `eras/conway/impl/src/Cardano/Ledger/Conway/UTxO.hs:119-147`, cardano-ledger f649f975) and
  * Dijkstra's (`Dijkstra/UTxO.hs:148-163, 200-211, 291-298`, cardano-ledger 9df9f98c).
  */
object EraUTxO:
  given conway: AlonzoEraUTxO[Conway] = xyz.kd5ujc.scarab.ledger.era.conway.ConwayUTxO.instance
  given dijkstra: (DijkstraEraUTxO[Dijkstra] { val txOf: EraTx.dijkstra.type }) =
    xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraUTxO.instance
