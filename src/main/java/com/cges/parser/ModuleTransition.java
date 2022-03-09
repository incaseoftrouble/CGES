package com.cges.parser;

import com.cges.model.Action;

public record ModuleTransition<S>(Action action, S destination) {
}
