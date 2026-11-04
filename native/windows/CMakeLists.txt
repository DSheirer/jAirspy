# Builds airspy.dll with MSVC. Used by native/build-windows.ps1.
#
# Upstream expects a prebuilt pthreads-win32 DLL on Windows. This project builds
# pthreads4w as a static library instead and links it, along with the static C
# runtime, into airspy.dll, so that libusb-1.0.dll is its only dependency.
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
add_library(pthreads4w STATIC "${PTHREADS4W_SOURCE_DIR}/pthread.c")
target_include_directories(pthreads4w PUBLIC "${PTHREADS4W_SOURCE_DIR}")
# PUBLIC definitions reach libairspy through target_link_libraries.
target_compile_definitions(pthreads4w
    PUBLIC __PTW32_STATIC_LIB __PTW32_CLEANUP_C HAVE_STRUCT_TIMESPEC
    PRIVATE HAVE_CONFIG_H __PTW32_BUILD)

# Satisfy upstream's FindThreads.cmake with the target built above.
set(THREADS_PTHREADS_INCLUDE_DIR "${PTHREADS4W_SOURCE_DIR}" CACHE PATH "pthreads include directory" FORCE)
set(THREADS_PTHREADS_WIN32_LIBRARY pthreads4w CACHE STRING "pthreads library" FORCE)

add_subdirectory("${AIRSPY_SOURCE_DIR}/libairspy" libairspy)
