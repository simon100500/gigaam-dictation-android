//! Android link fix-ups for transcribe.cpp's static archives.
//!
//! transcribe-cpp-sys copies its system libraries from the CMake link
//! manifest, which names the desktop Linux set (`stdc++ m pthread dl`). On
//! Android, pthread is part of bionic's libc and has no library of its own,
//! and the archives were compiled against the NDK's libc++ (`std::__ndk1`).
//! So this script links libc++ statically and gives the linker an empty
//! `libpthread.a` to satisfy `-lpthread`.

use std::{env, fs, path::PathBuf};

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("android") {
        return;
    }
    let stubs = PathBuf::from(env::var("OUT_DIR").unwrap()).join("stubs");
    fs::create_dir_all(&stubs).unwrap();
    // An ar archive with no members.
    fs::write(stubs.join("libpthread.a"), b"!<arch>\n").unwrap();
    println!("cargo:rustc-link-search=native={}", stubs.display());
    // Link arguments, not `rustc-link-lib`: clang finds these in the NDK
    // sysroot, which rustc's own static-library check doesn't search.
    println!("cargo:rustc-link-arg=-lc++_static");
    println!("cargo:rustc-link-arg=-lc++abi");
    // Keep the static libc++ private to this library, and align segments for
    // phones with 16 KB pages (NDK r28+ does this by default; older ones don't).
    println!("cargo:rustc-link-arg=-Wl,--exclude-libs,ALL");
    println!("cargo:rustc-link-arg=-Wl,-z,max-page-size=16384");
}
