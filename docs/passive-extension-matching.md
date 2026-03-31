# Passive Extension Matching

## Problem

Passive extensions worked on newer WebView versions, but on older WebView/Chrome builds:

- active extensions worked
- passive extensions often failed to auto-run
- `*` matched, while patterns such as `google.com` or `*.google.com` did not

## Root Cause

The previous passive matching logic relied on regex-based glob conversion in both:

- Kotlin fallback matching
- JavaScript document-start matching

That implementation was not reliable across older WebView engines. In practice, only the trivial `*` case was consistently working.

## Fix

Both matching paths now use the same simple glob matcher:

- case-insensitive
- split on `*`
- sequential substring matching
- no regex dependency

This applies to:

- Kotlin fallback matching in `UserExtensionInjector.matches(...)`
- JavaScript document-start matching in `UserExtensionInjector.buildDocumentStartScript(...)`

## Notes

- `*` matches everything
- `google.com` matches only `google.com`
- `*.google.com` matches subdomains such as `www.google.com`
- if both bare domain and subdomains are needed, use `google.com@@*.google.com`
