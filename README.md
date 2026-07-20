# Board Game Framework

Domain-first Kotlin contracts for immutable game state, legal player intents, semantic event resolution, turn flow, outcomes, history, boards, players, and optional configuration.

## State progression

A player intent is a stimulus, not necessarily one indivisible state change. A game definition resolves it into an explicitly ordered sequence of semantic transitions:

```text
authoritative state awaiting input
    -> player-driven transition
    -> zero or more rule-driven transitions
    -> authoritative state awaiting input, or terminal state
```

The engine runs every deterministic rule-driven step locally. It stops only when another decision is required or the game ends.

```kotlin
val progression = engine.playWithTrace(state, actor, intent)

progression.initialState
progression.steps
progression.resultingState
```

Each `TransitionStep` contains its `TransitionCause`, emitted events, and resulting intermediate state. Intermediate trace states support explanation, testing, replay, and UI animation; the furthest state is the authoritative result returned to ordinary callers.

Call `play` when only that result is needed:

```kotlin
val nextState = engine.play(state, actor, intent)
```

## Player-driven and rule-driven transitions

Definitions return a `Resolution` whose first step is player-driven and whose remaining steps are explicitly ordered rule-driven reactions.

```kotlin
Resolution(
    listOf(
        ResolutionStep.PlayerDriven(listOf(StonesSown(...))),
        ResolutionStep.RuleDriven(
            RuleId("mancala.capture"),
            listOf(StonesCaptured(...)),
        ),
        ResolutionStep.RuleDriven(
            RuleId("mancala.advance-turn"),
            listOf(TurnAdvanced(...)),
        ),
    ),
)
```

Rich events describe enough detail for clients to interpolate presentation frames. For example, a sowing event may carry every stone placement while remaining one semantic domain step.

History records the same causes. Player-driven records retain the actor and submitted intent; rule-driven records retain the stable `RuleId` that explains the reaction.

History preserves semantic transition grouping and can reconstruct the state reached after each transition:

```kotlin
val states = state.history.states(initialState, definition::reduce)
val ruleReactions = state.history.transitions
    .filter { it.cause is TransitionCause.RuleDriven }
```

## Turn ownership and decisions

`TurnContext` separates two concepts that simple alternating games often conflate:

- `owner`: the player whose turn remains in progress.
- `decisionActors`: the players currently eligible to submit an intent.

```kotlin
TurnContext(
    owner = alice,
    decisionActors = setOf(bob),
)
```

This represents Bob responding during Alice's turn. It also supports multiple eligible responders. `GameEngine` validates submitted intents against `decisionActors`, not against the turn owner.

`GameState.currentPlayer` remains a convenience property and returns a player only when there is exactly one decision actor. New game rules should use `turnOwner` and `decisionActors` whenever the distinction matters.

## Authoritative states and traces

Persist authoritative game states, not presentation-oriented trace frames. Deterministic work remains in the returned progression. When rule resolution requires new input, the resulting authoritative state must retain both the decision requirement and enough pending continuation to resume afterward.

This provides a practical boundary:

- A Mancala capture resolves automatically and appears in the trace.
- A chess promotion must pause in an authoritative decision state.
- A Catan trade response may temporarily assign decision authority to another player while preserving the original turn owner.

## Turn-yield behavior

Turn yielding is game-defined behavior:

| Game style | Example | Behavior |
|---|---|---|
| Automatic | Tic-Tac-Toe, Connect Four | A completed move yields to the next player |
| Rule-dependent | Mancala, Checkers | Resolution decides whether the same player continues |
| Explicit | Roll For It, Catan | `EndTurn` is a legal player intent |

Future DSL layers may provide construction styles with appropriate defaults. Games should not expose irrelevant Boolean options simply to fit one configuration shape.

## Core contracts

- `GameDefinition` supplies legal intents, ordered resolution, event reduction, and outcomes.
- `GameEngine` validates decision authority and returns either a final state or complete `Progression`.
- `ResolutionStep` identifies player-driven results and ordered rule-driven reactions.
- `TransitionCause` explains both trace steps and recorded history.
- `TurnContext` separates turn ownership from present decision authority.
- `HistoryWritableState` lets the engine append causal event history immutably.
- `BoardGameState` and `PhasedGameState` add common domain projections without prescribing a game taxonomy.

## Verification

```bash
mvn test
mvn spotless:apply
mvn verify
```

The broader model rationale, exploration table, and expansion/variant guidance live in the parent collection's `docs/transition-model.md`.
