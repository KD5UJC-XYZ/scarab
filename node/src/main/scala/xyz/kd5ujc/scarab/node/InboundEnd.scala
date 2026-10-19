// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.network.{ConnectionEnd, LocalTxSubmissionServer}

/** The listener's connection outcome. Service and unexpected failures retain their causes until
  * logging, including local-client connections that do not enter the node-to-node manager.
  */
enum InboundEnd:
  case Connection(end: ConnectionEnd)
  case Local(end: LocalTxSubmissionServer.End)
  case Defect(cause: Throwable)

  def message: String = this match
    case Connection(end) => end.message
    case Local(end)      => end.message
    case Defect(cause)   => s"failed: ${cause.getClass.getSimpleName}: ${cause.getMessage}"
