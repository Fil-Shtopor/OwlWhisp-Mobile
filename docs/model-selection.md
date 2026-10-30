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
pre-0.1.6 benchmark report. Results from different model weight revisions are not reused.
A dash means no matching device measurement exists; it is not a zero error rate or speed.

WER is summed edit errors divided by summed reference words. RTF is summed processing
time divided by summed audio time. The bundled comparison has three English and three
Russian clips; unsupported languages are skipped. Model cards label each measured language
and clip count. All clips use a cold model load. These short comparisons are not a general
accuracy ranking, and RTF measured on one phone is not a prediction for another.

## Phone and language guide

Models > Model recommendations offers the detected phone memory class,
example phone profiles, a language and a priority: everyday dictation, smallest download or
multilingual dictation. Suggestions filter out unsupported languages and platform runtimes.
The smallest-download option actually sorts the eligible shipped downloads by byte size.

These filters only change the recommendations. They do not change the selected model or
language shortcuts, and they do not start a download. Each recommendation shows its size
and a Download button. Tap Download, wait for verification, then tap Use model (or Use for
the recommended language when language shortcuts are enabled). There is no need to close
the recommendations to start a download.

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

## Downloading while switching screens

On Android, an explicit model download runs in a data-sync foreground service with a
progress notification and a Cancel action. You can switch app tabs, close recommendations,
open another app or lock the screen. The main app shows progress and Cancel on every tab.
The service and its wake lock stop after completion, cancellation or an error. Denying
notification permission does not cancel the transfer; Android controls notification visibility.

Downloads are still subject to Android's system limits, force-stop and network failures.
Retry from Models or recommendations after an interruption. Already verified files are
reused; an incomplete file is downloaded again and never offered as a ready model. File
sizes and SHA-256 hashes are verified before installation.

On iOS, download tasks belong to the app model rather than a tab or recommendation row.
You can navigate within the app during a download. This release does not add persistent iOS
background URLSession downloads; keep the app active for large transfers.

[Android data-sync foreground services](https://developer.android.com/develop/background-work/services/fgs/service-types#data-sync)
and [system time limits](https://developer.android.com/develop/background-work/services/fgs/timeout).
