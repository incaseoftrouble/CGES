package com.cges.model;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import owl.ltl.Formula;
import owl.ltl.LabelledFormula;
import owl.ltl.parser.LtlParser;

public final class ConcurrentGame {
  private static final Pattern COMMA_PATTERN = Pattern.compile(" *, *");
  private static final Pattern SPACE_PATTERN = Pattern.compile(" +");

  public static ConcurrentGame parse(Stream<String> lines) {
    Iterator<String> iterator = lines.iterator();
    checkArgument(iterator.next().equals("agent"));
    List<String[]> agentStrings = new ArrayList<>();
    while (true) {
      String line = iterator.next();
      if (line.equals("arena")) {
        break;
      }
      agentStrings.add(COMMA_PATTERN.split(line));
    }
    Set<State> states = new HashSet<>();
    Multimap<State, Transition> transitions = HashMultimap.create();
    State initialState = new State(iterator.next());
    List<String[]> stateStrings = new ArrayList<>();
    while (iterator.hasNext()) {
      String[] stateData = COMMA_PATTERN.split(iterator.next());
      stateStrings.add(stateData);
      State state = new State(stateData[0]);
      states.add(state);
    }
    List<String> propositions = states.stream().map(State::name).sorted().toList();
    List<Agent> agents = new ArrayList<>();
    for (String[] agentData : agentStrings) {
      String name = agentData[0];
      LabelledFormula goal = LtlParser.parse(agentData[1], propositions);
      checkArgument(Set.of("0", "1").contains(agentData[2]), "Expected 0/1, got %s", agentData[2]);
      boolean payoff = agentData[2].equals("1");
      List<Action> actions = Arrays.asList(agentData).subList(3, agentData.length).stream().map(Action::new).toList();
      agents.add(new Agent(name, goal.formula(), payoff, actions));
    }
    for (String[] stateData : stateStrings) {
      State state = new State(stateData[0]);
      for (String transition : Arrays.asList(stateData).subList(1, stateData.length)) {
        String[] transitionData = SPACE_PATTERN.split(transition);
        checkArgument(transitionData.length == agents.size() + 1);
        String destination = transitionData[transitionData.length - 1];

        List<List<Action>> actions = new ArrayList<>(agents.size());
        for (int i = 0; i < transitionData.length - 1; i++) {
          Agent agent = agents.get(i);
          if (transitionData[i].equals("*")) {
            actions.add(List.copyOf(agent.actions()));
          } else {
            actions.add(List.of(agent.actionByName(transitionData[i])));
          }
        }

        for (List<Action> pureMove : Lists.cartesianProduct(actions)) {
          Map<Agent, Action> transitionActions = IntStream.range(0, agents.size())
              .boxed().collect(Collectors.toMap(agents::get, pureMove::get));
          transitions.put(state, new Transition(new Move(transitionActions), new State(destination)));
        }
      }
    }

    return new ConcurrentGame(agents, transitions, states, initialState, propositions);
  }

  private final List<Agent> agents;
  private final Map<String, Agent> agentsByName;
  private final Multimap<State, Transition> transitions;
  private final State initialState;
  private final Set<State> states;
  private final List<String> formulaPropositions;

  private ConcurrentGame(List<Agent> agents, Multimap<State, Transition> transitions, Set<State> states, State initialState,
      List<String> formulaPropositions) {
    assert states.containsAll(transitions.keys());
    assert transitions.values().stream().map(Transition::destination).allMatch(states::contains);
    assert states.contains(initialState);

    this.agents = agents;
    this.formulaPropositions = formulaPropositions;
    this.transitions = transitions;
    this.states = Set.copyOf(states);
    this.agentsByName = agents.stream().collect(Collectors.toMap(Agent::name, a -> a));
    this.initialState = initialState;
  }

  public State initialState() {
    return initialState;
  }

  public Set<Agent> agents() {
    return Set.copyOf(agents);
  }

  public List<String> formulaPropositions() {
    return formulaPropositions;
  }

  public Agent agent(String name) {
    return Objects.requireNonNull(agentsByName.get(name));
  }

  public Collection<State> states() {
    return states;
  }

  public Collection<Transition> transitions(State gameState) {
    return transitions.get(gameState);
  }


  public record Action(String name) {
    @Override
    public String toString() {
      return name;
    }
  }

  public record Move(Map<Agent, Action> actions) {
    public Action action(Agent agent) {
      return actions.get(agent);
    }

    // TODO Can replace the map with a list and fix an ordering of the agents
    @Override
    public String toString() {
      return actionsOrdered().stream()
          .map(Objects::toString)
          .collect(Collectors.joining(",", "[", "]"));
    }

    public List<Action> actionsOrdered() {
      return actions.entrySet().stream()
          .sorted(Map.Entry.comparingByKey(Comparator.comparing(Agent::name)))
          .map(Map.Entry::getValue)
          .toList();
    }
  }

  public record State(String name) {
    @Override
    public String toString() {
      return name;
    }
  }

  public static final class Agent {
    private final String name;
    private final Formula goal;
    private final boolean payoff;
    private final Map<String, Action> actions;

    public Agent(String name, Formula goal, boolean payoff, Collection<Action> actions) {
      this.name = name;
      this.goal = goal;
      this.payoff = payoff;
      this.actions = actions.stream().collect(Collectors.toMap(Action::name, a -> a));
    }

    public String name() {
      return name;
    }

    public Formula goal() {
      return goal;
    }

    public boolean payoff() {
      return payoff;
    }

    public Collection<Action> actions() {
      return actions.values();
    }

    public Action actionByName(String name) {
      return Objects.requireNonNull(actions.get(name));
    }

    public boolean isLoser() {
      return !payoff;
    }

    @Override
    public String toString() {
      return "A[%s,%s]@{%s}".formatted(name, payoff, actions.values().stream().map(Objects::toString).collect(Collectors.joining(",")));
    }
  }

  public record Transition(Move move, State destination) {}
}
