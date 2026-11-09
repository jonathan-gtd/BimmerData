# BimmerData R8 rules.
#
# Shrinking and optimization are on, obfuscation is off: the app is open source, so renaming
# brings nothing, and it would break what relies on names:
# - enum names stored in the settings (LiveMode, export mode... read back with valueOf),
# - exception class names shown in error messages (e.javaClass.simpleName),
# - readable stack traces in bug reports.
-dontobfuscate

# BluetoothDevice.createRfcommSocket(int) is called by reflection (hidden framework API, fallback
# for adapters that refuse the SDP lookup); it is a framework method, R8 never touches it.

# The EDIABAS interpreter (:ediabas) is kept exactly as compiled: it is the part checked against
# real Tool32 traces (golden tests on the JVM), so the release must run that very code.
-keep class io.github.jonathangtd.bimmerdata.ediabas.** { *; }
