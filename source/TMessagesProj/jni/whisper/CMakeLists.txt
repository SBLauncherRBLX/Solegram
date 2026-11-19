# Solegram: Whisper (whisper.cpp v1.7.6, MIT, see LICENSE) for transcribing voice messages in any language, on the
# phone. Only the CPU backend is built. The model is not bundled: the app downloads it on request and checks its hash.
set(SGW ${CMAKE_CURRENT_SOURCE_DIR})

if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_library(sgwhisper SHARED
        ${SGW}/sg_whisper_jni.cpp
        ${SGW}/src/whisper.cpp
        ${SGW}/ggml/src/ggml.c
        ${SGW}/ggml/src/ggml.cpp
        ${SGW}/ggml/src/ggml-alloc.c
        ${SGW}/ggml/src/ggml-backend.cpp
        ${SGW}/ggml/src/ggml-backend-reg.cpp
        ${SGW}/ggml/src/ggml-opt.cpp
        ${SGW}/ggml/src/ggml-quants.c
        ${SGW}/ggml/src/ggml-threading.cpp
        ${SGW}/ggml/src/gguf.cpp
        ${SGW}/ggml/src/ggml-cpu/ggml-cpu.c
        ${SGW}/ggml/src/ggml-cpu/ggml-cpu.cpp
        ${SGW}/ggml/src/ggml-cpu/binary-ops.cpp
        ${SGW}/ggml/src/ggml-cpu/unary-ops.cpp
        ${SGW}/ggml/src/ggml-cpu/ops.cpp
        ${SGW}/ggml/src/ggml-cpu/vec.cpp
        ${SGW}/ggml/src/ggml-cpu/traits.cpp
        ${SGW}/ggml/src/ggml-cpu/quants.c
        ${SGW}/ggml/src/ggml-cpu/repack.cpp
        ${SGW}/ggml/src/ggml-cpu/arch/arm/quants.c
        ${SGW}/ggml/src/ggml-cpu/arch/arm/repack.cpp
        ${SGW}/ggml/src/ggml-cpu/llamafile/sgemm.cpp
    )
    target_include_directories(sgwhisper PRIVATE ${SGW}/include ${SGW}/ggml/include ${SGW}/ggml/src ${SGW}/ggml/src/ggml-cpu)
    target_compile_definitions(sgwhisper PRIVATE GGML_USE_CPU GGML_USE_CPU_REPACK GGML_USE_LLAMAFILE _GNU_SOURCE
        GGML_VERSION="0.0.0" GGML_COMMIT="whisper-1.7.6" WHISPER_VERSION="1.7.6")
    target_compile_options(sgwhisper PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections -w)
    set_target_properties(sgwhisper PROPERTIES C_STANDARD 11 CXX_STANDARD 17)
    target_link_options(sgwhisper PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
    target_link_libraries(sgwhisper log android m)
else()
    # other ABIs: a stub, the app sees Whisper as unavailable
    add_library(sgwhisper SHARED ${SGW}/sg_whisper_jni.cpp)
    target_compile_definitions(sgwhisper PRIVATE SG_NO_WHISPER)
    target_link_libraries(sgwhisper log)
endif()
