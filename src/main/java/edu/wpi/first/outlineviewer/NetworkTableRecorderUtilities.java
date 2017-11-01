package edu.wpi.first.outlineviewer;

import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.outlineviewer.model.EntryChange;
import edu.wpi.first.outlineviewer.model.NetworkTableRecord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import org.apache.commons.lang3.ArrayUtils;

public final class NetworkTableRecorderUtilities {

  /**
   * Returns a NetworkTableEntry's value as a string.
   *
   * @param entry Entry to string-ify
   * @return String representation of the entry's value
   */
  public static String getValueAsString(NetworkTableEntry entry) {
    switch (entry.getType()) {
      case kDouble:
        return String.valueOf(entry.getDouble(0));

      case kString:
        return entry.getString("");

      case kBoolean:
        return String.valueOf(entry.getBoolean(false));

      case kDoubleArray:
        return Arrays.stream(entry.getDoubleArray(new Double[]{0.0}))
            .mapToDouble(Double::doubleValue)
            .mapToObj(String::valueOf)
            .collect(Collectors.joining(","));

      case kStringArray: {
        String[] data = entry.getStringArray(new String[]{""});
        StringJoiner joiner = new StringJoiner(",");
        for (String datum : data) {
          joiner.add(datum);
        }
        return joiner.toString();
      }

      case kBooleanArray:
        return Arrays.stream(entry.getBooleanArray(new Boolean[]{false}))
            .mapToInt(val -> val ? 1 : 0)
            .mapToObj(String::valueOf)
            .collect(Collectors.joining(","));

      case kRaw: {
        byte[] data = entry.getRaw(new byte[]{0});
        StringJoiner joiner = new StringJoiner(",");
        for (byte datum : data) {
          joiner.add(String.valueOf(datum));
        }
        return joiner.toString();
      }

      default:
        return "";
    }
  }

  /**
   * Parses a comma delimited double array from a String.
   *
   * @param source double array String
   * @return double array version of string
   */
  public static double[] parseDoubleArray(String source) {
    List<Double> doubles = new ArrayList<>();

    for (int i = 0; i < source.length(); ) {
      String sub = findSubstring(source, i, ',', false);
      if (!sub.equals("")) {
        doubles.add(Double.parseDouble(sub));
        i += sub.length() + 1;
      } else {
        i++;
      }
    }

    return ArrayUtils.toPrimitive(doubles.toArray(new Double[doubles.size()]));
  }

  /**
   * Parses a comma delimited string array from a String.
   *
   * @param source string array String
   * @return string array version of string
   */
  public static String[] parseStringArray(String source) {
    List<String> strings = new ArrayList<>();

    for (int i = 0; i < source.length(); ) {
      String sub = findSubstring(source, i, ',', false);
      if (!sub.equals("")) {
        strings.add(sub);
        i += sub.length() + 1;
      } else {
        i++;
      }
    }

    return strings.toArray(new String[strings.size()]);
  }

  /**
   * Parses a comma delimited boolean array from a String.
   *
   * @param source boolean array String
   * @return boolean array version of string
   */
  public static boolean[] parseBooleanArray(String source) throws NumberFormatException {
    List<Boolean> booleans = new ArrayList<>();

    for (int i = 0; i < source.length(); ) {
      String sub = findSubstring(source, i, ',', false);
      String val = sub;

      if (Integer.parseInt(sub) == 1) {
        val = "true";
      }

      if (!sub.equals("")) {
        booleans.add(Boolean.parseBoolean(val));
        i += sub.length() + 1;
      } else {
        i++;
      }
    }

    return ArrayUtils.toPrimitive(booleans.toArray(new Boolean[booleans.size()]));
  }

  /**
   * Parses a comma delimited byte array from a String.
   *
   * @param source byte array String
   * @return byte array version of string
   */
  public static byte[] parseByteArray(String source) {
    List<Byte> bytes = new ArrayList<>();

    for (int i = 0; i < source.length(); ) {
      String sub = findSubstring(source, i, ',', false);
      if (!sub.equals("")) {
        bytes.add(Byte.parseByte(sub));
        i += sub.length() + 1;
      } else {
        i++;
      }
    }

    return ArrayUtils.toPrimitive(bytes.toArray(new Byte[bytes.size()]));
  }

  /**
   * Find a substring inside a delimited string.
   *
   * @param source String containing substring
   * @param start Start index inside string
   * @param delimiter Delimiter delimiting substrings
   * @return The substring, or empty if nothing was found
   */
  public static String findSubstring(String source, int start, char delimiter, boolean
      checkForEscape) {
    char[] chars = source.toCharArray();

    for (int i = start; i < chars.length; i++) {
      if (chars[i] == delimiter) {
        //Found a delimiter
        if (i == 0) {
          //If we are on the first index, we don't need to check the previous character for an
          //escape
          return source.substring(start, i);
        } else if (!checkForEscape || chars[i - 1] != '\\') {
          //Otherwise, we need to check for an escape
          return source.substring(start, i);
        }
      } else if (i == chars.length - 1) {
        //Last index (i.e. should be a newline but toCharArray strips the newline) so return the
        //substring because newline is the line delimiter
        return source.substring(start, i + 1); //i + 1 so we include the last character
      }
    }

    return "";
  }

  /**
   * Computes the NetworkTable state at a given time by running through the loaded recording and
   * simulating publishing those data.
   *
   * @param time Time in recording
   */
  public static Map<String, String> computeNetworkTableState(NetworkTableRecord record, long time) {
    Map<String, String> state = new HashMap<>();
    record.keySet()
        .parallelStream()
        .sorted()
        .forEachOrdered(val -> {
          if (val < time) {
            EntryChange change = record.get(time);
            if (change.getNewValue().equals("[[DELETED]]")) {
              state.remove(change.getName());
            } else {
              state.put(change.getName(), change.getNewValue());
            }
          }
        });
    return state;
  }

}
