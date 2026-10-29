# Native code: RNNoise noise reduction for calls (rnnoise/README.vaultchat.md).
cmake_minimum_required(VERSION 3.22.1)
project(vaultchat_rnnoise C)

add_library(vaultchat_rnnoise SHARED
    rnnoise_jni.c
    rnnoise/src/denoise.c
    rnnoise/src/rnn.c
    rnnoise/src/rnn_data.c
    rnnoise/src/celt_lpc.c
    rnnoise/src/kiss_fft.c
    rnnoise/src/pitch.c)
target_include_directories(vaultchat_rnnoise PRIVATE rnnoise/include rnnoise/src)
target_compile_options(vaultchat_rnnoise PRIVATE -O2 -fvisibility=hidden)
target_link_libraries(vaultchat_rnnoise m)
