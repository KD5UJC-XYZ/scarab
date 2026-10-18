// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

/** Released node-to-client versions supported by cardano-node 11.1.3. The Shelley versions are
  * eight lower (`Shelley/Ledger/NetworkProtocolVersion.hs:58-68`, ouroboros-consensus
  * 82ecba329d7d054340bf707d44fe6e9ac27cec40).
  */
enum NodeToClientVersion(val number: Int):
  case V16 extends NodeToClientVersion(16)
  case V17 extends NodeToClientVersion(17)
  case V18 extends NodeToClientVersion(18)
  case V19 extends NodeToClientVersion(19)
  case V20 extends NodeToClientVersion(20)
  case V21 extends NodeToClientVersion(21)
  case V22 extends NodeToClientVersion(22)
  case V23 extends NodeToClientVersion(23)

  def legacyPParams: Boolean = number < 21

object NodeToClientVersion:
  val latest: NodeToClientVersion = V23

  def fromNumber(number: Int): Option[NodeToClientVersion] = values.find(_.number == number)
