cmake_minimum_required(VERSION 3.22)
project(microduck_android_m1 LANGUAGES C CXX)
if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
  message(FATAL_ERROR "M1 currently targets Android arm64-v8a")
endif()
get_filename_component(M1_PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(MUJOCO_SOURCE_DIR "${M1_PROJECT_ROOT}/.native-cache/mujoco-3.10.0" CACHE PATH "Pinned MuJoCo source")
find_package(Git REQUIRED)
if(NOT EXISTS "${MUJOCO_SOURCE_DIR}/CMakeLists.txt")
  get_filename_component(MUJOCO_CACHE_PARENT "${MUJOCO_SOURCE_DIR}" DIRECTORY)
  file(MAKE_DIRECTORY "${MUJOCO_CACHE_PARENT}")
  execute_process(COMMAND "${GIT_EXECUTABLE}" clone --depth 1 --branch 3.10.0
    https://github.com/google-deepmind/mujoco.git "${MUJOCO_SOURCE_DIR}"
    RESULT_VARIABLE CLONE_RESULT)
  if(NOT CLONE_RESULT EQUAL 0)
    message(FATAL_ERROR "MuJoCo download failed; restore network access and retry Gradle Sync")
  endif()
endif()
execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${MUJOCO_SOURCE_DIR}" rev-parse HEAD
  OUTPUT_VARIABLE MUJOCO_REV OUTPUT_STRIP_TRAILING_WHITESPACE RESULT_VARIABLE REV_RESULT)
if(NOT REV_RESULT EQUAL 0 OR NOT MUJOCO_REV STREQUAL "28009f9105cd92784b7b0b30c0605a5e29107a77")
  message(FATAL_ERROR "MuJoCo source revision differs from the pinned desktop 3.10.0")
endif()
# NDK 25 libc++ lacks the iterator-pair string_view constructor used by upstream.
# Explicit pointer+length preserves the same byte range. Apply only this checked-in hunk.
set(NDK_PATCH "${M1_PROJECT_ROOT}/patches/mujoco-3.10.0-ndk25.patch")
execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${MUJOCO_SOURCE_DIR}" apply --reverse --check "${NDK_PATCH}"
  RESULT_VARIABLE PATCH_PRESENT OUTPUT_QUIET ERROR_QUIET)
if(NOT PATCH_PRESENT EQUAL 0)
  execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${MUJOCO_SOURCE_DIR}" apply --check "${NDK_PATCH}"
    RESULT_VARIABLE PATCH_APPLICABLE OUTPUT_QUIET ERROR_QUIET)
  if(NOT PATCH_APPLICABLE EQUAL 0)
    message(FATAL_ERROR "The scoped NDK compatibility patch cannot be applied")
  endif()
  execute_process(COMMAND "${GIT_EXECUTABLE}" -C "${MUJOCO_SOURCE_DIR}" apply "${NDK_PATCH}"
    COMMAND_ERROR_IS_FATAL ANY)
endif()
# Use already fetched source-only dependencies if available. Missing ones are fetched
# by upstream at the revisions pinned in its MujocoDependencies.cmake.
foreach(DEP ccd lodepng marchingcubecpp miniz qhull tinyobjloader tinyxml2)
  string(TOUPPER "${DEP}" DEP_UPPER)
  set(DEP_SOURCE "${M1_PROJECT_ROOT}/.native-cache/deps/${DEP}-src")
  if(EXISTS "${DEP_SOURCE}")
    set("FETCHCONTENT_SOURCE_DIR_${DEP_UPPER}" "${DEP_SOURCE}" CACHE PATH "Cached source for ${DEP}" FORCE)
  endif()
endforeach()
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(MUJOCO_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(MUJOCO_BUILD_SIMULATE OFF CACHE BOOL "" FORCE)
set(MUJOCO_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(MUJOCO_TEST_PYTHON_UTIL OFF CACHE BOOL "" FORCE)
add_subdirectory("${MUJOCO_SOURCE_DIR}" mujoco-build EXCLUDE_FROM_ALL)
# Bionic provides localtime_r; upstream 3.10 requires an explicit POSIX feature macro.
target_compile_definitions(mujoco PRIVATE _POSIX_C_SOURCE=200809L)
# Android packages .so files; keep the bundled library SONAME unversioned.
set_target_properties(mujoco PROPERTIES VERSION "" SOVERSION "")
add_library(microduck_m1 SHARED native_sim.cpp)
target_compile_features(microduck_m1 PRIVATE cxx_std_20)
target_compile_options(microduck_m1 PRIVATE -Wall -Wextra -Werror)
target_link_options(microduck_m1 PRIVATE -Wl,-z,max-page-size=16384)
target_link_options(mujoco PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(microduck_m1 PRIVATE mujoco log)
