cmake_minimum_required(VERSION 3.22.1)
project(feldnative C CXX)

add_library(feldnative SHARED feldhash.cpp)
target_include_directories(feldnative PRIVATE third_party/xxhash)
target_compile_options(feldnative PRIVATE -O3 -fvisibility=hidden)
find_library(log-lib log)
target_link_libraries(feldnative ${log-lib})

# RAW development (LibRaw, LGPL 2.1 / CDDL 1.0) in a library of its own so that it can be replaced by a rebuilt one.
file(GLOB_RECURSE LIBRAW_SRC third_party/libraw/src/*.cpp)
# the *_ph.cpp files are stand-ins for a build without post-processing; we want the real ones
list(FILTER LIBRAW_SRC EXCLUDE REGEX "_ph\\.cpp$")
add_library(feldraw SHARED feldraw.cpp ${LIBRAW_SRC})
target_include_directories(feldraw PRIVATE third_party/libraw)
target_compile_definitions(feldraw PRIVATE LIBRAW_NOTHREADS USE_ZLIB)
target_compile_options(feldraw PRIVATE -O2 -fvisibility=hidden -w)
target_link_libraries(feldraw z jnigraphics ${log-lib})
