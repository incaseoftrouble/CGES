package com.cges.output;

public interface DotFormatted {
  String dotString();

  static String toString(Object object) {
    return (object instanceof DotFormatted format) ? format.dotString() : object.toString();
  }
}
