# Add project specific ProGuard rules here.

-keepattributes *Annotation*, InnerClasses, Signature

# ---------------------------------------------------------------------------------------------
# Reflection notes
# ---------------------------------------------------------------------------------------------
# The only Java reflection in this app targets *Android framework* classes:
#   - BluetoothDevice.createRfcommSocket(int) / isConnected()   (data/obd/BluetoothClassicTransport)
#   - BluetoothSocket.mSocket                                   (data/obd/BluetoothClassicTransport)
# R8 never shrinks or obfuscates platform classes, so those reflective lookups keep working and
# need no keep rule. They are, however, hidden APIs — see the lint "DiscouragedPrivateApi" notes.
#
# `isMinifyEnabled` is currently false, so R8 shrinking is not run at all. The rules below make a
# future `isMinifyEnabled = true` safe; enable it only after re-testing the OBD connect path on a
# real dongle and the Routes/Places calls on a device.

# ---------------------------------------------------------------------------------------------
# kotlinx.serialization (DTOs are (de)serialized reflectively via generated serializers)
# ---------------------------------------------------------------------------------------------
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class com.fuelroute.** {
    *** Companion;
}
-keepclasseswithmembers class com.fuelroute.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.fuelroute.**$$serializer { *; }

# ---------------------------------------------------------------------------------------------
# Retrofit service interfaces (resolved reflectively at runtime; annotations carry the HTTP info)
# ---------------------------------------------------------------------------------------------
-keep interface com.fuelroute.data.routes.RoutesService { *; }
-keep interface com.fuelroute.data.places.PlacesService { *; }
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ---------------------------------------------------------------------------------------------
# Hilt @EntryPoint interfaces (looked up by EntryPointAccessors.fromApplication(...))
# ---------------------------------------------------------------------------------------------
-keep interface com.fuelroute.**.*EntryPoint { *; }
