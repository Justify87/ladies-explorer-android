use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};

use crate::CoreError;

#[derive(Default, uniffi::Object)]
pub struct RequestControl {
    cancelled: AtomicBool,
}

#[uniffi::export]
impl RequestControl {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::Release);
    }
}

impl RequestControl {
    pub(crate) fn is_cancelled(&self) -> bool {
        self.cancelled.load(Ordering::Acquire)
    }

    pub(crate) fn check(&self) -> Result<(), CoreError> {
        if self.is_cancelled() {
            Err(CoreError::Source("Operation cancelled".into()))
        } else {
            Ok(())
        }
    }
}
