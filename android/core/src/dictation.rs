//! Hex's deterministic dictation text steps, compiled from the desktop source
//! in `src/dictation/`. The Android app follows upstream changes to these
//! files without a copy. Each file depends only on `regex`, `serde` and its
//! siblings here.

#[path = "../../../src/dictation/filler_words.rs"]
pub mod filler_words;
#[path = "../../../src/dictation/spoken_formatting.rs"]
pub mod spoken_formatting;
#[path = "../../../src/dictation/spoken_symbols.rs"]
pub mod spoken_symbols;
#[path = "../../../src/dictation/text_replacements.rs"]
pub mod text_replacements;
