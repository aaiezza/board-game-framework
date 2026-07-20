package games.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GameEngineTest {
    private data object Advance : PlayerIntent
    private data class Advanced(val value: Int) : GameEvent
    private data class Completed(val value: Int) : GameEvent
    private data class State(
        val value: Int,
        override val turn: TurnContext?,
        override val turnNumber: TurnNumber = TurnNumber(0),
        override val history: EventHistory<GameEvent> = EventHistory(),
    ) : HistoryWritableState<GameEvent> {
        override fun withHistory(history: EventHistory<GameEvent>) = copy(history = history)
    }

    private val owner = PlayerId("owner")
    private val responder = PlayerId("responder")
    private val definition = object : GameDefinition<State, Advance, GameEvent> {
        override fun legalIntents(state: State) = if (state.value < 2) setOf(LegalIntent(Advance)) else emptySet()

        override fun resolve(state: State, actor: PlayerId, intent: Advance) = Resolution(
            listOf(
                ResolutionStep.PlayerDriven(listOf(Advanced(state.value + 1))),
                ResolutionStep.RuleDriven(RuleId("test.complete"), listOf(Completed(state.value + 2))),
            ),
        )

        override fun reduce(state: State, event: GameEvent) = when (event) {
            is Advanced -> state.copy(value = event.value)
            is Completed -> state.copy(value = event.value, turn = null)
            else -> error("Unsupported event: $event")
        }

        override fun outcome(state: State) = if (state.value == 2) GameOutcome.PlayerWon(responder) else GameOutcome.InProgress
    }

    @Test
    fun `engine returns player-driven and rule-driven transition trace`() {
        val initial = State(0, TurnContext(owner, setOf(responder)))

        val progression = GameEngine(definition).playWithTrace(initial, responder, Advance)

        assertEquals(2, progression.steps.size)
        assertTrue(progression.steps[0].cause is TransitionCause.PlayerDriven)
        assertEquals(1, progression.steps[0].resultingState.value)
        assertEquals(TransitionCause.RuleDriven(RuleId("test.complete")), progression.steps[1].cause)
        assertEquals(2, progression.resultingState.value)
        assertEquals(2, progression.resultingState.history.transitions.size)
        assertEquals(2, progression.resultingState.history.events.size)
        assertEquals(responder, progression.resultingState.history.events.first().actor)
        assertEquals(owner, progression.resultingState.history.events.first().turnOwner)
        assertNull(progression.resultingState.history.events.last().actor)
        assertEquals(
            listOf(0, 1, 2),
            progression.resultingState.history.states(initial) { state, event -> definition.reduce(state, event) }.map { it.value },
        )
    }

    @Test
    fun `turn owner may differ from the eligible decision actor`() {
        val initial = State(0, TurnContext(owner, setOf(responder)))

        val result = GameEngine(definition).play(initial, responder, Advance)

        assertEquals(GameOutcome.PlayerWon(responder), definition.outcome(result))
    }

    @Test
    fun `engine rejects an actor who does not own the current decision`() {
        val initial = State(0, TurnContext(owner, setOf(responder)))

        assertThrows<IllegalArgumentException> { GameEngine(definition).play(initial, owner, Advance) }
    }
}
