package com.cges.algorithm;

import static com.google.common.base.Preconditions.checkArgument;

import com.cges.algorithm.HistoryGame.HistoryState;
import com.cges.algorithm.SuspectGame.EveState;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.PayoffAssignment;
import com.cges.parity.OinkGameSolver;
import com.cges.parity.Player;
import com.cges.parity.PriorityState;
import com.cges.parity.SuspectParityGame;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import owl.automaton.Automaton;
import owl.automaton.ParityUtil;
import owl.automaton.acceptance.ParityAcceptance;
import owl.ltl.BooleanConstant;
import owl.ltl.Conjunction;
import owl.ltl.Disjunction;
import owl.ltl.GOperator;
import owl.ltl.LabelledFormula;
import owl.ltl.Literal;
import owl.ltl.rewriter.LiteralMapper;
import owl.ltl.rewriter.SimplifierRepository;
import owl.translations.LtlTranslationRepository;
import owl.translations.LtlTranslationRepository.Option;

public final class DeviationSolver<S> {
  private static final boolean CROSS_VALIDATE = false;

  private final Function<LabelledFormula, Automaton<?, ? extends ParityAcceptance>> dpaFunction =
      /* LtlTranslationRepository.defaultTranslation(EnumSet.of(Option.COMPLETE, Option.SIMPLIFY_AUTOMATON),
          BranchingMode.DETERMINISTIC, ParityAcceptance.class); */
      LtlTranslationRepository.LtlToDpaTranslation.SEJK16_EKRS17.translation(EnumSet.of(Option.SIMPLIFY_AUTOMATON));

  private final SuspectGame<S> suspectGame;
  // TODO Make caching work on unlabelled formulas?
  private final Map<LabelledFormula, Automaton<?, ?>> automatonCache = new HashMap<>();
  private final OinkGameSolver solver = new OinkGameSolver();
  private final Map<Agent, Literal> agentLiterals;
  private final List<String> atomicPropositions;
  private final Set<Agent> losingAgents;
  private final Map<HistoryState<S>, PunishmentStrategy<S>> cache = new HashMap<>();

  public DeviationSolver(SuspectGame<S> suspectGame, PayoffAssignment payoff) {
    this.suspectGame = suspectGame;

    HistoryGame<S> historyGame = suspectGame.historyGame();
    ConcurrentGame<S> concurrentGame = historyGame.concurrentGame();
    losingAgents = concurrentGame.agents().stream().filter(payoff::isLoser).collect(Collectors.toSet());
    atomicPropositions = Stream.concat(concurrentGame.atomicPropositions().stream(), losingAgents.stream().map(Agent::name)).toList();
    agentLiterals = losingAgents.stream().collect(Collectors.toMap(Function.identity(),
        a -> Literal.of(atomicPropositions.indexOf(a.name()))));
    assert Set.copyOf(atomicPropositions).size() == atomicPropositions.size();
  }

  public Optional<PunishmentStrategy<S>> solve(HistoryState<S> historyState) {
    if (cache.containsKey(historyState)) {
      return Optional.ofNullable(cache.get(historyState));
    }

    LabelledFormula eveGoal = LabelledFormula.of(Disjunction.of(losingAgents.stream()
        .map(a -> Conjunction.of(GOperator.of(agentLiterals.get(a)), historyState.goal(a)))).not(), atomicPropositions);

    EveState<S> eveState = new EveState<>(historyState, losingAgents);

    var goal = SimplifierRepository.SYNTACTIC_FAIRNESS.apply(eveGoal);
    if (goal.formula() instanceof BooleanConstant bool) {
      if (bool.value) {
        PriorityState<S> state = new PriorityState<>(null, eveState, 0);
        return Optional.of(new Strategy<>(state, Map.of(state, state)));
      }
      return Optional.empty();
    }

    var shifted = LiteralMapper.shiftLiterals(goal);
    @SuppressWarnings("unchecked")
    var automaton = (Automaton<Object, ParityAcceptance>) automatonCache.computeIfAbsent(shifted.formula,
        formula -> ParityUtil.convert(dpaFunction.apply(formula), ParityAcceptance.Parity.MIN_EVEN));
    if (automaton.states().isEmpty()) {
      return Optional.empty();
    }

    var parityGame = SuspectParityGame.create(suspectGame, eveState, automaton);
    var paritySolution = solver.solve(parityGame);

    assert !CROSS_VALIDATE || paritySolution.winner(parityGame.initialState()).equals(((Supplier<Player>) () -> {
      var defaultTranslation = LtlTranslationRepository.LtlToDpaTranslation.DEFAULT.translation(EnumSet.of(Option.COMPLETE));
      @SuppressWarnings("unchecked")
      var otherGame = SuspectParityGame.create(suspectGame, eveState, (Automaton<Object, ParityAcceptance>)
          ParityUtil.convert(defaultTranslation.apply(shifted.formula), ParityAcceptance.Parity.MIN_EVEN));
      var otherSolution = solver.solve(otherGame);
      return otherSolution.winner(otherGame.initialState());
    }).get());


    if (paritySolution.winner(parityGame.initialState()) == Player.EVEN) {
      return Optional.empty();
    }

    Queue<PriorityState<S>> queue = new ArrayDeque<>(List.of(parityGame.initialState()));
    Set<PriorityState<S>> reached = new HashSet<>();
    Map<PriorityState<S>, PriorityState<S>> strategy = new HashMap<>();
    while (!queue.isEmpty()) {
      PriorityState<S> next = queue.poll();
      assert paritySolution.winner(next) == Player.ODD;
      Stream<PriorityState<S>> successors;
      if (parityGame.owner(next) == Player.EVEN) {
        successors = parityGame.successors(next);
      } else {
        PriorityState<S> successor = paritySolution.oddStrategy().get(next);
        strategy.put(next, successor);
        successors = Stream.of(successor);
      }
      successors.forEach(successor -> {
        if (reached.add(successor)) {
          queue.add(successor);
        }
      });
    }
    var strategyCopy = Map.copyOf(strategy);
    for (PriorityState<S> priorityState : reached) {
      if (priorityState.isEve() && priorityState.eve().suspects().equals(losingAgents)) {
        HistoryState<S> gameHistoryState = priorityState.eve().historyState();
        if (paritySolution.winner(priorityState) == Player.ODD) {
          assert !cache.containsKey(gameHistoryState) || cache.get(gameHistoryState) != null;
          cache.putIfAbsent(gameHistoryState, new Strategy<>(priorityState, strategyCopy));
        } else {
          assert !cache.containsKey(gameHistoryState) || cache.get(gameHistoryState) == null;
          cache.putIfAbsent(gameHistoryState, null);
        }
      }
    }
    return Optional.of(new Strategy<>(parityGame.initialState(), strategyCopy));
  }

  public interface PunishmentStrategy<S> {
    PriorityState<S> initialState();

    PriorityState<S> move(PriorityState<S> state);
  }

  record Strategy<S>(PriorityState<S> initialState, Map<PriorityState<S>, PriorityState<S>> strategies) implements PunishmentStrategy<S> {
    @Override
    public PriorityState<S> move(PriorityState<S> state) {
      checkArgument(strategies.containsKey(state));
      return strategies.get(state);
    }
  }
}
