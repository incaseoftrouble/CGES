package com.cges.graph;

import com.cges.graph.HistoryGame.HistoryState;
import com.cges.model.Agent;
import com.cges.model.Move;
import com.cges.model.Transition;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class SuspectGame<S> {
  public record SuspectTransition<S>(AdamState<S> adamState, EveState<S> eveSuccessor) {
    SuspectTransition(Map.Entry<AdamState<S>, EveState<S>> entry) {
      this(entry.getKey(), entry.getValue());
    }
  }

  private final HistoryGame<S> game;
  private final EveState<S> initialState;
  private final Map<EveState<S>, Map<AdamState<S>, Set<EveState<S>>>> transitions = new HashMap<>();

  public SuspectGame(HistoryGame<S> game) {
    this.game = game;
    this.initialState = new EveState<>(game.initialState(), Set.copyOf(game.concurrentGame().agents()));
  }

  public EveState<S> initialState() {
    return initialState;
  }

  public Stream<SuspectTransition<S>> transitions(EveState<S> eveState) {
    return transitions.computeIfAbsent(eveState, this::computeSuccessorMap).entrySet().stream()
        .flatMap(entry -> entry.getValue().stream().map(eve -> new SuspectTransition<>(entry.getKey(), eve)));
  }

  public Stream<AdamState<S>> successors(EveState<S> eveState) {
    return Set.copyOf(transitions.computeIfAbsent(eveState, this::computeSuccessorMap).keySet()).stream();
  }

  public Stream<EveState<S>> successors(AdamState<S> adamState) {
    return transitions.computeIfAbsent(adamState.eveState(), this::computeSuccessorMap).get(adamState).stream();
  }

  public Stream<EveState<S>> eveSuccessors(EveState<S> eveState) {
    return transitions.computeIfAbsent(eveState, this::computeSuccessorMap).values().stream().flatMap(Collection::stream);
  }

  private Map<AdamState<S>, Set<EveState<S>>> computeSuccessorMap(EveState<S> eveState) {
    var gameState = eveState.historyState();

    if (eveState.suspects().isEmpty()) {
      return game.transitions(gameState).collect(Collectors.toUnmodifiableMap(
          t -> new AdamState<>(eveState, Set.of(t.move())),
          t -> Set.of(new EveState<>(t.destination(), Set.of()))
      ));
    }

    var nonSuspects = Set.copyOf(Sets.difference(game.concurrentGame().agents(), eveState.suspects()));

    var movesToSuccessors = game.transitions(eveState.historyState())
        .collect(ImmutableSetMultimap.toImmutableSetMultimap(Transition::destination, Transition::move));
    assert movesToSuccessors.entries().stream().anyMatch(e ->
        game.transition(eveState.historyState(), e.getValue()).orElseThrow().destination().equals(e.getKey()));

    // TODO This is somewhat inefficient, but smaller than the subsequent algorithmic problems
    // Which set of states can we reach by deviating from the move
    SetMultimap<Set<EveState<S>>, Move> reachableByDeviation = HashMultimap.create();

    game.transitions(gameState).forEach(proposedTransition -> {
      // Eve proposes this transition -- adam can either comply or change the choice of one suspect
      Move proposedMove = proposedTransition.move();
      Collection<Agent> currentSuspects = eveState.suspects();
      assert !currentSuspects.isEmpty();
      assert movesToSuccessors.get(proposedTransition.destination()).contains(proposedMove);

      Set<EveState<S>> deviationSuccessors = new HashSet<>();
      // Check for each successor if we can reach it by a single deviation
      game.transitions(eveState.historyState()).map(Transition::destination).forEach(alternativeSuccessor -> {
        Set<Move> movesLeadingToAlternative = movesToSuccessors.get(alternativeSuccessor);
        assert !alternativeSuccessor.equals(proposedTransition.destination()) || movesLeadingToAlternative.contains(proposedMove);

        Collection<Agent> successorSuspects;
        if (movesLeadingToAlternative.contains(proposedMove)) {
          // Nobody needs to deviate to achieve this move
          successorSuspects = currentSuspects;
        } else {
          successorSuspects = new HashSet<>();
          for (Move move : movesLeadingToAlternative) {
            if (nonSuspects.stream().allMatch(a -> move.action(a).equals(proposedMove.action(a)))) {
              // Check if there is a single suspect who could deviate to achieve this move (i.e. move to the successor)
              var deviatingAgents = currentSuspects.stream().filter(a -> !move.action(a).equals(proposedMove.action(a))).iterator();
              Agent deviating = null;
              if (deviatingAgents.hasNext()) {
                var first = deviatingAgents.next();
                if (!deviatingAgents.hasNext()) {
                  deviating = first;
                }
              }
              if (deviating != null) {
                successorSuspects.add(deviating);
              }
              assert (game.concurrentGame().agents().stream().filter(a -> !move.action(a).equals(proposedMove.action(a))).count() == 1)
                  == (deviating != null);
            }
          }
        }
        assert currentSuspects.containsAll(successorSuspects);
        if (!successorSuspects.isEmpty()) {
          deviationSuccessors.add(new EveState<>(alternativeSuccessor, Set.copyOf(successorSuspects)));
        }
      });

      assert deviationSuccessors.stream().map(EveState::historyState).anyMatch(proposedTransition.destination()::equals);
      reachableByDeviation.put(Set.copyOf(deviationSuccessors), proposedTransition.move());
    });

    Map<AdamState<S>, Set<EveState<S>>> transitions = reachableByDeviation.asMap().entrySet().stream()
        .collect(Collectors.toUnmodifiableMap(e -> new AdamState<>(eveState, Set.copyOf(e.getValue())), Map.Entry::getKey));
    assert transitions.values().stream()
        .flatMap(Collection::stream)
        .map(EveState::suspects)
        .flatMap(Collection::stream)
        .collect(Collectors.toSet()).equals(eveState.suspects()) : "Vanishing suspects";
    assert transitions.values().stream()
        .flatMap(Collection::stream)
        .map(EveState::historyState)
        .collect(Collectors.toSet())
        .containsAll(game.transitions(eveState.historyState()).map(Transition::destination).collect(Collectors.toSet())) :
        "Missing successors";
    return Map.copyOf(transitions);
  }

  public HistoryGame<S> historyGame() {
    return game;
  }

  public static final class AdamState<S> {
    private final EveState<S> eveState;
    private final Set<Move> moves;
    private final int hashCode;

    public AdamState(EveState<S> eveState, Set<Move> moves) {
      this.eveState = eveState;
      this.moves = Set.copyOf(moves);
      this.hashCode = eveState.hashCode() ^ Set.copyOf(moves).hashCode();
    }

    @Override
    public String toString() {
      return "AS[%s]@[%s]".formatted(eveState.historyState(), moves);
    }

    public EveState<S> eveState() {
      return eveState;
    }

    public Set<Move> moves() {
      return moves;
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this
          || (obj instanceof SuspectGame.AdamState<?> that
          && hashCode == that.hashCode
          && eveState.equals(that.eveState)
          && moves.equals(that.moves));
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }

  public static final class EveState<S> {
    private final HistoryState<S> historyState;
    private final Set<Agent> suspects;
    private final int hashCode;

    public EveState(HistoryState<S> historyState, Set<Agent> suspects) {
      this.historyState = historyState;
      this.suspects = Set.copyOf(suspects);
      this.hashCode = this.historyState.hashCode() ^ this.suspects.hashCode();
    }

    public S gameState() {
      return historyState.state();
    }

    @Override
    public String toString() {
      return "ES[%s]{%s}".formatted(historyState, suspects.stream().map(Agent::name).sorted().collect(Collectors.joining(",")));
    }

    public HistoryState<S> historyState() {
      return historyState;
    }

    public Set<Agent> suspects() {
      return suspects;
    }

    @Override
    public boolean equals(Object obj) {
      return (this == obj)
          || (obj instanceof EveState<?> that
          && hashCode == that.hashCode
          && suspects.equals(that.suspects)
          && historyState.equals(that.historyState));
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }
}
