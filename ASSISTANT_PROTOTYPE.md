# On-device anime companion prototype

Version 4.1.7 (412). Open Home → Ask for recommendations, or More → Anime companion.

This prototype uses Gemini Nano through ML Kit's Prompt API (1.0.0-beta4). There is no hosted AI fallback or API key in the APK. Android 8/API 26 is now the minimum because the SDK requires it. Google's current support list includes the S26 Ultra, but readiness and response quality must be verified on the actual phone.

## First phone check

1. Install over 4.1.4–4.1.6 using the normal in-app updater. Keep the existing app data.
2. Sign in to MAL if needed. Open Anime companion and tap Check Nano. If offered, tap Download model while connected; Android/AICore manages the model assets.
3. Start with “I loved Made in Abyss. Find something similar.” If multiple titles match, choose the correct one.
4. Look for the imported visible-title and rated-title counts. Compare them with your MAL list under the same content-visibility setting.
5. Try “Keep the same preferences, but only series under 24 episodes,” then “I've already seen the first suggestion.” Check the displayed episode lengths and exclusion.
6. Try “Less disturbing, but keep the exploration.” Mood comparisons use short synopses and are tentative; they are not verified content warnings.
7. Use View / add to list to open the existing details/list editor. The companion never marks something watched automatically.
8. New chat resets the conversation, exclusions, and in-memory catalog/history cache. Stop cancels work while preserving the previous accepted results. Closing the companion route releases its model client and conversation state. No chat database is created.

## How personalization works

The importer follows all pages for completed, watching, plan-to-watch, on-hold, and dropped entries and deduplicates by MAL ID. A failed/inconsistent page aborts import instead of presenting a partial profile as complete. Current content visibility applies, so the displayed count means visible imported titles.

Ratings at or above the user's own average influence genre preferences; unrated entries stay neutral. Rewatch count slightly reinforces scored favorites. Watched/currently active/on-hold/dropped titles are excluded from discovery. Plan-to-watch entries may appear. The selected seed and its known related titles are excluded to keep comparisons distinct from franchise continuation.

The candidate pool is deliberately bounded: seed-title recommendation links, MAL suggestions, and links from higher-rated titles. Up to six favorites and thirty candidate details are enriched, with three concurrent detail requests and an in-memory cache. This is not an exhaustive search of MAL. The prototype currently supports anime; manga is later work.

Nano interprets a request into validated constraints and orders up to five already-filtered, real catalog IDs. Episode/format/excluded-genre constraints are enforced by app code. Unknown lengths cannot pass a strict length limit. Unknown or duplicate model IDs, malformed JSON, and invalid constraints fail the request without replacing the last accepted results. Explanations on cards are generated from actual genre overlap and recommendation-link evidence, avoiding unsupported model-written facts.

The model receives a compact current request/previous constraints and up to ten candidate descriptions. It does not receive MAL credentials, account email, birthday, private comments, or the entire history. Personalization is computed locally from imported ratings and statuses. Catalog and account requests go to MAL; Android/AICore manages Google's model downloads and setup, while model inference runs locally. Prompts remain short and are checked against Nano's actual token limit before generation.

## Validation boundaries

JVM tests exercise full pagination/failure handling, neutral unrated data, personal-rating ranking, strict filters, invented IDs, and follow-up behavior with a fake model and fictional catalog. Compose tests cover unavailable-model UI, real details navigation, and clearing chat in both themes. Android emulator tests can verify screen launch and unavailable Nano handling, plus existing update/search regressions.

An AOSP emulator does not run real Nano inference. These checks do not establish response quality, speed, memory use, battery use, model download behavior, or a real MAL session on the S26 Ultra. The phone checks above are required for those claims. Availability/error handling is explicit; the app does not silently replace Nano with fake AI responses.

References: [Prompt setup](https://developers.google.com/ml-kit/genai/prompt/android/get-started), [ML Kit GenAI device support](https://developers.google.com/ml-kit/genai), and [GenerativeModel API](https://developers.google.com/android/reference/kotlin/com/google/mlkit/genai/prompt/GenerativeModel).
