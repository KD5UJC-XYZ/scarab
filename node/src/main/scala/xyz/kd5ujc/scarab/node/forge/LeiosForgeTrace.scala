// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.EbPoint

/** The Leios lines of the forger, the cases of `TraceLeiosKernel` the reference's forge loop and
  * `forgeShelleyBlock` trace (ouroboros-consensus 2416493b
  * `ouroboros-consensus/src/ouroboros-consensus/LeiosDemoTypes.hs:1621-1720`), with their `kind` s
  * (`:1980-2140`).
  */
enum LeiosForgeTrace:
  /** The closure of the endorser block the parent announced is not in the store
    * (`MkTraceLeiosKernel "EB not yet downloaded: …"`, `NodeKernel/Forge.hs:356-360`).
    */
  case NotDownloaded(point: EbPoint)

  /** The closure is held but no certificate was assembled for the parent (`MkTraceLeiosKernel "EB
    * downloaded but no certificate: …"`, `NodeKernel/Forge.hs:373-377`).
    */
  case NoCertificate(point: EbPoint)

  /** The block forged in `atSlot` certifies the endorser block at `point`
    * (`TraceLeiosBlockCertified`, `NodeKernel/Forge.hs:379-383`).
    */
  case BlockCertified(atSlot: BigInt, point: EbPoint)

  /** An endorser block forged in `slot` (`TraceLeiosBlockForged`, `Shelley/Ledger/Forge.hs:202-208`
    * of ouroboros-consensus-cardano): its transactions, its encoded size and its closure's bytes;
    * the mempool's rest the reference writes as zero (its `FIXME`) is written so here.
    */
  case BlockForged(slot: BigInt, point: EbPoint, txs: Int, ebSize: Long, closureSize: Long)

  /** The ranking block `rbHash` announces the endorser block at `point`
    * (`TraceLeiosBlockAnnounced`, `Shelley/Ledger/Forge.hs:130-134`).
    */
  case BlockAnnounced(rbHash: Bytes, point: EbPoint)

  /** The ranking block `rbHash`, forged in `atSlot`, both certifies an endorser block and announces
    * one (`TraceLeiosCertifiedAndAnnounced`, `Shelley/Ledger/Forge.hs:135-137`).
    */
  case CertifiedAndAnnounced(atSlot: BigInt, rbHash: Bytes)
