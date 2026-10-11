LibRaw 0.21.3 (https://www.libraw.org), unmodified except that src/integration (RawSpeed / DNG SDK glue) is left out.
Dual licensed LGPL 2.1 / CDDL 1.0 (see LICENSE.LGPL, LICENSE.CDDL, COPYRIGHT).
FeldKit builds it into its own shared library, libfeldraw.so (see ../../CMakeLists.txt), separate from the rest of the app, so it can be replaced by a rebuilt one.
