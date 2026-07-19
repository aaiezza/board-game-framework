package games.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GameEngineTest {
    private data object Advance : PlayerIntent
    private data class Advanced(val value: Int) : GameEvent
    private data class State(val value: Int, override val currentPlayer: PlayerId?, override val turnNumber: TurnNumber = TurnNumber(0), override val history: EventHistory<Advanced> = EventHistory()) : HistoryWritableState<Advanced> {
        override fun withHistory(history: EventHistory<Advanced>) = copy(history = history)
    }
    private val player = PlayerId("player")
    private val definition = object : GameDefinition<State, Advance, Advanced> {
        override fun legalIntents(state: State) = if (state.value < 1) setOf(LegalIntent(Advance)) else emptySet()
        override fun resolve(state: State, actor: PlayerId, intent: Advance) = Resolution(listOf(Advanced(state.value + 1)))
        override fun reduce(state: State, event: Advanced) = state.copy(value = event.value, currentPlayer = null)
        override fun outcome(state: State) = if (state.value == 1) GameOutcome.PlayerWon(player) else GameOutcome.InProgress
    }

    @Test fun `engine validates reduces and records an intent`() {
        val result = GameEngine(definition).play(State(0, player), player, Advance)
        assertEquals(1, result.value)
        assertEquals(1, result.history.events.size)
        assertEquals(GameOutcome.PlayerWon(player), definition.outcome(result))
    }

    @Test fun `engine rejects the wrong actor`() {
        assertThrows<IllegalArgumentException> { GameEngine(definition).play(State(0, player), PlayerId("other"), Advance) }
    }
}
