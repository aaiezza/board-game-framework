package games.core

@JvmInline
value class GameId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class PlayerId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class TeamId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
value class TurnNumber(val value: Int) {
    init {
        require(value >= 0)
    }
}

@JvmInline
value class RuleId(val value: String) {
    init {
        require(value.isNotBlank())
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

data class TurnContext(
    val owner: PlayerId,
    val decisionActors: Set<PlayerId> = setOf(owner),
) {
    init {
        require(decisionActors.isNotEmpty())
    }
}

sealed interface TransitionCause {
    data class PlayerDriven(val actor: PlayerId, val intent: PlayerIntent) : TransitionCause
    data class RuleDriven(val rule: RuleId) : TransitionCause
}

data class RecordedEvent<E : GameEvent>(
    val turn: TurnNumber,
    val turnOwner: PlayerId?,
    val cause: TransitionCause,
    val event: E,
) {
    val actor: PlayerId? get() = (cause as? TransitionCause.PlayerDriven)?.actor
}

data class RecordedTransition<E : GameEvent>(
    val turn: TurnNumber,
    val turnOwner: PlayerId?,
    val cause: TransitionCause,
    val events: List<E>,
)

data class EventHistory<E : GameEvent>(val transitions: List<RecordedTransition<E>> = emptyList()) {
    val events: List<RecordedEvent<E>> get() = transitions.flatMap { transition ->
        transition.events.map { event ->
            RecordedEvent(transition.turn, transition.turnOwner, transition.cause, event)
        }
    }

    fun record(
        turn: TurnNumber,
        turnOwner: PlayerId?,
        cause: TransitionCause,
        newEvents: List<E>,
    ) = copy(transitions = transitions + RecordedTransition(turn, turnOwner, cause, newEvents))

    fun <S> states(initialState: S, reduce: (S, E) -> S): List<S> = transitions.runningFold(initialState) { state, transition ->
        transition.events.fold(state, reduce)
    }
}

interface GameState<E : GameEvent> {
    val turn: TurnContext?
    val turnNumber: TurnNumber
    val history: EventHistory<E>
    val turnOwner: PlayerId? get() = turn?.owner
    val decisionActors: Set<PlayerId> get() = turn?.decisionActors.orEmpty()
    val currentPlayer: PlayerId? get() = decisionActors.singleOrNull()
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

sealed interface ResolutionStep<E : GameEvent> {
    val events: List<E>

    data class PlayerDriven<E : GameEvent>(override val events: List<E>) : ResolutionStep<E> {
        init {
            require(events.isNotEmpty())
        }
    }

    data class RuleDriven<E : GameEvent>(
        val rule: RuleId,
        override val events: List<E>,
    ) : ResolutionStep<E> {
        init {
            require(events.isNotEmpty())
        }
    }
}

data class Resolution<E : GameEvent>(val steps: List<ResolutionStep<E>>) {
    init {
        require(steps.isNotEmpty())
        require(steps.first() is ResolutionStep.PlayerDriven)
        require(steps.drop(1).all { it is ResolutionStep.RuleDriven })
    }

    companion object {
        fun <E : GameEvent> playerDriven(vararg events: E) = Resolution(listOf(ResolutionStep.PlayerDriven(events.toList())))
    }
}

data class TransitionStep<S, E : GameEvent>(
    val cause: TransitionCause,
    val events: List<E>,
    val resultingState: S,
)

data class Progression<S, E : GameEvent>(
    val initialState: S,
    val steps: List<TransitionStep<S, E>>,
) {
    init {
        require(steps.isNotEmpty())
    }

    val resultingState: S get() = steps.last().resultingState
}

interface GameDefinition<S : GameState<E>, I : PlayerIntent, E : GameEvent> {
    fun legalIntents(state: S): Set<LegalIntent<I>>
    fun legalIntents(state: S, actor: PlayerId): Set<LegalIntent<I>> = if (actor in state.decisionActors) legalIntents(state) else emptySet()

    fun resolve(state: S, actor: PlayerId, intent: I): Resolution<E>
    fun reduce(state: S, event: E): S
    fun outcome(state: S): GameOutcome
}

interface ConfigurableGameDefinition<S : GameState<E>, I : PlayerIntent, E : GameEvent, C : GameConfiguration> : GameDefinition<S, I, E> {
    val configuration: C
}

class GameEngine<S : GameState<E>, I : PlayerIntent, E : GameEvent>(private val definition: GameDefinition<S, I, E>) {
    fun play(state: S, actor: PlayerId, intent: I): S = playWithTrace(state, actor, intent).resultingState

    fun playWithTrace(state: S, actor: PlayerId, intent: I): Progression<S, E> {
        require(definition.outcome(state) == GameOutcome.InProgress) { "The game has ended" }
        require(actor in state.decisionActors) { "$actor is not eligible to act" }
        require(definition.legalIntents(state, actor).any { it.intent == intent }) { "Intent is not legal: $intent" }

        val resolution = definition.resolve(state, actor, intent)
        var nextState = state
        val trace = resolution.steps.map { step ->
            val cause = when (step) {
                is ResolutionStep.PlayerDriven -> TransitionCause.PlayerDriven(actor, intent)
                is ResolutionStep.RuleDriven -> TransitionCause.RuleDriven(step.rule)
            }
            val startingTurn = nextState.turnNumber
            val startingTurnOwner = nextState.turnOwner
            nextState = step.events.fold(nextState, definition::reduce)
            nextState = nextState.withRecordedHistory(startingTurn, startingTurnOwner, cause, step.events)
            TransitionStep(cause, step.events, nextState)
        }
        check(definition.outcome(nextState) != GameOutcome.InProgress || nextState.decisionActors.isNotEmpty()) {
            "Resolution stopped before reaching a decision or terminal state"
        }
        return Progression(state, trace)
    }

    @Suppress("UNCHECKED_CAST")
    private fun S.withRecordedHistory(turn: TurnNumber, turnOwner: PlayerId?, cause: TransitionCause, events: List<E>): S = when (this) {
        is HistoryWritableState<*> ->
            (this as HistoryWritableState<E>).withHistory(history.record(turn, turnOwner, cause, events)) as S

        else -> this
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
