---
type: decision
description: Reasons for extending the existing village mod step by step.
status: active
archived: false
work_status: in-progress
plan: docs/plans/2026-10-29__master-features
---

# Decisions on the Master Prompt

## Assignment

The user demands: "Yes, then keep going on everything." The user's complete master prompt with nine functional areas remains authoritative. GPT-6.1 Sol integrates; GPT-6 Luna with Medium is explicitly requested for subagents.

## Decisions

- Existing worlds must remain loadable. Running build plans keep their previous step sequence. New phases need a persistent marker.
- Scaffold positions must survive a restart and must not remove foreign blocks during cleanup.
- Production and transport change real inventories. Missing recipes, ingredients or capacity lead to waiting instead of generated goods.
- The village map keeps its data resolution. Zoom and panning change only the presentation and coordinate conversion in the client.
- Progress is recorded in the plan per verified function. A successful build does not confirm complete game mechanics or modpack compatibility.

## Useful Failure Evidence

The first freight test checked an old chest reference after the relocation. The current village chest contained the goods correctly; the test was corrected to the current reference. The earlier phase display was based on percentages and claimed a scaffold that was still missing. This display was corrected before the actual phase implementation.
