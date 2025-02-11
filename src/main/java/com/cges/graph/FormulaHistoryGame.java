package com.cges.graph;

import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.Transition;
import com.google.common.collect.Lists;
import de.tum.in.naturals.Indices;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import owl.ltl.Formula;
import owl.ltl.rewriter.SimplifierRepository;

public class FormulaHistoryGame<S> implements HistoryGame<S> {
    private final Object2IntMap<Agent> indices;

    static final class ListHistoryState<S> implements HistoryState<S> {
        private static final Formula[] EMPTY = new Formula[0];

        private final S state;
        private final Formula[] agentGoals;
        private final FormulaHistoryGame<S> game;
        private final int hashCode;

        ListHistoryState(S state, List<Formula> agentGoals, FormulaHistoryGame<S> game) {
            this.state = state;
            this.agentGoals = agentGoals.toArray(EMPTY);
            this.game = game;
            this.hashCode = this.state.hashCode() ^ Arrays.hashCode(this.agentGoals);
        }

        @Override
        public Formula goal(Agent agent) {
            return agentGoals[game.indices.getInt(agent)];
        }

        @Override
        public HistoryGame<S> game() {
            return game;
        }

        @Override
        public S state() {
            return state;
        }

        public List<Formula> goals() {
            return Arrays.asList(agentGoals);
        }

        @Override
        public boolean equals(Object obj) {
            return obj == this
                    || (obj instanceof ListHistoryState<?> that
                            && hashCode == that.hashCode
                            && state.equals(that.state)
                            && Arrays.equals(agentGoals, that.agentGoals));
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public String toString() {
            return state + " "
                    + game.indices.object2IntEntrySet().stream()
                            .sorted(Map.Entry.comparingByKey(Comparator.comparing(Agent::name)))
                            .map(Map.Entry::getValue)
                            .map(i -> agentGoals[i])
                            .map(SimplifierRepository.SYNTACTIC_FIXPOINT::apply)
                            .map(Objects::toString)
                            .collect(Collectors.joining(",", "[", "]"));
        }
    }

    private final ConcurrentGame<S> game;
    private final ListHistoryState<S> initialState;
    private final Map<HistoryState<S>, Set<Transition<HistoryState<S>>>> transitions;

    public FormulaHistoryGame(ConcurrentGame<S> game) {
        this.game = game;

        var propositionIndices = Indices.ids(game.atomicPropositions());
        indices = Indices.ids(game.agents());

        this.initialState = new ListHistoryState<>(
                game.initialState(),
                game.agents().stream().map(Agent::goal).map(Formula::unfold).toList(),
                this);

        Map<HistoryState<S>, Set<Transition<HistoryState<S>>>> transitions = new HashMap<>();
        Set<ListHistoryState<S>> states = new HashSet<>(List.of(initialState));
        Queue<ListHistoryState<S>> queue = new ArrayDeque<>(states);
        Map<S, BitSet> labelCache = new HashMap<>();
        while (!queue.isEmpty()) {
            ListHistoryState<S> state = queue.poll();

            BitSet valuation = labelCache.computeIfAbsent(state.state(), s -> {
                BitSet set = new BitSet();
                game.labels(s).stream().map(propositionIndices::getInt).forEach(set::set);
                return set;
            });
            List<Formula> successorGoals = List.copyOf(Lists.transform(
                    state.goals(), goal -> goal.temporalStep(valuation).unfold()));
            Set<Transition<HistoryState<S>>> stateTransitions = new HashSet<>();
            for (Transition<S> transition : game.transitions(state.state())) {
                ListHistoryState<S> successor = new ListHistoryState<>(transition.destination(), successorGoals, this);
                stateTransitions.add(transition.withDestination(successor));
                if (states.add(successor)) {
                    queue.add(successor);
                }
            }
            transitions.put(state, Set.copyOf(stateTransitions));
        }
        this.transitions = Map.copyOf(transitions);
    }

    @Override
    public ListHistoryState<S> initialState() {
        return initialState;
    }

    @Override
    public Stream<Transition<HistoryState<S>>> transitions(HistoryState<S> state) {
        assert transitions.containsKey(state);
        return transitions.get(state).stream();
    }

    @Override
    public ConcurrentGame<S> concurrentGame() {
        return game;
    }
}
