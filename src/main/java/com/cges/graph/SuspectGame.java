package com.cges.graph;

import com.cges.graph.HistoryGame.HistoryState;
import com.cges.model.Agent;
import com.cges.model.Move;
import com.cges.model.Transition;
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
  private final Map<EveState<S>, SetMultimap<AdamState<S>, EveState<S>>> transitions = new HashMap<>();

  public SuspectGame(HistoryGame<S> game) {
    this.game = game;
    this.initialState = new EveState<>(game.initialState(), Set.copyOf(game.concurrentGame().agents()));
  }

  public EveState<S> initialState() {
    return initialState;
  }

  public Stream<SuspectTransition<S>> transitions(EveState<S> eveState) {
    return transitions.computeIfAbsent(eveState, this::computeSuccessorMap).entries().stream().map(SuspectTransition::new);
  }

  public Stream<AdamState<S>> successors(EveState<S> eveState) {
    return Set.copyOf(transitions.computeIfAbsent(eveState, this::computeSuccessorMap).keySet()).stream();
  }

  public Stream<EveState<S>> successors(AdamState<S> adamState) {
    return transitions.computeIfAbsent(adamState.eveState(), this::computeSuccessorMap).get(adamState).stream();
  }

  public Stream<EveState<S>> eveSuccessors(EveState<S> eveState) {
    return transitions.computeIfAbsent(eveState, this::computeSuccessorMap).values().stream();
  }

  private SetMultimap<AdamState<S>, EveState<S>> computeSuccessorMap(EveState<S> eveState) {
    var gameState = eveState.historyState();
    var movesToSuccessors = game.transitions(eveState.historyState())
        .collect(ImmutableSetMultimap.toImmutableSetMultimap(Transition::destination, Transition::move));
    var nonSuspects = Set.copyOf(Sets.difference(game.concurrentGame().agents(), eveState.suspects()));

    // TODO This is somewhat inefficient, but this is much smaller than the subsequent algorithmic problems
    ImmutableSetMultimap.Builder<AdamState<S>, EveState<S>> transitions = ImmutableSetMultimap.builder();
    game.transitions(gameState).forEach(transition -> {
      var adamSuccessor = new AdamState<>(eveState, transition.move());
      Collection<Agent> currentSuspects = eveState.suspects();

      game.transitions(eveState.historyState()).map(Transition::destination).distinct().forEach(historySuccessor -> {
        Set<Move> gameMoves = movesToSuccessors.get(historySuccessor);
        Collection<Agent> successorSuspects;
        if (gameMoves.contains(adamSuccessor.move())) {
          // Nobody needs to deviate to achieve this move
          successorSuspects = currentSuspects;
        } else {
          successorSuspects = new HashSet<>();
          for (Move move : gameMoves) {
            if (nonSuspects.stream().allMatch(a -> move.action(a).equals(adamSuccessor.move().action(a)))) {
              // Check if there is a single suspect who could deviate to achieve this move (i.e. move to the successor)
              var deviatingAgents = currentSuspects.stream()
                  .filter(a -> !adamSuccessor.move().action(a).equals(move.action(a)))
                  .iterator();
              if (deviatingAgents.hasNext()) {
                Agent deviating = deviatingAgents.next();
                if (!deviatingAgents.hasNext()) {
                  successorSuspects.add(deviating);
                }
              }
            }
          }
        }
        assert currentSuspects.containsAll(successorSuspects);

        if (!successorSuspects.isEmpty()) {
          transitions.put(adamSuccessor, new EveState<>(historySuccessor, Set.copyOf(successorSuspects)));
        }
      });
    });

    var built = transitions.build();
    assert built.values().stream()
        .map(EveState::suspects)
        .flatMap(Collection::stream)
        .collect(Collectors.toSet()).equals(eveState.suspects()) : "Vanishing suspects";
    assert built.values().stream()
        .map(EveState::historyState)
        .collect(Collectors.toSet())
        .containsAll(game.transitions(eveState.historyState()).map(Transition::destination).collect(Collectors.toSet()));
    return built;
  }

  public HistoryGame<S> historyGame() {
    return game;
  }

  public static final class AdamState<S> {
    private final EveState<S> eveState;
    private final Move move;
    private final int hashCode;

    public AdamState(EveState<S> eveState, Move move) {
      this.eveState = eveState;
      this.move = move;
      this.hashCode = eveState.hashCode() ^ move.hashCode();
    }

    @Override
    public String toString() {
      return "AS[%s]@[%s]".formatted(eveState.historyState(), move);
    }

    public EveState<S> eveState() {
      return eveState;
    }

    public Move move() {
      return move;
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this
          || (obj instanceof SuspectGame.AdamState<?> that
          && hashCode == that.hashCode
          && eveState.equals(that.eveState)
          && move.equals(that.move));
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
