package vn.ptit.procon.engine;

/** Immutable event emitted by exact day execution. */
public sealed interface SimulationEvent permits
        MoveStartedEvent,
        MoveCompletedEvent,
        FuelConsumedEvent,
        WaitStepEvent,
        UdonCollectedEvent,
        RefueledEvent,
        DayCompletedEvent {

    int step();
}