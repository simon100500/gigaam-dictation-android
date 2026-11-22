//! The text steps a dictation goes through after transcription, in the order
//! Hex's desktop `prepare_transcript` uses: spoken formatting, spoken symbols,
//! filler words, then text replacements.

use serde::Deserialize;

use crate::dictation::{
    filler_words, spoken_formatting, spoken_symbols,
    text_replacements::{ReplacementSet, TextReplacement},
};

/// Which steps run. The app sends this as JSON; a missing field is off.
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default)]
pub struct TextSteps {
    pub spoken_formatting: bool,
    pub spoken_symbols: bool,
    pub filler_words: bool,
    /// ISO code of the dictation. Filler words apply only to "en".
    pub language: String,
    pub replacements: Vec<TextReplacement>,
}

pub fn process(transcript: &str, steps: &TextSteps) -> String {
    let mut text = transcript.to_owned();
    if steps.spoken_formatting {
        text = spoken_formatting::apply(&text);
    }
    if steps.spoken_symbols {
        text = spoken_symbols::apply(&text);
    }
    if let Some(stripped) = filler_words::strip_if(&text, &steps.language, steps.filler_words) {
        text = stripped;
    }
    if !steps.replacements.is_empty() {
        text = ReplacementSet::new(&steps.replacements).replace(&text);
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;

    fn all_on() -> TextSteps {
        TextSteps {
            spoken_formatting: true,
            spoken_symbols: true,
            filler_words: true,
            language: "en".into(),
            replacements: vec![TextReplacement {
                matched_phrase: "hex droid".into(),
                output: "HexDroid".into(),
            }],
        }
    }

    #[test]
    fn off_by_default_leaves_text_alone() {
        let text = "um, new line hashtag hex droid";
        assert_eq!(process(text, &TextSteps::default()), text);
    }

    #[test]
    fn runs_every_step() {
        let out = process("Um, I like hex droid new line thanks", &all_on());
        assert!(!out.to_lowercase().contains("um"), "{out}");
        assert!(out.contains("HexDroid"), "{out}");
        assert!(out.contains('\n'), "{out}");
    }

    #[test]
    fn fillers_stay_outside_english() {
        let steps = TextSteps { language: "de".into(), ..all_on() };
        assert!(process("um das", &steps).contains("um"));
    }
}
