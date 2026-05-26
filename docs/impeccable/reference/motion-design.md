# Motion Design

## Duration: The 100/300/500 Rule

Timing matters more than easing. These durations feel right for most UI:

| Duration | Use Case | Examples |
|----------|----------|----------|
| **100-150ms** | Instant feedback | Button press, toggle, color change |
| **200-300ms** | State changes | Menu open, tooltip, hover states |
| **300-500ms** | Layout changes | Accordion, modal, drawer |
| **500-800ms** | Entrance animations | Page load, hero reveals |

**Exit animations are faster than entrances.** Use ~75% of enter duration.

## Easing: Pick the Right Curve

**Don't use `ease`.** It's a compromise that's rarely optimal. Instead:

| Curve | Use For | CSS |
|-------|---------|-----|
| **ease-out** | Elements entering | `cubic-bezier(0.16, 1, 0.3, 1)` |
| **ease-in** | Elements leaving | `cubic-bezier(0.7, 0, 0.84, 0)` |
| **ease-in-out** | State toggles (there → back) | `cubic-bezier(0.65, 0, 0.35, 1)` |

**For micro-interactions, use exponential curves.** They feel natural because they mimic real physics (friction, deceleration):

```css
/* Quart out - smooth, refined (recommended default) */
--ease-out-quart: cubic-bezier(0.25, 1, 0.5, 1);

/* Quint out - slightly more dramatic */
--ease-out-quint: cubic-bezier(0.22, 1, 0.36, 1);

/* Expo out - snappy, confident */
--ease-out-expo: cubic-bezier(0.16, 1, 0.3, 1);
```

**Avoid bounce and elastic curves.** They were trendy in 2015 but now feel tacky and amateurish. Real objects don't bounce when they stop; they decelerate smoothly. Overshoot effects draw attention to the animation itself rather than the content.

## Premium Motion Materials

Transform and opacity are reliable defaults, not the whole palette. Premium interfaces often need atmospheric properties: blur reveals, backdrop-filter panels, saturation or brightness shifts, shadow bloom, SVG filters, masks, clip paths, gradient-position movement, and variable font or shader-driven effects.

Use the right material for the effect:

- **Transform / opacity**: movement, press feedback, simple reveals, list choreography.
- **Blur / filter / backdrop-filter**: focus pulls, depth, glass or lens effects, softened entrances, atmospheric transitions.
- **Clip path / masks**: wipes, reveals, editorial cropping, product-like transitions.
- **Shadow / glow / color filters**: energy, affordance, focus, warmth, active state.
- **Grid-template rows or FLIP-style transforms**: expanding and reflowing layout without animating `height` directly.

The hard rule is not "transform and opacity only." The hard rule is: avoid animating layout-driving properties casually (`width`, `height`, `top`, `left`, margins), keep expensive effects bounded to small or isolated areas, and verify in-browser that the result is smooth on the 

