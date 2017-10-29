package edu.wpi.first.outlineviewer.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NetworkTables recording data structure. Wrapper for a ConcurrentHashMap so we can have a few
 * convenience methods.
 */
public class NTRecord implements Map<String, List<String>> {
  private final ConcurrentHashMap<String, List<String>> data;

  public NTRecord() {
    data = new ConcurrentHashMap<>();
  }

  @Override
  public int size() {
    return data.size();
  }

  @Override
  public boolean isEmpty() {
    return data.isEmpty();
  }

  @Override
  public boolean containsKey(Object other) {
    return data.containsKey(other);
  }

  @Override
  public boolean containsValue(Object other) {
    return data.containsValue(other);
  }

  @Override
  public List<String> get(Object other) {
    return data.get(other);
  }

  private List<String> getSingleton(String key) {
    if (data.containsKey(key)) {
      return data.get(key);
    } else {
      data.put(key, new ArrayList<>());
      return data.get(key);
    }
  }

  @Override
  public List<String> put(String key, List<String> strings) {
    getSingleton(key).addAll(strings);
    return data.get(key);
  }

  public List<String> put(String key, String val) {
    getSingleton(key).add(val);
    return data.get(key);
  }

  @Override
  public List<String> remove(Object other) {
    return data.remove(other);
  }

  @Override
  public void putAll(Map<? extends String, ? extends List<String>> map) {
    data.putAll(map);
  }

  @Override
  public void clear() {
    data.clear();
  }

  @Override
  public Set<String> keySet() {
    return data.keySet();
  }

  @Override
  public Collection<List<String>> values() {
    return data.values();
  }

  @Override
  public Set<Entry<String, List<String>>> entrySet() {
    return data.entrySet();
  }
}
