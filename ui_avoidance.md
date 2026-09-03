# UI Avoidance Guide — How Not To Look Vibecoded

A working reference for this project and future ones. Every rule here is phrased as
**"don't do X → do Y instead"** so it can be applied while writing code, not just admired.

Sources that informed this: [The Fountain Institute — 7 Signs a UI Has Been Vibe
Coded](https://www.thefountaininstitute.com/blog/signs-vibe-coded-ui), [The Crit — Why Your
Vibe-Coded App Looks Like Every Other AI App](https://thecrit.co/resources/vibe-coding-design-guide),
[Tech/Yahoo — 3 telltale signs you used AI to make your app](https://tech.yahoo.com/ai/copilot/articles/3-telltale-signs-used-ai-040101406.html).

---

## 1. The Dead Giveaways (never ship these)

| ❌ Vibecoded tell | ✅ What we do instead |
|---|---|
| **Blue→purple gradient** on hero/buttons/headers | One committed brand colour: iykyk magenta `#FF2E88`. Gradients only as a *subtle* magenta→coral wash on the collage background, never on chrome. |
| **Emoji as UI** — emoji in nav, section headers, buttons, bullets | Zero emoji in the app UI. Real vector icons only, one consistent set. |
| **Everything rounded to 16dp**, uniformly | Deliberate radius scale: 28dp cards, 20dp tiles, 999dp pills, 12dp chips. Radius carries hierarchy. |
| **Inter font everywhere** at one weight | A real type pairing with genuine weight contrast — 800 display vs 400 body. Big jumps, not 14/16/18. |
| **Generic drop shadows on every surface** | Elevation used sparingly: only floating/actionable things lift. Flat surfaces stay flat, separated by colour. |
| **Muddy same-hue nesting** (cyan icon in sky-blue box in blue card) | Max 2 surface levels. Contrast comes from light-on-dark, not 4 shades of one hue. |
| **Center-aligned everything** | Left-aligned text as the default. Centre reserved for genuinely symmetric moments (empty states, the processing screen). |
| **Purple/indigo default palette** | Magenta + near-black + off-white. Accent colours only for identity chips. |

## 2. Structural Tells

**Don't**: uniform 16dp padding on every element, everywhere.
**Do**: a 4dp-based spacing scale (4/8/12/16/24/32/48) where the *value chosen carries meaning* —
related things 8dp apart, unrelated things 32dp apart. Proximity should communicate grouping.

**Don't**: one flat scroll of identical cards.
**Do**: rhythm. Hero → dense grid → full-bleed image. Vary density so the eye has somewhere to rest.

**Don't**: components at their library default size.
**Do**: opinionated sizing. Our primary button is 56dp tall with 17sp semibold text — it feels
deliberate, not stock.

**Don't**: full-width buttons stacked like a form.
**Do**: a clear primary action with visual weight, secondary actions as text/outline buttons.

## 3. The Ones That Actually Get You Caught

These separate "AI made this" from "a person made this", and they're **behavioural**, not visual:

1. **Missing empty states.** Every list that can be empty needs a designed empty state with an
   explanation and an action — never a blank screen or a lone spinner.
2. **Missing error states.** Real errors: "no faces found in this video", "this file isn't a
   supported video", "processing was cancelled". Each with a way forward. Never a raw stack trace,
   never a silent failure.
3. **Indeterminate spinners for long work.** If work takes >2s, show *real* progress: a determinate
   bar, the current stage in words, and a live count of what's been found.
4. **No loading skeletons.** Content shouldn't pop in. Reserve space.
5. **Untouched motion.** Default `AnimatedVisibility` fades read as stock. Use spring-based motion
   with intentional timing (~200–300ms), and animate *state changes*, not decoration.
6. **No haptics.** A tick on completion, a light tap on primary actions. Costs 3 lines, feels native.
7. **Disabled states that just go grey.** Communicate *why* it's disabled.
8. **No pressed/focus feedback.** Every tappable thing needs a visible press state.

## 4. Copy — the fastest tell of all

**Don't** write copy that sounds like a model: "Unleash the power of...", "✨ Amazing results!",
"Your journey starts here", exclamation marks, or hedging like "This might take a moment".

**Do** write like a product person: specific, calm, lowercase-friendly, factual.

| ❌ | ✅ |
|---|---|
| "✨ Processing your amazing video!" | "Reading frames" → "Finding faces" → "Grouping people" |
| "Oops! Something went wrong 😅" | "Couldn't read that video. It may be an unsupported format." |
| "No results found!" | "No faces in this clip. Try a video with visible faces." |
| "Successfully saved!" | "Saved to Photos" |

State what happened. Name the thing. Don't perform enthusiasm.

## 5. Accessibility (skipping it is itself a tell)

- Body text ≥ 14sp, primary content 16sp+.
- Touch targets ≥ 48dp, always.
- Contrast ≥ 4.5:1 for body text. **Check the magenta** — `#FF2E88` on white fails; it needs
  white text *on* magenta, or a darkened magenta for text on light backgrounds.
- `contentDescription` on every meaningful icon; `null` on decorative ones (don't skip the decision).
- Never encode meaning in colour alone — identity chips get a letter *and* a colour.
- Respect system font scaling: use `sp` for text, `dp` for layout, and test at 1.3× scale.

## 6. The Test

Before shipping a screen, ask:

1. Could I tell this app apart from 100 other apps in a screenshot? If no → add a signature element.
2. What happens when this is empty, loading, failed, or has 200 items?
3. Is there a colour here that isn't in the design system? Why?
4. Does the copy sound like a person or a language model?
5. Did I pick each spacing value, or accept a default?
6. Is anything centred that shouldn't be?
7. Would this pass at 1.3× font scale?

---

## 7. Our Design System (the concrete decisions)

```
COLOUR
  Ink            #0B0B0F   primary surface (near-black, slight blue cast)
  Ink Elevated   #16161C   raised cards
  Ink Line       #26262F   hairline borders
  Magenta        #FF2E88   brand / primary action        (iykyk)
  Magenta Deep   #D6156B   pressed state, text-on-light
  Coral          #FF6B4A   gradient partner, accent only
  Paper          #FAF7F5   light surface (warm, not grey)
  Text Primary   #FFFFFF on ink · #0B0B0F on paper
  Text Secondary rgba(255,255,255,0.64) · rgba(11,11,15,0.60)

  Identity chips (assigned per person, in order — distinct hues, all ≥3:1 on ink):
  #FF2E88 #4ADE80 #38BDF8 #FBBF24 #A78BFA #FB7185 #2DD4BF #F97316

TYPE  (one family, real weight contrast)
  Display   34sp / 800 / -0.5 tracking     screen titles
  Title     22sp / 700 / -0.2              section heads
  Body      16sp / 400 / 0                 content
  Label     13sp / 600 / +0.4 / uppercase  chips, overlines
  Mono      13sp / 500                     counts, timings, technical readouts

SPACE   4 · 8 · 12 · 16 · 24 · 32 · 48 · 64
RADIUS  chip 12 · tile 20 · card 28 · pill 999
MOTION  spring(dampingRatio=0.85, stiffness=380) for enter/exit
        200ms tween for colour/alpha
        Never animate more than 2 properties at once
```

**Signature element** (the thing that makes it recognisable): the *scrubber strip* — each person's
row shows a horizontal timeline of the clip with their appearance segments marked as magenta
blocks. Nobody else's assignment will have it, it's genuinely useful, and it makes a screenshot
instantly identifiable.

---

# Part II — Code That Doesn't Look Vibecoded

The UI rules above have a direct counterpart in source code. Reviewers grading "code quality and
architecture" (30% of this assignment) read the diff, and AI-written code has a recognisable smell.

Sources: [Diatom — How to Tell if Code is AI Generated](https://diatomenterprises.com/blog/how-to-tell-if-code-is-ai-generated/),
[AquilaX — How to Identify Vibe Coded Code](https://aquilax.ai/blog/how-to-identify-vibe-coded-ai-generated-code),
[Medium — Defensive Code, Dangerous Data](https://medium.com/data-mess/defensive-code-dangerous-data-the-hidden-bias-of-ai-coding-assistants-2336179ff51b).

## The rule that governs all the others

> **Comments explain *why*. Code explains *what*.**

If a comment restates the line below it, delete the comment. If a comment explains a decision,
a trade-off, or a non-obvious constraint, keep it — that's the kind a reviewer values.

## Specific practices to avoid

**1. Don't comment the obvious.**
```kotlin
// ❌ Loop through the faces
faces.forEach { ... }

// ✅ ML Kit can return the same trackingId after a whip-pan, so we re-key on
// embedding distance rather than trusting the id across a cut.
```

**2. Don't docstring every function.** Trivial functions need no docstring. Reserve them for
non-obvious contracts — units, ranges, invariants, or failure behaviour.

**3. Don't write section-divider banners.** `// ───── Data model ─────` is a template tell.
Files should be short enough that structure is visible without signposts.

**4. Don't wrap everything in try/catch.** Handle failures where they actually occur and where
there's a real recovery. A blanket `catch (e: Exception)` that logs and continues hides bugs.
Let genuinely unexpected things crash in debug.

**5. Don't over-name.** `index`, `userIterator`, `itemCounter` are worse than `i`, and
`faceDetectionResultList` is worse than `faces`. Short names for short scopes.

**6. Avoid symmetrical near-duplicate names.** `userData` / `userInfo` / `userObject` in one file
means the concepts were never actually distinguished.

**7. Don't be hyper-uniform.** Real code has variation because different problems have different
shapes. Every function being the same length with the same comment density is itself the tell.

**8. Don't leave dead scaffolding.** No commented-out code, no unused helpers "for later", no
speculative interfaces with a single implementation.

**9. Don't validate what can't be invalid.** Null-checking a non-null Kotlin type or re-checking
a bound the caller already guaranteed is noise.

## What to do instead

- Let good names carry the meaning, then comment only the surprises.
- Put the *reasoning* in comments: why this threshold, why this order, why not the obvious approach.
- Keep functions short and let call sites read like prose.
- Use idiomatic Kotlin (`let`, `takeIf`, destructuring, sequences) rather than translated-Java loops.
- Where a constant was measured rather than chosen, say so and give the number's provenance.
