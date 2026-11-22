//! Hex's speech engine and dictation text steps for the Android app.
//!
//! The JNI surface matches `app.hexforandroid.HexCore` in the app. Each
//! function throws `java.lang.RuntimeException` on failure instead of
//! returning a sentinel, and a Rust panic becomes that exception too.

mod dictation;
pub mod engine;
pub mod text;

use std::panic::{AssertUnwindSafe, catch_unwind};

use jni::JNIEnv;
use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jint, jlong, jstring};

use crate::engine::{Engine, RunSettings};
use crate::text::TextSteps;

/// Runs `work`, turning an error or a panic into a Java exception and
/// `fallback` into the return value.
fn guarded<'l, T>(
    env: &mut JNIEnv<'l>,
    fallback: T,
    work: impl FnOnce(&mut JNIEnv<'l>) -> Result<T, String>,
) -> T {
    let outcome = catch_unwind(AssertUnwindSafe(|| work(env)));
    let message = match outcome {
        Ok(Ok(value)) => return value,
        Ok(Err(message)) => message,
        Err(panic) => panic
            .downcast_ref::<&str>()
            .map(|s| (*s).to_owned())
            .or_else(|| panic.downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "native panic".to_owned()),
    };
    // A pending exception from a failed JNI call wins over ours.
    if !env.exception_check().unwrap_or(true) {
        let _ = env.throw_new("java/lang/RuntimeException", message);
    }
    fallback
}

fn string(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value).map(Into::into).map_err(|e| e.to_string())
}

fn java_string(env: &mut JNIEnv, value: &str) -> Result<jstring, String> {
    env.new_string(value).map(|s| s.into_raw()).map_err(|e| e.to_string())
}

/// # Safety
/// `handle` must come from `nativeLoad` and not yet be freed.
unsafe fn engine<'a>(handle: jlong) -> Result<&'a Engine, String> {
    if handle == 0 {
        return Err("the model is not loaded".to_owned());
    }
    Ok(unsafe { &*(handle as *const Engine) })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_hexforandroid_HexCore_nativeLoad<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    path: JString<'l>,
    threads: jint,
) -> jlong {
    guarded(&mut env, 0, |env| {
        let path = string(env, &path)?;
        let engine = Engine::load(&path, threads)?;
        Ok(Box::into_raw(Box::new(engine)) as jlong)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_hexforandroid_HexCore_nativeFree<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle != 0 {
        // A panic while dropping must not cross into Java.
        let _ = catch_unwind(AssertUnwindSafe(|| drop(unsafe { Box::from_raw(handle as *mut Engine) })));
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_hexforandroid_HexCore_nativeDescribe<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jstring {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let description = unsafe { engine(handle) }?.describe();
        java_string(env, &description)
    })
}

/// Returns the run report as JSON: `{"text", "audio_ms", "inference_ms"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_app_hexforandroid_HexCore_nativeTranscribe<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    pcm: JFloatArray<'l>,
    settings_json: JString<'l>,
) -> jstring {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let engine = unsafe { engine(handle) }?;
        let settings: RunSettings =
            serde_json::from_str(&string(env, &settings_json)?).map_err(|e| e.to_string())?;
        let length = env.get_array_length(&pcm).map_err(|e| e.to_string())?;
        let mut samples = vec![0f32; length as usize];
        env.get_float_array_region(&pcm, 0, &mut samples).map_err(|e| e.to_string())?;
        let report = engine.transcribe(&samples, &settings)?;
        java_string(env, &serde_json::to_string(&report).map_err(|e| e.to_string())?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_app_hexforandroid_HexCore_nativeProcess<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    transcript: JString<'l>,
    steps_json: JString<'l>,
) -> jstring {
    guarded(&mut env, std::ptr::null_mut(), |env| {
        let transcript = string(env, &transcript)?;
        let steps: TextSteps =
            serde_json::from_str(&string(env, &steps_json)?).map_err(|e| e.to_string())?;
        java_string(env, &text::process(&transcript, &steps))
    })
}
