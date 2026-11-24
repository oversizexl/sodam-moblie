#!/usr/bin/env bash
# 交叉编译 sodam-ffi 到 Android 并生成 Kotlin(JNA) 绑定。
#
# 用法: scripts/android-ffi.sh [arm64-v8a|x86_64] [debug|release]
#
# 注意：本机的 cargo/rustc 默认是 Homebrew 版，不带 Android std；
# 这里把 rustup 的 stable 工具链放到 PATH 最前，否则 cargo-ndk 内层会用到没有
# aarch64-linux-android std 的 rustc，报 “can't find crate for `core`”。
set -euo pipefail

target="${1:-arm64-v8a}"
profile="${2:-release}"

sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# 优先用显式指定；否则挑最新一个**完整**的 NDK（有 source.properties，
# 有些残留目录只建了空壳，cargo-ndk 探测版本会失败）。
ndk="${ANDROID_NDK_HOME:-}"
if [ -z "$ndk" ] || [ ! -f "$ndk/source.properties" ]; then
  ndk=""
  for d in $(ls -d "$sdk"/ndk/* 2>/dev/null | sort -Vr); do
    if [ -f "$d/source.properties" ]; then
      ndk="$d"
      break
    fi
  done
fi
if [ -z "$ndk" ] || [ ! -d "$ndk" ]; then
  echo "找不到可用的 NDK：设置 ANDROID_NDK_HOME，或用 SDK Manager 安装" >&2
  exit 1
fi

# 本机的 cargo/rustc 可能是 Homebrew 版，不带 Android std；macOS 下把 rustup 的
# stable 工具链放到 PATH 最前，否则 cargo-ndk 内层会用到没有 aarch64-linux-android
# std 的 rustc，报 “can't find crate for `core`”。Linux/CI 上由 rustup 默认工具链提供。
tc="$HOME/.rustup/toolchains/stable-aarch64-apple-darwin/bin"
if [ -x "$tc/rustc" ]; then
  export PATH="$tc:$HOME/.cargo/bin:$PATH"
  export RUSTC="$tc/rustc"
fi
export ANDROID_HOME="$sdk" ANDROID_NDK_HOME="$ndk" ANDROID_NDK_ROOT="$ndk"

echo "NDK:   $ndk"
echo "rustc: $(rustc --version)"

cargo_args=(--lib -p sodam-ffi)
[ "$profile" = release ] && cargo_args+=(--release)
cargo ndk -t "$target" build "${cargo_args[@]}"

# cargo-ndk 用 ABI 名构建，但产物落在 Rust triple 目录。
case "$target" in
  arm64-v8a) triple=aarch64-linux-android ;;
  armeabi-v7a) triple=armv7-linux-androideabi ;;
  x86) triple=i686-linux-android ;;
  x86_64) triple=x86_64-linux-android ;;
  *) triple="$target" ;;
esac
so="target/$triple/$profile/libsodam_ffi.so"
echo "so:    $so"

bindings="target/android/kotlin"
mkdir -p "$bindings"
cargo run -q -p sodam-ffi --features cli --bin uniffi-bindgen -- \
  generate --library "$so" --language kotlin --out-dir "$bindings"
kt="$bindings/uniffi/sodam_ffi/sodam_ffi.kt"
echo "kotlin: $kt"

# 存在 Android 工程时，直接铺进 app 源码树（这两个路径已在 android/.gitignore 里）。
app_main="android/app/src/main"
if [ -d "$app_main" ]; then
  mkdir -p "$app_main/jniLibs/$target" "$app_main/java/uniffi/sodam_ffi"
  cp "$so" "$app_main/jniLibs/$target/libsodam_ffi.so"
  cp "$kt" "$app_main/java/uniffi/sodam_ffi/sodam_ffi.kt"
  echo "installed -> $app_main/jniLibs/$target/ + $app_main/java/uniffi/sodam_ffi/"
fi
