package edu.wpi.first.outlineviewer.model;

import edu.wpi.first.networktables.NetworkTableType;

public class EntryChange {
  private final String name;
  private final NetworkTableType type;
  private final String newValue;

  public EntryChange(String name, NetworkTableType type, String newValue) {
    this.name = name;
    this.newValue = newValue;
    this.type = type;
  }

  public String getName() {
    return name;
  }

  public String getNewValue() {
    return newValue;
  }

  public NetworkTableType getType() {
    return type;
  }

  public int getTypeValue() {
    return type.getValue();
  }
}
