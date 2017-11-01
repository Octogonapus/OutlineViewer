package edu.wpi.first.outlineviewer.model;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class NetworkTableRecord implements ConcurrentMap<Long, EntryChange> {
  public final ConcurrentHashMap<Long, EntryChange> data;

  public NetworkTableRecord() {
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
  public EntryChange get(Object other) {
    return data.get(other);
  }

  @Override
  public EntryChange put(Long key, EntryChange entryChange) {
    return data.put(key, entryChange);
  }

  @Override
  public EntryChange remove(Object other) {
    return data.remove(other);
  }

  @Override
  public boolean remove(Object key, Object val) {
    return data.remove(key, val);
  }

  @Override
  public void putAll(Map<? extends Long, ? extends EntryChange> map) {
    data.putAll(map);
  }

  @Override
  public void clear() {
    data.clear();
  }

  @Override
  public Set<Long> keySet() {
    return data.keySet();
  }

  @Override
  public Collection<EntryChange> values() {
    return data.values();
  }

  @Override
  public Set<Entry<Long, EntryChange>> entrySet() {
    return data.entrySet();
  }

  @Override
  public EntryChange putIfAbsent(Long key, EntryChange entryChange) {
    return data.putIfAbsent(key, entryChange);
  }

  @Override
  public boolean replace(Long key, EntryChange entryChange, EntryChange v1) {
    return data.replace(key, entryChange, v1);
  }

  @Override
  public EntryChange replace(Long key, EntryChange entryChange) {
    return data.replace(key, entryChange);
  }
}