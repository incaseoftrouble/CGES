package com.cges.parity.si;

import javax.annotation.Nonnegative;

public interface ValueVector {
  int get(int priority);

  default ValueVector add(@Nonnegative int priority) {
    return add(ValueVectors.single(priority));
  }

  ValueVector add(ValueVector value);
}
