package edu.wpi.first.outlineviewer.model;

public class EntryChange {
  private String name;
  private String newValue;

  public EntryChange(String name, String newValue) {
    this.name = name;
    this.newValue = newValue;
  }

  public String getName() {
    return name;
  }

  public String getNewValue() {
    return newValue;
  }
}
