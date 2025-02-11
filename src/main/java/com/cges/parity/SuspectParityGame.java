package com.cges.parity;

import static com.google.common.base.Preconditions.checkArgument;

import com.cges.graph.HistoryGame;
import com.cges.graph.SuspectGame;
import com.cges.graph.SuspectGame.EveState;
import com.cges.model.Agent;
import com.cges.model.Move;
import com.cges.model.Transition;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableTable;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Table;
import de.tum.in.naturals.Indices;
import de.tum.in.naturals.bitset.BitSets;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import owl.automaton.Automaton;
import owl.automaton.acceptance.ParityAcceptance;
import owl.automaton.edge.Edge;

public final class SuspectParityGame<S> implements ParityGame<PriorityState<S>> {
    private final SetMultimap<HistoryGame.HistoryState<S>, NonDeviationState<S>> historyStateMap;
    private final Table<NonDeviationState<S>, Move, Set<PriorityState<S>>> deviationSuccessors;
    private final SetMultimap<PriorityState<S>, PriorityState<S>> successors;

    private SuspectParityGame(SuspectGame<S> suspectGame, EveState<S> initialState,
                    Automaton<Object, ParityAcceptance> dpa) {
        assert !dpa.acceptance().parity().max();

        // We have a min even objective and want max + let eve be odd player
        int maximumPriority = dpa.acceptance().acceptanceSets();
        if (dpa.acceptance().isAccepting(maximumPriority)) {
            // Ensure that maximum priority is odd, so if priority is even then maximum
            // priority is odd
            maximumPriority += 1;
        }

        List<String> propositions = dpa.atomicPropositions();
        Object2IntMap<String> propositionIndex = Indices.ids(propositions);
        propositionIndex.defaultReturnValue(-1);
        Map<S, BitSet> gameStateLabelCache = new HashMap<>();
        Function<S, BitSet> gameStateLabels = s -> BitSets.copyOf(gameStateLabelCache.computeIfAbsent(s, state -> {
            BitSet set = new BitSet();
            suspectGame.historyGame().concurrentGame().labels(state).stream().mapToInt(propositionIndex::getInt)
                            .filter(i -> i >= 0).forEach(set::set);
            return set;
        }));

        Set<Agent> initialSuspects = initialState.suspects();
        BitSet allSuspectsLabel = new BitSet();
        initialSuspects.stream().map(Agent::name).mapToInt(propositionIndex::getInt).filter(i -> i >= 0)
                        .forEach(allSuspectsLabel::set);

        HistoryGame<S> historyGame = suspectGame.historyGame();
        Set<NonDeviationState<S>> nonDeviationStates = new HashSet<>(
                        List.of(new NonDeviationState<>(historyGame.initialState(), dpa.initialState())));
        Queue<NonDeviationState<S>> nonDeviationQueue = new ArrayDeque<>(nonDeviationStates);
        ImmutableSetMultimap.Builder<HistoryGame.HistoryState<S>, NonDeviationState<S>> historyStateMap = ImmutableSetMultimap
                        .builder();
        ImmutableTable.Builder<NonDeviationState<S>, Move, Set<PriorityState<S>>> deviationSuccessors = ImmutableTable
                        .builder();

        while (!nonDeviationQueue.isEmpty()) {
            var current = nonDeviationQueue.poll();
            historyStateMap.put(current.gameState(), current);

            var labels = gameStateLabels.apply(current.gameState.state());
            labels.or(allSuspectsLabel);
            assert dpa.edges(current.automatonState(), labels).size() == 1;
            Edge<Object> automatonEdge = dpa.edge(current.automatonState(), labels);
            assert automatonEdge != null;
            Object automatonSuccessor = automatonEdge.successor();
            historyGame.transitions(current.gameState).map(Transition::destination).forEach(historySuccessor -> {
                var successor = new NonDeviationState<>(historySuccessor, automatonSuccessor);
                if (nonDeviationStates.add(successor)) {
                    nonDeviationQueue.add(successor);
                }
            });

            int priority = maximumPriority - automatonEdge.colours().first().orElse(maximumPriority);
            EveState<S> eveState = new EveState<>(current.gameState(), initialSuspects);
            suspectGame.successors(eveState).forEach(adam -> deviationSuccessors.put(current, adam.move(),
                            suspectGame.deviationSuccessors(adam)
                                            .map(eve -> new PriorityState<S>(automatonSuccessor, eve, priority))
                                            .collect(Collectors.toSet())));
        }
        this.historyStateMap = historyStateMap.build();
        this.deviationSuccessors = deviationSuccessors.build();

        ImmutableSetMultimap.Builder<PriorityState<S>, PriorityState<S>> successors = ImmutableSetMultimap.builder();
        Set<PriorityState<S>> reached = this.deviationSuccessors.values().stream().flatMap(Collection::stream)
                        .collect(Collectors.toSet());
        Queue<PriorityState<S>> queue = new ArrayDeque<>(reached);

        while (!queue.isEmpty()) {
            var current = queue.poll();

            EveState<S> eveState = current.eve();
            BitSet label = gameStateLabels.apply(eveState.gameState());
            eveState.suspects().stream().map(Agent::name).mapToInt(propositionIndex::getInt).filter(i -> i >= 0)
                            .forEach(label::set);

            assert dpa.edges(current.automatonState(), label).size() == 1;
            Edge<Object> automatonEdge = dpa.edge(current.automatonState(), label);
            assert automatonEdge != null;
            int priority = maximumPriority - automatonEdge.colours().first().orElse(maximumPriority);

            suspectGame.successors(eveState).forEach(adam -> {
                var adamSuccessor = new PriorityState<S>(automatonEdge.successor(), adam, priority);
                successors.put(current, adamSuccessor);
                suspectGame.successors(adam).forEach(successor -> {
                    var eveSuccessor = new PriorityState<S>(automatonEdge.successor(), successor, 0);
                    successors.put(adamSuccessor, eveSuccessor);
                    if (reached.add(eveSuccessor)) {
                        queue.add(eveSuccessor);
                    }
                });
            });
        }
        this.successors = successors.build();
    }

    public static <S> SuspectParityGame<S> create(SuspectGame<S> suspectGame, EveState<S> eveState,
                    Automaton<Object, ParityAcceptance> dpa) {
        checkArgument(dpa.acceptance().parity().equals(ParityAcceptance.Parity.MIN_EVEN));
        return new SuspectParityGame<>(suspectGame, eveState, dpa);
    }

    @Override
    public Set<PriorityState<S>> states() {
        return successors.keySet();
    }

    @Override
    public Stream<PriorityState<S>> successors(PriorityState<S> current) {
        assert !successors.get(current).isEmpty() : "No successors in %s".formatted(current);
        return successors.get(current).stream();
    }

    @Override
    public int priority(PriorityState<S> state) {
        return state.priority();
    }

    @Override
    public Player owner(PriorityState<S> state) {
        return state.isEve() ? Player.ODD : Player.EVEN;
    }

    public Stream<PriorityState<S>> deviationStates(HistoryGame.HistoryState<S> historyState, Move move) {
        assert historyStateMap.containsKey(historyState);
        var states = historyStateMap.get(historyState);
        assert !states.isEmpty();
        return states.stream().map(s -> this.deviationSuccessors.get(s, move)).peek(Objects::requireNonNull)
                        .flatMap(Collection::stream);
    }

    private record NonDeviationState<S>(HistoryGame.HistoryState<S> gameState, Object automatonState) {
    }
}
