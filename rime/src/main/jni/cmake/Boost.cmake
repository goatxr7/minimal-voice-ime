# 使用预置的 Boost 头文件（1.74），只提供头文件能力。
# librime 里唯一需要编译库的 boost::regex 已改为 std::regex，
# 因此这里不需要再下载或编译 boost 源码。
set(BOOST_INCLUDE_DIR "/tmp/boostroot")

add_library(boost_headers INTERFACE)
target_include_directories(boost_headers SYSTEM INTERFACE ${BOOST_INCLUDE_DIR})
include_directories(SYSTEM ${BOOST_INCLUDE_DIR})
add_compile_definitions(_LIBCPP_ENABLE_CXX17_REMOVED_UNARY_BINARY_FUNCTION)

set(BOOST_DEPS "")
