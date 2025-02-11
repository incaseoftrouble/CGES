package com.cges;

import com.cges.graph.RunGraph;
import com.cges.graph.SuspectGame;
import com.cges.model.EquilibriumStrategy;
import com.cges.model.PayoffAssignment;

public record GameSolution<S>(
        SuspectGame<S> suspectGame,
        RunGraph<S> runGraph,
        PayoffAssignment assignment,
        EquilibriumStrategy<S> strategy) {}
