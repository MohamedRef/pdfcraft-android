# Reuse source field discovery during opening

Opening used to enumerate form fields for display-only appearances and then enumerate the original source again for the document's form model. Each enumeration visits page annotations, including PDFs whose result is an empty field list. Large link collections can therefore pay for a second all-page pass without containing any fields.

`display_bytes_collect_fields` now captures the field discovery already required for display when no comment appearance overlay changes the source document. It passes the same list to the form appearance builder and later to the document's form model. Field values, widget adoption, script-button marking and signature handling keep their existing behavior.

If comment appearances produce an overlay, appearance generation still discovers fields on that overlay and the final model is discovered on the original source as before. That case does not retain two field lists or reuse data across document states. Refresh, printing and export callers use the existing fresh-discovery wrapper; the new borrowed-snapshot helper is used only with fields from the same immutable document state. The ordinary wrapper keeps its owning iterator so it releases processed fields as before.

## Timing interpretation

The opt-in `native_display_form_discovery` stage measures the captured discovery and is nested inside `native_display`. The final `native_form_fields` step can then take zero milliseconds because it takes the captured result, not because the document necessarily has no fields. When a comment overlay requires the original path, the captured stage is absent and final discovery remains fresh. Nested durations must not be added as independent work.

## Validation

- Forms regression compares appearance objects, field values and adoption counts with fresh enumeration, while confirming one whole-form read is removed.
- Engine regression compares rendered pixels and saved original bytes for ordinary fields, missing comment appearances, empty forms and adopted page-only widgets. It also verifies that an existing collector is cleared instead of reused for a changed comment overlay.
- A controlled host comparison used the same opening implementation with capture disabled versus enabled. Fixtures had 869 pages and 32 unique links per page (27,808 links), with approximately 352 MB of raw 400×337 RGB images. A second variant used compressed object streams. The setup is the same image/link scenario described in `stream-end-open.md`. Each variant was opened three times in the repository dev profile; the values below are medians of illustrative runs, not device performance guarantees.

| Fixture | Fresh final discovery | Shared discovery | Native preparation before | Native preparation after |
| --- | ---: | ---: | ---: | ---: |
| Ordinary link objects | 76 ms | 0 ms | 398 ms | 312 ms |
| Compressed link objects | 67 ms | 0 ms | 367 ms | 311 ms |
| Image book without links | 0 ms | 0 ms | 13 ms | 14 ms |

The original file read, renderer inspection, timestamp discovery and other appearance preparation still occur. This change removes one duplicate discovery where eligible; it does not establish an improvement on any particular original PDF, make opening progressive, or validate physical Android behavior. Comment-overlay cases deliberately retain their original discovery flow.
