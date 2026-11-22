//! One loaded GGUF model and the runs made with it. Like Hex's desktop
//! `gguf_session`, each run opens a fresh session, so scratch memory sized for
//! one dictation is released before the next.

use std::time::Instant;

use serde::{Deserialize, Serialize};
use transcribe_cpp::{
    Feature, Model, ParakeetVocabularyOptions, RunExtension, RunOptions, SessionOptions,
};

/// Hex's limit on terms sent to Parakeet's vocabulary rescoring.
const MAX_BIASED_TERMS: usize = 64;

/// One vocabulary term and the other spellings the transcript may show for it.
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default)]
pub struct VocabularyTerm {
    pub term: String,
    pub sounds_like: Vec<String>,
}

/// The settings of one run. The app sends this as JSON.
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(default)]
pub struct RunSettings {
    pub vocabulary: Vec<VocabularyTerm>,
    /// Hex's boost levels in nats: 2.0 light, 2.5 normal, 4.0 strong.
    /// `None` uses the engine default; 0 turns rescoring off.
    pub boost: Option<f32>,
    /// ISO language hint, or `None` for the model's own choice.
    pub language: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct RunReport {
    pub text: String,
    pub audio_ms: u64,
    pub inference_ms: u64,
}

pub struct Engine {
    model: Model,
    threads: i32,
}

impl Engine {
    pub fn load(path: &str, threads: i32) -> Result<Self, String> {
        let model = Model::load(path).map_err(|e| format!("load {path}: {e}"))?;
        Ok(Self { model, threads })
    }

    pub fn describe(&self) -> String {
        format!(
            "transcribe.cpp {} model={}/{} threads={} vocabulary={}",
            transcribe_cpp::version(),
            self.model.arch(),
            self.model.variant(),
            self.threads,
            self.biases_vocabulary()
        )
    }

    /// Transcribes 16 kHz mono PCM in [-1, 1].
    pub fn transcribe(&self, pcm: &[f32], settings: &RunSettings) -> Result<RunReport, String> {
        let started = Instant::now();
        let mut session = self
            .model
            .session_with(&SessionOptions { n_threads: self.threads, ..Default::default() })
            .map_err(|e| format!("session: {e}"))?;
        let options = self.run_options(settings);
        let transcript = session.run(pcm, &options).map_err(|e| format!("run: {e}"))?;
        Ok(RunReport {
            text: transcript.text.trim().to_owned(),
            audio_ms: (pcm.len() as u64 * 1000) / 16_000,
            inference_ms: started.elapsed().as_millis() as u64,
        })
    }

    /// Only Parakeet takes the rescoring extension built below.
    fn biases_vocabulary(&self) -> bool {
        self.model.arch() == "parakeet" && self.model.supports(Feature::Vocabulary)
    }

    fn run_options(&self, settings: &RunSettings) -> RunOptions {
        let mut options = RunOptions { language: settings.language.clone(), ..Default::default() };
        let terms: Vec<&VocabularyTerm> = settings
            .vocabulary
            .iter()
            .filter(|t| !t.term.trim().is_empty())
            .take(MAX_BIASED_TERMS)
            .collect();
        if terms.is_empty() || settings.boost == Some(0.0) || !self.biases_vocabulary() {
            return options;
        }
        options.vocabulary = terms.iter().map(|t| t.term.trim().to_owned()).collect();
        let sounds_like = terms
            .iter()
            .enumerate()
            .flat_map(|(i, t)| {
                t.sounds_like
                    .iter()
                    .filter(|s| !s.trim().is_empty())
                    .map(move |s| (i, s.trim().to_owned()))
            })
            .collect();
        options.family = Some(RunExtension::ParakeetVocabulary(ParakeetVocabularyOptions {
            boost: settings.boost,
            sounds_like,
        }));
        options
    }
}
