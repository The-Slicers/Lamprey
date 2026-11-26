# Compile-only stubs

These are stand-ins for a few classes from the Quorum runtime, with only the
names and public fields that Lamprey's Java plugins use (BitArrayFill reaches
into the standard library's 32/64-bit arrays). `build.sh` / `build.bat`
compile against them but do **not** put them in `lamprey-plugins.jar`: when
Lamprey runs, the real classes from the Quorum runtime are used instead.

If a Quorum release ever renames those classes or fields, update the stubs to
match (they mirror `quorum.Libraries.Containers.*BitArray`, its `plugin_`
field, and `plugins.quorum.Libraries.Containers.*BitArray`'s
`ints` / `floats` / `doubles`).
