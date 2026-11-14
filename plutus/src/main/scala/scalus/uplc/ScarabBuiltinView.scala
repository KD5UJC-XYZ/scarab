// SPDX-License-Identifier: Apache-2.0
package scalus.uplc

/** What a partly applied builtin has taken, as the native diagnostic discharge needs it. Scalus
  * 1.4.0 keeps the argument kinds package-private (`Builtin.scala:32-40`): `true` for a term
  * argument, `false` for a `force`, in order.
  */
object ScarabBuiltinView:
  def argKinds(runtime: BuiltinRuntime): Vector[Boolean] = runtime.argKinds.toVector
