//! The crate's integration tests, linked into one test binary.

mod action_failures;
mod attachment_key;
mod automation;
mod automation_roots;
mod backdrop;
mod batch;
mod batch_failures;
mod batch_secrets;
mod button_caption;
mod form_bulk;
mod hostile_args;
// Android deliberately omits PRC/model tools and their desktop implementation scenarios.
// Keep their source files pinned, and test the mobile rejection contract below instead.
#[test]
fn deferred_desktop_model_tools_are_unavailable_without_document_edits() {
    let mut automation = pdfcraft_automation::Automation::new();
    let before = automation.call("doc_list", &serde_json::json!({})).unwrap();
    for name in ["model_add", "model_render"] {
        let result = automation.call(name, &serde_json::json!({"doc":1,"page":1,"path":"unopened.prc"}));
        assert!(matches!(result, Err(pdfcraft_automation::ToolError::UnknownTool(_))));
    }
    assert_eq!(automation.call("doc_list", &serde_json::json!({})).unwrap(), before);
}
mod optimize_content_boundaries;
mod redact_after_stray_q_form;
mod redact_text_after_q;
mod render_warnings;
