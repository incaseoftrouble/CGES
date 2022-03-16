package com.cges.parser;

import static com.google.common.base.Preconditions.checkArgument;

import com.cges.model.Action;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.Transition;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.SetMultimap;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import owl.ltl.LabelledFormula;
import owl.ltl.parser.LtlParser;

public final class GameParser {
  private static final Pattern COMMA_PATTERN = Pattern.compile(" *, *");
  private static final Pattern SPACE_PATTERN = Pattern.compile(" +");

  private GameParser() {}

  public static ConcurrentGame<State> parseExplicit(Stream<String> lines) {
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
    SetMultimap<State, Transition<State>> transitions = HashMultimap.create();
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
      checkArgument(Set.of("0", "1", "?").contains(agentData[2]), "Expected 0/1, got %s", agentData[2]);
      Agent.Payoff payoff = Agent.Payoff.parse(agentData[2]);
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
            actions.add(List.of(agent.action(transitionData[i])));
          }
        }

        for (List<Action> pureMove : Lists.cartesianProduct(actions)) {
          Map<Agent, Action> transitionActions = IntStream.range(0, agents.size())
              .boxed().collect(Collectors.toMap(agents::get, pureMove::get));
          transitions.put(state, new Transition<>(new ExplicitGame.MapMove(transitionActions), new State(destination)));
        }
      }
    }

    return new ExplicitGame<>("empty", agents, propositions, initialState, states, transitions, state -> Set.of(state.name()));
  }

  public static ConcurrentGame<?> parseExplicit(JsonObject json) {
    String type = json.getAsJsonPrimitive("type").getAsString();
    return switch (type) {
      case "module" -> ModuleParser.parse(json);
      case "explicit" -> ExplicitParser.parse(json);
      default -> throw new IllegalArgumentException("Unknown type " + type);
    };
  }
}
