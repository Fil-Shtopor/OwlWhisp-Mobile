# Shared-core integration

The desktop source remains https://github.com/Fil-Shtopor/OwlWhisp. Its lw-app includes desktop
capture, injection, paths, runtime downloads and subprocess workers. lw-core also pulls in
networking/TLS and filesystem dependencies. A mobile-ready Rust SDK is not available yet.

The build proof uses official native sherpa-onnx wrappers: a fixed engine version, the same
Parakeet model and one manifest across Android and iOS. There is no copied desktop Rust source,
placeholder SDK or implicit sibling-path dependency.

The ordinary 0.6B v3 weights use the sherpa encoder/decoder/joiner export rather than the desktop
fused decoder_joint export. The base model is the same; file layouts differ. The mobile export
is pinned to commit 2bda32ec70b097a55adaa07d9a7173915b43cc78 of
csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.

The historical audit moved to research/desktop-audit-2026-10-19.md. Its repository recommendation
has been superseded. Desktop model manifests, engines and fixtures stay in OwlWhisp because the
desktop application uses them.

A future SDK should extract session/configuration, text/dictionary, VAD and model policy.
Pass sandbox directories/audio explicitly; keep capture, text delivery, background tasks,
secrets and updates in native hosts. Consume a pinned SDK revision/package and matching native
artifacts. Local overrides may support a sibling checkout, but committed builds must be standalone.
