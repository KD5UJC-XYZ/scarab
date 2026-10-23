// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.byron

import cats.Show

/** The keys of `blockVersionData` in the Byron genesis file, as the reference reads them: the
  * parameters in `ProtocolParameters` (`Cardano/Chain/Update/ProtocolParameters.hs`, `FromJSON`
  * instance, lines 144-160), the three thresholds of `softforkRule` (`Update/SoftforkRule.hs`,
  * lines 90-95) and the two numbers of `txFeePolicy` (`Common/TxFeePolicy.hs`, lines 118 and 123);
  * cardano-ledger `f649f975`, compared with the released `cardano-ledger-byron` 1.3.0.0. `json` is
  * the wire key and is separate from the text an error shows for a place
  * ([[xyz.kd5ujc.scarab.ledger.DecodeField]]), so rewording one never changes what a genesis file
  * must say.
  */
enum ByronGenesisKey(val json: String):
  case ScriptVersion extends ByronGenesisKey("scriptVersion")
  case SlotDuration extends ByronGenesisKey("slotDuration")
  case MaxBlockSize extends ByronGenesisKey("maxBlockSize")
  case MaxHeaderSize extends ByronGenesisKey("maxHeaderSize")
  case MaxTxSize extends ByronGenesisKey("maxTxSize")
  case MaxProposalSize extends ByronGenesisKey("maxProposalSize")
  case MpcThd extends ByronGenesisKey("mpcThd")
  case HeavyDelThd extends ByronGenesisKey("heavyDelThd")
  case UpdateVoteThd extends ByronGenesisKey("updateVoteThd")
  case UpdateProposalThd extends ByronGenesisKey("updateProposalThd")
  case UpdateImplicit extends ByronGenesisKey("updateImplicit")
  case SoftforkRule extends ByronGenesisKey("softforkRule")
  case TxFeePolicy extends ByronGenesisKey("txFeePolicy")
  case UnlockStakeEpoch extends ByronGenesisKey("unlockStakeEpoch")

  // inside softforkRule
  case InitThd extends ByronGenesisKey("initThd")
  case MinThd extends ByronGenesisKey("minThd")
  case ThdDecrement extends ByronGenesisKey("thdDecrement")

  // inside txFeePolicy
  case Summand extends ByronGenesisKey("summand")
  case Multiplier extends ByronGenesisKey("multiplier")

object ByronGenesisKey:
  /** The key as the genesis file spells it, which is how an error names it. */
  given Show[ByronGenesisKey] = Show.show(_.json)
