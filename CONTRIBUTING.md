# Repository language

Use English for all authored repository text: interface copy, errors, system permission
prompts, documentation, comments, test descriptions, commit messages and release notes.

Speech recognition must still support its advertised languages. Non-English reference
utterances are test data, not interface copy: serialize them as JSON Unicode escapes and
preserve their decoded values. Use Unicode code-point escapes in normalization and language
regression tests. Do not translate reference transcripts, which would invalidate WER.

Run the repository checks before pushing:

    python -m unittest discover -s tests -v

The language check scans tracked UTF-8 files for literal Cyrillic characters. Binary audio
fixtures and generated recognition output are separate from authored application text.
