# Domain Docs

Engineering skills should consume this repository's domain documentation as follows.

## Before exploring, read these

- Read the repository root `CONTEXT.md` when it exists.
- Read the relevant decisions in `docs/adr/` when that directory exists.
- If these files do not exist, continue silently. Do not flag their absence or proactively create them.

## File structure

This is a single-context repository:

```
/
|- CONTEXT.md
|- docs/adr/
`- src/
```

## Use the glossary vocabulary

When naming a domain concept in an issue, proposal, refactor, or test, use the terminology defined in `CONTEXT.md`. If a required concept is missing, record it through the domain-modeling workflow rather than inventing a competing synonym.

## Flag ADR conflicts

If proposed work conflicts with an existing ADR, explicitly call out the conflict instead of silently overriding the decision.
