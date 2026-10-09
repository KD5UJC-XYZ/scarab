// SPDX-License-Identifier: Apache-2.0
package lab.submission

/** Closed, explicit admission policies. A profile is fixed for one owner lifetime. */
enum AdmissionProfile(val id: String):
  case AdaVkey extends AdmissionProfile("isolated-conway-pv9-ada-vkey-v1")
  case NativeScript extends AdmissionProfile("isolated-conway-pv9-ada-native-v1")

object AdmissionProfile:
  def fromId(id: String): Option[AdmissionProfile] = values.find(_.id == id)
