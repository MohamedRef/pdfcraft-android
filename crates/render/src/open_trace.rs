//! Bounded, opt-in open diagnostics. Stages are code constants, never PDF data.

use std::sync::{Arc, Mutex, PoisonError};

/// One completed stage. Nested stages and the two parser threads overlap; do not add them.
#[derive(Clone, Debug)]
pub struct OpenStage {
    pub name: &'static str,
    pub duration_ms: u64,
}

/// A trace belongs to one open request; clones share only that request's bounded recording.
/// The default does not read the clock, allocate a buffer or record anything.
#[derive(Clone, Default)]
pub struct OpenTrace(Option<Arc<Mutex<Vec<OpenStage>>>>);

impl OpenTrace {
    pub fn recording() -> Self {
        Self(Some(Arc::new(Mutex::new(Vec::new()))))
    }

    pub fn measure<T>(&self, name: &'static str, work: impl FnOnce() -> T) -> T {
        let Some(_) = &self.0 else { return work() };
        #[cfg(not(target_arch = "wasm32"))]
        let started = std::time::Instant::now();
        let result = work();
        #[cfg(not(target_arch = "wasm32"))]
        let duration_ms = u64::try_from(started.elapsed().as_millis()).unwrap_or(u64::MAX);
        // std::time::Instant panics on wasm32. Preserve the existing web open path.
        #[cfg(target_arch = "wasm32")]
        let duration_ms = 0;
        self.record(name, duration_ms);
        result
    }

    /// A fixed diagnostic event, such as a conservative fallback reason, not measured work.
    pub fn event(&self, name: &'static str) {
        self.record(name, 0);
    }

    fn record(&self, name: &'static str, duration_ms: u64) {
        let Some(stages) = &self.0 else { return };
        let mut stages = stages.lock().unwrap_or_else(PoisonError::into_inner);
        if stages.len() < 64 {
            stages.push(OpenStage { name, duration_ms });
        }
    }

    pub fn snapshot(&self) -> Vec<OpenStage> {
        self.0.as_ref().map(|s| s.lock().unwrap_or_else(PoisonError::into_inner).clone()).unwrap_or_default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn opt_in_is_request_local_bounded_and_collects_both_threads() {
        let silent = OpenTrace::default();
        assert_eq!(silent.measure("silent", || 7), 7);
        assert!(silent.snapshot().is_empty());
        let trace = OpenTrace::recording();
        std::thread::scope(|scope| {
            scope.spawn(|| trace.measure("cos", || 1));
            trace.measure("inspect", || 2);
        });
        let stages = trace.snapshot();
        assert_eq!(stages.len(), 2);
        assert!(stages.iter().any(|s| s.name == "cos"));
        assert!(stages.iter().any(|s| s.name == "inspect"));
        for _ in 0..100 {
            trace.measure("bounded", || ());
        }
        assert_eq!(trace.snapshot().len(), 64);
        assert!(OpenTrace::recording().snapshot().is_empty());
    }

    #[test]
    fn diagnostic_events_are_opt_in_and_share_the_stage_budget() {
        let quiet = OpenTrace::default();
        quiet.event("native_reading_fallback_catalog");
        assert!(quiet.snapshot().is_empty());
        let trace = OpenTrace::recording();
        trace.event("native_reading_fallback_catalog");
        assert_eq!(trace.snapshot()[0].duration_ms, 0);
        for _ in 0..100 {
            trace.event("native_reading_fallback_catalog");
        }
        assert_eq!(trace.snapshot().len(), 64);
    }
}
