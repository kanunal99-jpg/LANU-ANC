cmake_minimum_required(VERSION 3.22.1)
project(lanu_audio_native)

add_library(lanu_audio_native SHARED
    native_audio_engine.cpp
)

target_compile_features(lanu_audio_native PRIVATE cxx_std_17)

target_link_libraries(lanu_audio_native
    aaudio
    log
)
