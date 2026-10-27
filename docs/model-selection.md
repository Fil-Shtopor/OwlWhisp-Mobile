# Choosing and switching models

## Language shortcuts

Settings > Language models > Experimental language switch is off by default. Enable it,
assign a model to each language, and download those models in Models. The voice panel on
Android and Dictate on iOS then show a language button. It changes the assigned model
between recordings. During recording or final processing, switching and editing mappings
are disabled. Turning the feature off hides the button and restores the manually chosen
default model.

Assignments persist between launches. Only models that support the selected language and
platform can be assigned. Missing downloads are identified rather than silently starting
another model. Language shortcuts select models; multilingual recognizers may still detect
the language in the audio. This is not automatic model switching within a recording.

## Model cards and measurements

Models uses compact cards (Android) or expandable rows (iOS). Each model shows download
size, language support, installation state and saved WER/RTF by language. Details contain
repair/removal actions, license attribution and the measurement's app version.

Run Benchmark for a model to populate its numbers. Reports are saved independently, so
running another model does not overwrite its measurements. Android can also show the latest
pre-0.2.0 benchmark report. Results from different model weight revisions are not reused.
A dash means no matching device measurement exists; it is not a zero error rate or speed.

WER is summed edit errors divided by summed reference words. RTF is summed processing
time divided by summed audio time. The bundled comparison has three English and three
Russian clips; unsupported languages are skipped. Model cards label each measured language
and clip count. All clips use a cold model load. These short comparisons are not a general
accuracy ranking, and RTF measured on one phone is not a prediction for another.

## Phone and language guide

Models > Find a model for your phone and language offers the detected phone memory class,
example phone profiles, a language and a priority: everyday dictation, smallest download or
multilingual dictation. Suggestions filter out unsupported languages and platform runtimes.
The smallest-download option actually sorts the eligible shipped downloads by byte size.

The default starting points are GigaAM v3 for Russian, Moonshine Tiny for compact English
dictation, Parakeet v3 for mixed European languages and SenseVoice for its supported East
Asian languages. Whisper Turbo, Qwen3-ASR and Omnilingual provide alternatives where their
catalog language lists match the task. Suggestions do not claim one model has better WER
on every accent or recording.

Phone examples include Galaxy A15, Galaxy A55, Galaxy S24/S25 Ultra, Pixel 8a/9 Pro and
iPhone profiles. These are manually curated memory/workload starting points, not measured
phone leaderboards or guarantees of real-time performance. The current phone's physical
RAM is detected locally; RAM and download size are different quantities. No phone test
scores are fabricated or copied from desktop GPU benchmarks.

Shared data, priorities and primary sources are in [model-guide.json](../shared/model-guide.json).
Language support follows the exact pinned manifests in shared/models/. Model sources include
[NVIDIA Parakeet](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3),
[GigaAM](https://huggingface.co/ai-sage/GigaAM-v3),
[Moonshine](https://huggingface.co/moonshine-ai/moonshine-tiny) and
[SenseVoice](https://huggingface.co/FunAudioLLM/SenseVoiceSmall). Phone reference specifications
are linked per example profile. Recommendation priorities are OwlWhisp heuristics, not
performance statements from those manufacturers.
