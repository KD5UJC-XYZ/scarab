// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import cats.Monad
import cats.syntax.all.*

/** The costing functions of the builtins, the reference's `BuiltinCostModelBase`
  * (`PlutusCore/Evaluation/Machine/BuiltinCostModel.hs:82-203`, plutus 1.70.0.0 `507cab8c`), one
  * field per builtin that has a cost-model entry, in the reference's order and under its JSON name
  * (the field name less `param`, initial in lower case: `builtinCostModelOptions`, lines 221-226).
  * `keepPolicies` and `dropPolicies` have none: they are costed by `unimplementedCostingFun`
  * ([[BuiltinCosting]]).
  *
  * The leaf type `A` is `Unit` for the shapes of a semantics variant ([[BuiltinShapes]]) and
  * [[SatInt]] for a model that costs. Generated from the reference's field list; `CostModelSuite`
  * compares it with the vendored `builtinCostModel*.json` of the 1.70.0.0 view.
  */
final case class BuiltinCostModel[A](
    addInteger: CostingFun[TwoArguments, A],
    subtractInteger: CostingFun[TwoArguments, A],
    multiplyInteger: CostingFun[TwoArguments, A],
    divideInteger: CostingFun[TwoArguments, A],
    quotientInteger: CostingFun[TwoArguments, A],
    remainderInteger: CostingFun[TwoArguments, A],
    modInteger: CostingFun[TwoArguments, A],
    equalsInteger: CostingFun[TwoArguments, A],
    lessThanInteger: CostingFun[TwoArguments, A],
    lessThanEqualsInteger: CostingFun[TwoArguments, A],
    appendByteString: CostingFun[TwoArguments, A],
    consByteString: CostingFun[TwoArguments, A],
    sliceByteString: CostingFun[ThreeArguments, A],
    lengthOfByteString: CostingFun[OneArgument, A],
    indexByteString: CostingFun[TwoArguments, A],
    equalsByteString: CostingFun[TwoArguments, A],
    lessThanByteString: CostingFun[TwoArguments, A],
    lessThanEqualsByteString: CostingFun[TwoArguments, A],
    sha2_256: CostingFun[OneArgument, A],
    sha3_256: CostingFun[OneArgument, A],
    blake2b_256: CostingFun[OneArgument, A],
    verifyEd25519Signature: CostingFun[ThreeArguments, A],
    verifyEcdsaSecp256k1Signature: CostingFun[ThreeArguments, A],
    verifySchnorrSecp256k1Signature: CostingFun[ThreeArguments, A],
    appendString: CostingFun[TwoArguments, A],
    equalsString: CostingFun[TwoArguments, A],
    encodeUtf8: CostingFun[OneArgument, A],
    decodeUtf8: CostingFun[OneArgument, A],
    ifThenElse: CostingFun[ThreeArguments, A],
    chooseUnit: CostingFun[TwoArguments, A],
    trace: CostingFun[TwoArguments, A],
    fstPair: CostingFun[OneArgument, A],
    sndPair: CostingFun[OneArgument, A],
    chooseList: CostingFun[ThreeArguments, A],
    mkCons: CostingFun[TwoArguments, A],
    headList: CostingFun[OneArgument, A],
    tailList: CostingFun[OneArgument, A],
    nullList: CostingFun[OneArgument, A],
    chooseData: CostingFun[SixArguments, A],
    constrData: CostingFun[TwoArguments, A],
    mapData: CostingFun[OneArgument, A],
    listData: CostingFun[OneArgument, A],
    iData: CostingFun[OneArgument, A],
    bData: CostingFun[OneArgument, A],
    unConstrData: CostingFun[OneArgument, A],
    unMapData: CostingFun[OneArgument, A],
    unListData: CostingFun[OneArgument, A],
    unIData: CostingFun[OneArgument, A],
    unBData: CostingFun[OneArgument, A],
    equalsData: CostingFun[TwoArguments, A],
    mkPairData: CostingFun[TwoArguments, A],
    mkNilData: CostingFun[OneArgument, A],
    mkNilPairData: CostingFun[OneArgument, A],
    serialiseData: CostingFun[OneArgument, A],
    bls12_381_G1_add: CostingFun[TwoArguments, A],
    bls12_381_G1_neg: CostingFun[OneArgument, A],
    bls12_381_G1_scalarMul: CostingFun[TwoArguments, A],
    bls12_381_G1_multiScalarMul: CostingFun[TwoArguments, A],
    bls12_381_G1_equal: CostingFun[TwoArguments, A],
    bls12_381_G1_compress: CostingFun[OneArgument, A],
    bls12_381_G1_uncompress: CostingFun[OneArgument, A],
    bls12_381_G1_hashToGroup: CostingFun[TwoArguments, A],
    bls12_381_G2_add: CostingFun[TwoArguments, A],
    bls12_381_G2_neg: CostingFun[OneArgument, A],
    bls12_381_G2_scalarMul: CostingFun[TwoArguments, A],
    bls12_381_G2_equal: CostingFun[TwoArguments, A],
    bls12_381_G2_multiScalarMul: CostingFun[TwoArguments, A],
    bls12_381_G2_compress: CostingFun[OneArgument, A],
    bls12_381_G2_uncompress: CostingFun[OneArgument, A],
    bls12_381_G2_hashToGroup: CostingFun[TwoArguments, A],
    bls12_381_millerLoop: CostingFun[TwoArguments, A],
    bls12_381_mulMlResult: CostingFun[TwoArguments, A],
    bls12_381_finalVerify: CostingFun[TwoArguments, A],
    keccak_256: CostingFun[OneArgument, A],
    blake2b_224: CostingFun[OneArgument, A],
    integerToByteString: CostingFun[ThreeArguments, A],
    byteStringToInteger: CostingFun[TwoArguments, A],
    andByteString: CostingFun[ThreeArguments, A],
    orByteString: CostingFun[ThreeArguments, A],
    xorByteString: CostingFun[ThreeArguments, A],
    complementByteString: CostingFun[OneArgument, A],
    readBit: CostingFun[TwoArguments, A],
    writeBits: CostingFun[ThreeArguments, A],
    replicateByte: CostingFun[TwoArguments, A],
    shiftByteString: CostingFun[TwoArguments, A],
    rotateByteString: CostingFun[TwoArguments, A],
    countSetBits: CostingFun[OneArgument, A],
    findFirstSetBit: CostingFun[OneArgument, A],
    ripemd_160: CostingFun[OneArgument, A],
    expModInteger: CostingFun[ThreeArguments, A],
    dropList: CostingFun[TwoArguments, A],
    lengthOfArray: CostingFun[OneArgument, A],
    listToArray: CostingFun[OneArgument, A],
    indexArray: CostingFun[TwoArguments, A],
    lookupCoin: CostingFun[ThreeArguments, A],
    valueContains: CostingFun[TwoArguments, A],
    valueData: CostingFun[OneArgument, A],
    unValueData: CostingFun[OneArgument, A],
    insertCoin: CostingFun[FourArguments, A],
    unionValue: CostingFun[TwoArguments, A],
    scaleValue: CostingFun[TwoArguments, A],
    multiIndexArray: CostingFun[TwoArguments, A],
    assetCount: CostingFun[OneArgument, A],
    policies: CostingFun[OneArgument, A]
):
  /** Every coefficient, in field order, made into `B` by `f` with its parameter name; the first
    * failure of `G` wins.
    */
  def traverse[G[_]: Monad, B](f: (String, A) => G[B]): G[BuiltinCostModel[B]] =
    for
      addInteger <- this.addInteger.traverse("addInteger")(f)
      subtractInteger <- this.subtractInteger.traverse("subtractInteger")(f)
      multiplyInteger <- this.multiplyInteger.traverse("multiplyInteger")(f)
      divideInteger <- this.divideInteger.traverse("divideInteger")(f)
      quotientInteger <- this.quotientInteger.traverse("quotientInteger")(f)
      remainderInteger <- this.remainderInteger.traverse("remainderInteger")(f)
      modInteger <- this.modInteger.traverse("modInteger")(f)
      equalsInteger <- this.equalsInteger.traverse("equalsInteger")(f)
      lessThanInteger <- this.lessThanInteger.traverse("lessThanInteger")(f)
      lessThanEqualsInteger <- this.lessThanEqualsInteger.traverse("lessThanEqualsInteger")(f)
      appendByteString <- this.appendByteString.traverse("appendByteString")(f)
      consByteString <- this.consByteString.traverse("consByteString")(f)
      sliceByteString <- this.sliceByteString.traverse("sliceByteString")(f)
      lengthOfByteString <- this.lengthOfByteString.traverse("lengthOfByteString")(f)
      indexByteString <- this.indexByteString.traverse("indexByteString")(f)
      equalsByteString <- this.equalsByteString.traverse("equalsByteString")(f)
      lessThanByteString <- this.lessThanByteString.traverse("lessThanByteString")(f)
      lessThanEqualsByteString <- this.lessThanEqualsByteString.traverse(
        "lessThanEqualsByteString"
      )(f)
      sha2_256 <- this.sha2_256.traverse("sha2_256")(f)
      sha3_256 <- this.sha3_256.traverse("sha3_256")(f)
      blake2b_256 <- this.blake2b_256.traverse("blake2b_256")(f)
      verifyEd25519Signature <- this.verifyEd25519Signature.traverse("verifyEd25519Signature")(f)
      verifyEcdsaSecp256k1Signature <- this.verifyEcdsaSecp256k1Signature.traverse(
        "verifyEcdsaSecp256k1Signature"
      )(f)
      verifySchnorrSecp256k1Signature <- this.verifySchnorrSecp256k1Signature.traverse(
        "verifySchnorrSecp256k1Signature"
      )(f)
      appendString <- this.appendString.traverse("appendString")(f)
      equalsString <- this.equalsString.traverse("equalsString")(f)
      encodeUtf8 <- this.encodeUtf8.traverse("encodeUtf8")(f)
      decodeUtf8 <- this.decodeUtf8.traverse("decodeUtf8")(f)
      ifThenElse <- this.ifThenElse.traverse("ifThenElse")(f)
      chooseUnit <- this.chooseUnit.traverse("chooseUnit")(f)
      trace <- this.trace.traverse("trace")(f)
      fstPair <- this.fstPair.traverse("fstPair")(f)
      sndPair <- this.sndPair.traverse("sndPair")(f)
      chooseList <- this.chooseList.traverse("chooseList")(f)
      mkCons <- this.mkCons.traverse("mkCons")(f)
      headList <- this.headList.traverse("headList")(f)
      tailList <- this.tailList.traverse("tailList")(f)
      nullList <- this.nullList.traverse("nullList")(f)
      chooseData <- this.chooseData.traverse("chooseData")(f)
      constrData <- this.constrData.traverse("constrData")(f)
      mapData <- this.mapData.traverse("mapData")(f)
      listData <- this.listData.traverse("listData")(f)
      iData <- this.iData.traverse("iData")(f)
      bData <- this.bData.traverse("bData")(f)
      unConstrData <- this.unConstrData.traverse("unConstrData")(f)
      unMapData <- this.unMapData.traverse("unMapData")(f)
      unListData <- this.unListData.traverse("unListData")(f)
      unIData <- this.unIData.traverse("unIData")(f)
      unBData <- this.unBData.traverse("unBData")(f)
      equalsData <- this.equalsData.traverse("equalsData")(f)
      mkPairData <- this.mkPairData.traverse("mkPairData")(f)
      mkNilData <- this.mkNilData.traverse("mkNilData")(f)
      mkNilPairData <- this.mkNilPairData.traverse("mkNilPairData")(f)
      serialiseData <- this.serialiseData.traverse("serialiseData")(f)
      bls12_381_G1_add <- this.bls12_381_G1_add.traverse("bls12_381_G1_add")(f)
      bls12_381_G1_neg <- this.bls12_381_G1_neg.traverse("bls12_381_G1_neg")(f)
      bls12_381_G1_scalarMul <- this.bls12_381_G1_scalarMul.traverse("bls12_381_G1_scalarMul")(f)
      bls12_381_G1_multiScalarMul <- this.bls12_381_G1_multiScalarMul.traverse(
        "bls12_381_G1_multiScalarMul"
      )(f)
      bls12_381_G1_equal <- this.bls12_381_G1_equal.traverse("bls12_381_G1_equal")(f)
      bls12_381_G1_compress <- this.bls12_381_G1_compress.traverse("bls12_381_G1_compress")(f)
      bls12_381_G1_uncompress <- this.bls12_381_G1_uncompress.traverse("bls12_381_G1_uncompress")(f)
      bls12_381_G1_hashToGroup <- this.bls12_381_G1_hashToGroup.traverse(
        "bls12_381_G1_hashToGroup"
      )(f)
      bls12_381_G2_add <- this.bls12_381_G2_add.traverse("bls12_381_G2_add")(f)
      bls12_381_G2_neg <- this.bls12_381_G2_neg.traverse("bls12_381_G2_neg")(f)
      bls12_381_G2_scalarMul <- this.bls12_381_G2_scalarMul.traverse("bls12_381_G2_scalarMul")(f)
      bls12_381_G2_equal <- this.bls12_381_G2_equal.traverse("bls12_381_G2_equal")(f)
      bls12_381_G2_multiScalarMul <- this.bls12_381_G2_multiScalarMul.traverse(
        "bls12_381_G2_multiScalarMul"
      )(f)
      bls12_381_G2_compress <- this.bls12_381_G2_compress.traverse("bls12_381_G2_compress")(f)
      bls12_381_G2_uncompress <- this.bls12_381_G2_uncompress.traverse("bls12_381_G2_uncompress")(f)
      bls12_381_G2_hashToGroup <- this.bls12_381_G2_hashToGroup.traverse(
        "bls12_381_G2_hashToGroup"
      )(f)
      bls12_381_millerLoop <- this.bls12_381_millerLoop.traverse("bls12_381_millerLoop")(f)
      bls12_381_mulMlResult <- this.bls12_381_mulMlResult.traverse("bls12_381_mulMlResult")(f)
      bls12_381_finalVerify <- this.bls12_381_finalVerify.traverse("bls12_381_finalVerify")(f)
      keccak_256 <- this.keccak_256.traverse("keccak_256")(f)
      blake2b_224 <- this.blake2b_224.traverse("blake2b_224")(f)
      integerToByteString <- this.integerToByteString.traverse("integerToByteString")(f)
      byteStringToInteger <- this.byteStringToInteger.traverse("byteStringToInteger")(f)
      andByteString <- this.andByteString.traverse("andByteString")(f)
      orByteString <- this.orByteString.traverse("orByteString")(f)
      xorByteString <- this.xorByteString.traverse("xorByteString")(f)
      complementByteString <- this.complementByteString.traverse("complementByteString")(f)
      readBit <- this.readBit.traverse("readBit")(f)
      writeBits <- this.writeBits.traverse("writeBits")(f)
      replicateByte <- this.replicateByte.traverse("replicateByte")(f)
      shiftByteString <- this.shiftByteString.traverse("shiftByteString")(f)
      rotateByteString <- this.rotateByteString.traverse("rotateByteString")(f)
      countSetBits <- this.countSetBits.traverse("countSetBits")(f)
      findFirstSetBit <- this.findFirstSetBit.traverse("findFirstSetBit")(f)
      ripemd_160 <- this.ripemd_160.traverse("ripemd_160")(f)
      expModInteger <- this.expModInteger.traverse("expModInteger")(f)
      dropList <- this.dropList.traverse("dropList")(f)
      lengthOfArray <- this.lengthOfArray.traverse("lengthOfArray")(f)
      listToArray <- this.listToArray.traverse("listToArray")(f)
      indexArray <- this.indexArray.traverse("indexArray")(f)
      lookupCoin <- this.lookupCoin.traverse("lookupCoin")(f)
      valueContains <- this.valueContains.traverse("valueContains")(f)
      valueData <- this.valueData.traverse("valueData")(f)
      unValueData <- this.unValueData.traverse("unValueData")(f)
      insertCoin <- this.insertCoin.traverse("insertCoin")(f)
      unionValue <- this.unionValue.traverse("unionValue")(f)
      scaleValue <- this.scaleValue.traverse("scaleValue")(f)
      multiIndexArray <- this.multiIndexArray.traverse("multiIndexArray")(f)
      assetCount <- this.assetCount.traverse("assetCount")(f)
      policies <- this.policies.traverse("policies")(f)
    yield BuiltinCostModel(
      addInteger,
      subtractInteger,
      multiplyInteger,
      divideInteger,
      quotientInteger,
      remainderInteger,
      modInteger,
      equalsInteger,
      lessThanInteger,
      lessThanEqualsInteger,
      appendByteString,
      consByteString,
      sliceByteString,
      lengthOfByteString,
      indexByteString,
      equalsByteString,
      lessThanByteString,
      lessThanEqualsByteString,
      sha2_256,
      sha3_256,
      blake2b_256,
      verifyEd25519Signature,
      verifyEcdsaSecp256k1Signature,
      verifySchnorrSecp256k1Signature,
      appendString,
      equalsString,
      encodeUtf8,
      decodeUtf8,
      ifThenElse,
      chooseUnit,
      trace,
      fstPair,
      sndPair,
      chooseList,
      mkCons,
      headList,
      tailList,
      nullList,
      chooseData,
      constrData,
      mapData,
      listData,
      iData,
      bData,
      unConstrData,
      unMapData,
      unListData,
      unIData,
      unBData,
      equalsData,
      mkPairData,
      mkNilData,
      mkNilPairData,
      serialiseData,
      bls12_381_G1_add,
      bls12_381_G1_neg,
      bls12_381_G1_scalarMul,
      bls12_381_G1_multiScalarMul,
      bls12_381_G1_equal,
      bls12_381_G1_compress,
      bls12_381_G1_uncompress,
      bls12_381_G1_hashToGroup,
      bls12_381_G2_add,
      bls12_381_G2_neg,
      bls12_381_G2_scalarMul,
      bls12_381_G2_equal,
      bls12_381_G2_multiScalarMul,
      bls12_381_G2_compress,
      bls12_381_G2_uncompress,
      bls12_381_G2_hashToGroup,
      bls12_381_millerLoop,
      bls12_381_mulMlResult,
      bls12_381_finalVerify,
      keccak_256,
      blake2b_224,
      integerToByteString,
      byteStringToInteger,
      andByteString,
      orByteString,
      xorByteString,
      complementByteString,
      readBit,
      writeBits,
      replicateByte,
      shiftByteString,
      rotateByteString,
      countSetBits,
      findFirstSetBit,
      ripemd_160,
      expModInteger,
      dropList,
      lengthOfArray,
      listToArray,
      indexArray,
      lookupCoin,
      valueContains,
      valueData,
      unValueData,
      insertCoin,
      unionValue,
      scaleValue,
      multiIndexArray,
      assetCount,
      policies
    )

  /** Every coefficient with its parameter name. */
  def leaves: Vector[(String, A)] =
    Vector(
      addInteger.leaves("addInteger"),
      subtractInteger.leaves("subtractInteger"),
      multiplyInteger.leaves("multiplyInteger"),
      divideInteger.leaves("divideInteger"),
      quotientInteger.leaves("quotientInteger"),
      remainderInteger.leaves("remainderInteger"),
      modInteger.leaves("modInteger"),
      equalsInteger.leaves("equalsInteger"),
      lessThanInteger.leaves("lessThanInteger"),
      lessThanEqualsInteger.leaves("lessThanEqualsInteger"),
      appendByteString.leaves("appendByteString"),
      consByteString.leaves("consByteString"),
      sliceByteString.leaves("sliceByteString"),
      lengthOfByteString.leaves("lengthOfByteString"),
      indexByteString.leaves("indexByteString"),
      equalsByteString.leaves("equalsByteString"),
      lessThanByteString.leaves("lessThanByteString"),
      lessThanEqualsByteString.leaves("lessThanEqualsByteString"),
      sha2_256.leaves("sha2_256"),
      sha3_256.leaves("sha3_256"),
      blake2b_256.leaves("blake2b_256"),
      verifyEd25519Signature.leaves("verifyEd25519Signature"),
      verifyEcdsaSecp256k1Signature.leaves("verifyEcdsaSecp256k1Signature"),
      verifySchnorrSecp256k1Signature.leaves("verifySchnorrSecp256k1Signature"),
      appendString.leaves("appendString"),
      equalsString.leaves("equalsString"),
      encodeUtf8.leaves("encodeUtf8"),
      decodeUtf8.leaves("decodeUtf8"),
      ifThenElse.leaves("ifThenElse"),
      chooseUnit.leaves("chooseUnit"),
      trace.leaves("trace"),
      fstPair.leaves("fstPair"),
      sndPair.leaves("sndPair"),
      chooseList.leaves("chooseList"),
      mkCons.leaves("mkCons"),
      headList.leaves("headList"),
      tailList.leaves("tailList"),
      nullList.leaves("nullList"),
      chooseData.leaves("chooseData"),
      constrData.leaves("constrData"),
      mapData.leaves("mapData"),
      listData.leaves("listData"),
      iData.leaves("iData"),
      bData.leaves("bData"),
      unConstrData.leaves("unConstrData"),
      unMapData.leaves("unMapData"),
      unListData.leaves("unListData"),
      unIData.leaves("unIData"),
      unBData.leaves("unBData"),
      equalsData.leaves("equalsData"),
      mkPairData.leaves("mkPairData"),
      mkNilData.leaves("mkNilData"),
      mkNilPairData.leaves("mkNilPairData"),
      serialiseData.leaves("serialiseData"),
      bls12_381_G1_add.leaves("bls12_381_G1_add"),
      bls12_381_G1_neg.leaves("bls12_381_G1_neg"),
      bls12_381_G1_scalarMul.leaves("bls12_381_G1_scalarMul"),
      bls12_381_G1_multiScalarMul.leaves("bls12_381_G1_multiScalarMul"),
      bls12_381_G1_equal.leaves("bls12_381_G1_equal"),
      bls12_381_G1_compress.leaves("bls12_381_G1_compress"),
      bls12_381_G1_uncompress.leaves("bls12_381_G1_uncompress"),
      bls12_381_G1_hashToGroup.leaves("bls12_381_G1_hashToGroup"),
      bls12_381_G2_add.leaves("bls12_381_G2_add"),
      bls12_381_G2_neg.leaves("bls12_381_G2_neg"),
      bls12_381_G2_scalarMul.leaves("bls12_381_G2_scalarMul"),
      bls12_381_G2_equal.leaves("bls12_381_G2_equal"),
      bls12_381_G2_multiScalarMul.leaves("bls12_381_G2_multiScalarMul"),
      bls12_381_G2_compress.leaves("bls12_381_G2_compress"),
      bls12_381_G2_uncompress.leaves("bls12_381_G2_uncompress"),
      bls12_381_G2_hashToGroup.leaves("bls12_381_G2_hashToGroup"),
      bls12_381_millerLoop.leaves("bls12_381_millerLoop"),
      bls12_381_mulMlResult.leaves("bls12_381_mulMlResult"),
      bls12_381_finalVerify.leaves("bls12_381_finalVerify"),
      keccak_256.leaves("keccak_256"),
      blake2b_224.leaves("blake2b_224"),
      integerToByteString.leaves("integerToByteString"),
      byteStringToInteger.leaves("byteStringToInteger"),
      andByteString.leaves("andByteString"),
      orByteString.leaves("orByteString"),
      xorByteString.leaves("xorByteString"),
      complementByteString.leaves("complementByteString"),
      readBit.leaves("readBit"),
      writeBits.leaves("writeBits"),
      replicateByte.leaves("replicateByte"),
      shiftByteString.leaves("shiftByteString"),
      rotateByteString.leaves("rotateByteString"),
      countSetBits.leaves("countSetBits"),
      findFirstSetBit.leaves("findFirstSetBit"),
      ripemd_160.leaves("ripemd_160"),
      expModInteger.leaves("expModInteger"),
      dropList.leaves("dropList"),
      lengthOfArray.leaves("lengthOfArray"),
      listToArray.leaves("listToArray"),
      indexArray.leaves("indexArray"),
      lookupCoin.leaves("lookupCoin"),
      valueContains.leaves("valueContains"),
      valueData.leaves("valueData"),
      unValueData.leaves("unValueData"),
      insertCoin.leaves("insertCoin"),
      unionValue.leaves("unionValue"),
      scaleValue.leaves("scaleValue"),
      multiIndexArray.leaves("multiIndexArray"),
      assetCount.leaves("assetCount"),
      policies.leaves("policies")
    ).flatten

  /** Every `"type"` key of the JSON encoding with the shape it names. */
  def tags: Vector[(String, String)] =
    Vector(
      addInteger.tags("addInteger"),
      subtractInteger.tags("subtractInteger"),
      multiplyInteger.tags("multiplyInteger"),
      divideInteger.tags("divideInteger"),
      quotientInteger.tags("quotientInteger"),
      remainderInteger.tags("remainderInteger"),
      modInteger.tags("modInteger"),
      equalsInteger.tags("equalsInteger"),
      lessThanInteger.tags("lessThanInteger"),
      lessThanEqualsInteger.tags("lessThanEqualsInteger"),
      appendByteString.tags("appendByteString"),
      consByteString.tags("consByteString"),
      sliceByteString.tags("sliceByteString"),
      lengthOfByteString.tags("lengthOfByteString"),
      indexByteString.tags("indexByteString"),
      equalsByteString.tags("equalsByteString"),
      lessThanByteString.tags("lessThanByteString"),
      lessThanEqualsByteString.tags("lessThanEqualsByteString"),
      sha2_256.tags("sha2_256"),
      sha3_256.tags("sha3_256"),
      blake2b_256.tags("blake2b_256"),
      verifyEd25519Signature.tags("verifyEd25519Signature"),
      verifyEcdsaSecp256k1Signature.tags("verifyEcdsaSecp256k1Signature"),
      verifySchnorrSecp256k1Signature.tags("verifySchnorrSecp256k1Signature"),
      appendString.tags("appendString"),
      equalsString.tags("equalsString"),
      encodeUtf8.tags("encodeUtf8"),
      decodeUtf8.tags("decodeUtf8"),
      ifThenElse.tags("ifThenElse"),
      chooseUnit.tags("chooseUnit"),
      trace.tags("trace"),
      fstPair.tags("fstPair"),
      sndPair.tags("sndPair"),
      chooseList.tags("chooseList"),
      mkCons.tags("mkCons"),
      headList.tags("headList"),
      tailList.tags("tailList"),
      nullList.tags("nullList"),
      chooseData.tags("chooseData"),
      constrData.tags("constrData"),
      mapData.tags("mapData"),
      listData.tags("listData"),
      iData.tags("iData"),
      bData.tags("bData"),
      unConstrData.tags("unConstrData"),
      unMapData.tags("unMapData"),
      unListData.tags("unListData"),
      unIData.tags("unIData"),
      unBData.tags("unBData"),
      equalsData.tags("equalsData"),
      mkPairData.tags("mkPairData"),
      mkNilData.tags("mkNilData"),
      mkNilPairData.tags("mkNilPairData"),
      serialiseData.tags("serialiseData"),
      bls12_381_G1_add.tags("bls12_381_G1_add"),
      bls12_381_G1_neg.tags("bls12_381_G1_neg"),
      bls12_381_G1_scalarMul.tags("bls12_381_G1_scalarMul"),
      bls12_381_G1_multiScalarMul.tags("bls12_381_G1_multiScalarMul"),
      bls12_381_G1_equal.tags("bls12_381_G1_equal"),
      bls12_381_G1_compress.tags("bls12_381_G1_compress"),
      bls12_381_G1_uncompress.tags("bls12_381_G1_uncompress"),
      bls12_381_G1_hashToGroup.tags("bls12_381_G1_hashToGroup"),
      bls12_381_G2_add.tags("bls12_381_G2_add"),
      bls12_381_G2_neg.tags("bls12_381_G2_neg"),
      bls12_381_G2_scalarMul.tags("bls12_381_G2_scalarMul"),
      bls12_381_G2_equal.tags("bls12_381_G2_equal"),
      bls12_381_G2_multiScalarMul.tags("bls12_381_G2_multiScalarMul"),
      bls12_381_G2_compress.tags("bls12_381_G2_compress"),
      bls12_381_G2_uncompress.tags("bls12_381_G2_uncompress"),
      bls12_381_G2_hashToGroup.tags("bls12_381_G2_hashToGroup"),
      bls12_381_millerLoop.tags("bls12_381_millerLoop"),
      bls12_381_mulMlResult.tags("bls12_381_mulMlResult"),
      bls12_381_finalVerify.tags("bls12_381_finalVerify"),
      keccak_256.tags("keccak_256"),
      blake2b_224.tags("blake2b_224"),
      integerToByteString.tags("integerToByteString"),
      byteStringToInteger.tags("byteStringToInteger"),
      andByteString.tags("andByteString"),
      orByteString.tags("orByteString"),
      xorByteString.tags("xorByteString"),
      complementByteString.tags("complementByteString"),
      readBit.tags("readBit"),
      writeBits.tags("writeBits"),
      replicateByte.tags("replicateByte"),
      shiftByteString.tags("shiftByteString"),
      rotateByteString.tags("rotateByteString"),
      countSetBits.tags("countSetBits"),
      findFirstSetBit.tags("findFirstSetBit"),
      ripemd_160.tags("ripemd_160"),
      expModInteger.tags("expModInteger"),
      dropList.tags("dropList"),
      lengthOfArray.tags("lengthOfArray"),
      listToArray.tags("listToArray"),
      indexArray.tags("indexArray"),
      lookupCoin.tags("lookupCoin"),
      valueContains.tags("valueContains"),
      valueData.tags("valueData"),
      unValueData.tags("unValueData"),
      insertCoin.tags("insertCoin"),
      unionValue.tags("unionValue"),
      scaleValue.tags("scaleValue"),
      multiIndexArray.tags("multiIndexArray"),
      assetCount.tags("assetCount"),
      policies.tags("policies")
    ).flatten
