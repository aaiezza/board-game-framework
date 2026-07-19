package games.core

@JvmInline value class GameId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline value class PlayerId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline value class TeamId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline value class TurnNumber(val value: Int) {
    init {
        require(value >= 0)
    }
}

interface PlayerIntent
interface GameEvent
interface TurnPhase
interface GameConfiguration {
    val name: String
}

sealed interface GameOutcome {
    data object InProgress : GameOutcome
    data class PlayerWon(val playerId: PlayerId) : GameOutcome
    data class TeamWon(val teamId: TeamId) : GameOutcome
    data object Draw : GameOutcome
    data object PlayersWonCooperatively : GameOutcome
    data object OppositionWon : GameOutcome
}

sealed interface LegalityReason {
    data object Available : LegalityReason
    data class Required(val explanation: String) : LegalityReason
}

data class LegalIntent<I : PlayerIntent>(val intent: I, val reason: LegalityReason = LegalityReason.Available)
data class RecordedEvent<E : GameEvent>(val turn: TurnNumber, val actor: PlayerId?, val event: E)
data class EventHistory<E : GameEvent>(val events: List<RecordedEvent<E>> = emptyList()) {
    fun record(turn: TurnNumber, actor: PlayerId?, newEvents: List<E>) = copy(events = events + newEvents.map { RecordedEvent(turn, actor, it) })
}

interface GameState<E : GameEvent> {
    val turnNumber: TurnNumber
    val currentPlayer: PlayerId?
    val history: EventHistory<E>
}

interface PhasedGameState<E : GameEvent, P : TurnPhase> : GameState<E> {
    val phase: P
}
interface BoardGameState<E : GameEvent, B> : GameState<E> {
    val board: B
}
interface PlayerRegistry {
    val players: Set<PlayerId>
}

data class Resolution<E : GameEvent>(val events: List<E>) {
    init {
        require(events.isNotEmpty())
    }
}

interface GameDefinition<S : GameState<E>, I : PlayerIntent, E : GameEvent> {
    fun legalIntents(state: S): Set<LegalIntent<I>>
    fun resolve(state: S, actor: PlayerId, intent: I): Resolution<E>
    fun reduce(state: S, event: E): S
    fun outcome(state: S): GameOutcome
}

interface ConfigurableGameDefinition<S : GameState<E>, I : PlayerIntent, E : GameEvent, C : GameConfiguration> : GameDefinition<S, I, E> {
    val configuration: C
}

class GameEngine<S : GameState<E>, I : PlayerIntent, E : GameEvent>(private val definition: GameDefinition<S, I, E>) {
    fun play(state: S, actor: PlayerId, intent: I): S {
        require(definition.outcome(state) == GameOutcome.InProgress) { "The game has ended" }
        require(state.currentPlayer == actor) { "It is not $actor's turn" }
        require(definition.legalIntents(state).any { it.intent == intent }) { "Intent is not legal: $intent" }
        val resolution = definition.resolve(state, actor, intent)
        val reduced = resolution.events.fold(state) { next, event -> definition.reduce(next, event) }
        @Suppress("UNCHECKED_CAST")
        return when (reduced) {
            is HistoryWritableState<*> -> (reduced as HistoryWritableState<E>).withHistory(reduced.history.record(state.turnNumber, actor, resolution.events)) as S
            else -> reduced
        }
    }
}

interface HistoryWritableState<E : GameEvent> : GameState<E> {
    fun withHistory(history: EventHistory<E>): HistoryWritableState<E>
}

interface BoardTopology<C> {
    fun contains(coordinate: C): Boolean
    fun neighbors(coordinate: C): Set<C>
}

interface RuleModule<S, I : PlayerIntent> {
    fun validate(state: S, intent: I): String?
}
