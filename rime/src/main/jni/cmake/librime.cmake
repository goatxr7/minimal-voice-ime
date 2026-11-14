set(LIBRIME_VERSION "1.16.1")
set(LIBRIME_URL "https://github.com/rime/librime/archive/refs/tags/${LIBRIME_VERSION}.tar.gz")

# 使用本地已修补（boost::regex -> std::regex）的源码，跳过 GitHub 下载
set(LIBRIME_SOURCE_DIR "${JNI_DEPS_DIR}/librime-1.16.1")
if(NOT EXISTS "${LIBRIME_SOURCE_DIR}/src/rime_api.cc")
    download_and_extract(
        "librime"
        ${LIBRIME_VERSION}
        ".tar.gz"
        ${LIBRIME_URL}
        "944909b5d9fb81171b044b7f2ea89922bd73457fcc5105798a259173288cd2d9"
    )
endif()

include(librime-plugins)

# Do not use add_subdirectory(${LIBRIME_SOURCE_DIR}),
# because its find_package() could not find external libraries.
include(override/librime/main)

add_to_include(librime "${LIBRIME_SOURCE_DIR}/src")
