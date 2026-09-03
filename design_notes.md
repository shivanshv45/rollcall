# Design notes

The rules this app's UI and code are held to. Written down so the answers stay
consistent across screens instead of being re-decided each time.

---

## Part I — Interface

### Colour

```
Ink            #0B0B0F   primary surface (near-black, slight blue cast)
Ink Elevated   #16161C   raised cards
Ink Line       #26262F   hairline borders
Magenta        #FF2E88   brand / primary action
Magenta Deep   #D6156B   pressed state, text on light
Coral          #FF6B4A   gradient partner, accent only
Paper          #FAF7F5   light surface (warm, not grey)
Text Primary   #FFFFFF on ink · #0B0B0F on paper
Text Secondary rgba(255,255,255,0.64)
```

Identity chips, assigned in detection order — distinct hues, all ≥3:1 on ink:

```
#FF2E88  #4ADE80  #38BDF8  #FBBF24  #A78BFA  #FB7185  #2DD4BF  #F97316
```

One committed brand colour, not a gradient. Magenta comes from iykyk's own site.
Gradients appear only as a subtle magenta→coral wash behind the collage, never on
buttons or chrome.

### Type

One family, real weight contrast. Big jumps rather than 14/16/18.

```
Display   34sp / 800 / -0.5 tracking     screen titles
Title     22sp / 700 / -0.2              section heads
Body      16sp / 400                     content
Label     13sp / 600 / +0.4 / uppercase  chips, overlines
```

### Space and shape

```
SPACE   4 · 8 · 12 · 16 · 24 · 32 · 48 · 64
RADIUS  chip 12 · tile 20 · card 28 · pill 999
MOTION  spring(dampingRatio 0.85, stiffness 380) for enter/exit
        200ms tween for colour and alpha
```

The spacing value should carry meaning: related things sit 8dp apart, separate
ideas 32dp. Uniform padding everywhere flattens the hierarchy and makes a screen
harder to scan, not easier.

Radius carries hierarchy too — a chip reads tighter than a card.

### Rules

- **Left-align by default.** Centre only for genuinely symmetric moments: empty
  states, the processing screen.
- **Elevation is for things that act.** Floating and tappable surfaces lift;
  everything else stays flat and is separated by colour.
- **Two surface levels, maximum.** Contrast comes from light-on-dark, not from
  four shades of the same hue nested inside each other.
- **No emoji in the interface.** Vector icons, one set.
- **Opinionated sizing.** The primary button is 56dp with 17sp semibold text —
  larger than the Material default, because it is the main action on the screen.
- **One clear primary action** per screen, with secondary actions as text or
  outline buttons. Not a stack of identical full-width buttons.

### States

Every screen needs four, and they are the first thing to get skipped:

1. **Empty** — an explanation and an action, never a blank screen.
2. **Error** — what happened and what to do: "Couldn't read that video. It may be
   an unsupported format." Never a stack trace, never silence.
3. **Loading** — anything over two seconds gets determinate progress, the current
   stage named in words, and a live count of what has been found.
4. **Cancelled** — the user asked to stop, so stop and go back.

Plus: a visible press state on everything tappable, a haptic tick on completion
and primary actions, and disabled states that explain why they are disabled.

### Copy

Specific, calm, factual. State what happened and name the thing.

| Instead of | Write |
|---|---|
| "Processing your video!" | "Reading frames" → "Finding faces" → "Grouping people" |
| "Oops! Something went wrong" | "Couldn't read that video. It may be an unsupported format." |
| "No results found!" | "No faces in this clip. Try a video with visible faces." |
| "Successfully saved!" | "Saved to Photos" |

No exclamation marks, no enthusiasm, no hedging.

### Accessibility

- Body text ≥14sp, primary content 16sp+.
- Touch targets ≥48dp.
- Contrast ≥4.5:1 for body text. **Magenta on white fails** — use white text on
  magenta, or Magenta Deep for text on light backgrounds.
- `contentDescription` on meaningful icons, `null` on decorative ones. Make the
  decision either way.
- Never encode meaning in colour alone: identity chips carry a letter *and* a
  colour.
- `sp` for text, `dp` for layout. Check every screen at 1.3× font scale.

### The signature element

The **scrubber strip**: each person's row shows the clip's timeline with their
appearance segments marked as blocks. It makes the count checkable at a glance
rather than something to take on trust, and it makes a screenshot of this app
recognisable.

---

## Part II — Code

### The rule the rest follow from

> **Comments explain *why*. Code explains *what*.**

If a comment restates the line beneath it, delete the comment. If it explains a
decision, a trade-off, or a constraint that is not visible locally, keep it.

```kotlin
// Not this:
// Loop through the faces
faces.forEach { ... }

// This:
// ML Kit can reuse a trackingId after a whip-pan, so identity is re-keyed on
// embedding distance rather than trusting the id across a cut.
```

### Practices

- **No docstring on a trivial function.** Reserve them for non-obvious contracts:
  units, ranges, invariants, failure behaviour.
- **No section-divider banners.** A file should be short enough that its
  structure is visible without signposts.
- **No blanket `try`/`catch`.** Handle failures where they happen and where there
  is a real recovery. A catch-all that logs and continues hides bugs.
- **Short names in short scopes.** `faces`, not `faceDetectionResultList`. `i`,
  not `itemCounter`.
- **No near-duplicate names.** `userData` / `userInfo` / `userObject` in one file
  means the concepts were never actually separated.
- **No dead scaffolding.** No commented-out code, no unused helpers kept "for
  later", no interface with a single implementation.
- **Don't validate what cannot be invalid.** Null-checking a non-null type or
  re-checking a bound the caller guaranteed is noise.
- **Idiomatic Kotlin.** `let`, `takeIf`, destructuring, sequences — not a Java
  loop transcribed.
- **Where a constant was measured, say so and give the number's provenance.**
  Every threshold in `PipelineConfig` carries the range it holds across.
