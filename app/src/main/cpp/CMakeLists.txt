cmake_minimum_required(VERSION 3.22.1)
project("vellora_engine")

add_library(vellora_engine SHARED
    native-lib.cpp)

find_library(log-lib log)

target_link_libraries(vellora_engine
    ${log-lib})
