# Builds airspy.dll with MSVC. Used by native/build-windows.ps1.
#
# Upstream expects a prebuilt pthreads-win32 DLL on Windows. This project builds
# pthreads4w as a static library instead and links it, along with the static C
# runtime, into airspy.dll, so that libusb-1.0.dll is its only dependency.
# Streaming on Windows is only covered by the hardware tests, so run them on a
# Windows machine after changing anything here.
cmake_minimum_required(VERSION 3.20)
project(jairspy_windows C)

set(AIRSPY_SOURCE_DIR "" CACHE PATH "airspyone_host source tree")
set(PTHREADS4W_SOURCE_DIR "" CACHE PATH "pthreads4w source tree")
if(NOT EXISTS "${AIRSPY_SOURCE_DIR}/libairspy/CMakeLists.txt")
    message(FATAL_ERROR "AIRSPY_SOURCE_DIR does not contain libairspy: ${AIRSPY_SOURCE_DIR}")
endif()
if(NOT EXISTS "${PTHREADS4W_SOURCE_DIR}/pthread.c")
    message(FATAL_ERROR "PTHREADS4W_SOURCE_DIR does not contain pthread.c: ${PTHREADS4W_SOURCE_DIR}")
endif()

# pthread.c includes every other source file, so it is the whole library.
#
# It is compiled in pthreads4w's DLL mode, without __PTW32_STATIC_LIB, even
# though it is linked as a static library: that makes its DllMain the DllMain of
# airspy.dll, which initializes pthreads4w when the DLL loads and cleans up
# after each thread. In static-library mode, initialization relies on a
# .CRT$XCU constructor that does not run inside airspy.dll; without it the
# thread-local key that pthread_cond_wait uses for its cleanup handler does not
# exist, so a condition variable stays locked after its first wake-up. That
# stalls streaming after the first sample block and hangs airspy_stop_rx and
# airspy_close. libairspy has no DllMain of its own. The side effect is that
# airspy.dll also exports the pthread functions.
add_library(pthreads4w STATIC "${PTHREADS4W_SOURCE_DIR}/pthread.c")
target_include_directories(pthreads4w PUBLIC "${PTHREADS4W_SOURCE_DIR}")
# PUBLIC definitions reach libairspy through target_link_libraries.
target_compile_definitions(pthreads4w
    PUBLIC __PTW32_CLEANUP_C HAVE_STRUCT_TIMESPEC
    PRIVATE HAVE_CONFIG_H __PTW32_BUILD)

# Satisfy upstream's FindThreads.cmake with the target built above.
set(THREADS_PTHREADS_INCLUDE_DIR "${PTHREADS4W_SOURCE_DIR}" CACHE PATH "pthreads include directory" FORCE)
set(THREADS_PTHREADS_WIN32_LIBRARY pthreads4w CACHE STRING "pthreads library" FORCE)

add_subdirectory("${AIRSPY_SOURCE_DIR}/libairspy" libairspy)
