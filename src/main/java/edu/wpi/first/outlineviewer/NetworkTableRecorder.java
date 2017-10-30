package edu.wpi.first.outlineviewer;

import static edu.wpi.first.networktables.EntryListenerFlags.kDelete;
import static edu.wpi.first.networktables.EntryListenerFlags.kNew;
import static edu.wpi.first.networktables.EntryListenerFlags.kUpdate;

import com.google.common.base.Charsets;
import com.google.common.io.CharSink;
import com.google.common.io.FileWriteMode;
import com.google.common.io.Files;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.networktables.NetworkTableType;
import edu.wpi.first.outlineviewer.model.EntryChange;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javafx.beans.property.SimpleObjectProperty;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.text.StringEscapeUtils;

public class NetworkTableRecorder extends Thread {

  public static final String NTR_EXTENSION = ".ntr";

  //Keep running the thread, false means kill and exit
  private boolean keepRunning;
  //Whether the thread should pause recording and wait
  private boolean isPaused;
  //State of the overall recorder (only written to in this class, not read from)
  private final SimpleObjectProperty<Thread.State> state;

  private final ConcurrentHashMap<Long, EntryChange> values;

  private final ConcurrentHashMap<Long, EntryChange> playback;
  private Thread playbackThread = null;

  public NetworkTableRecorder() {
    super();
    setDaemon(true); //So we don't stop the application from closing
    keepRunning = true;
    isPaused = false;
    state = new SimpleObjectProperty<>(State.NEW);
    values = new ConcurrentHashMap<>();
    playback = new ConcurrentHashMap<>();
  }

  @Override
  public synchronized void start() {
    try {
      super.start();
    } catch (IllegalThreadStateException ignored) {
    }
  }

  @Override
  @SuppressWarnings("PMD")
  public void run() {
    NetworkTableEntry[] entries = NetworkTableUtilities.getNetworkTableInstance()
        .getEntries("", 0xFF);
    for (NetworkTableEntry entry : entries) {
      saveEntry(entry.getName(),
          entry.getType(),
          getValueAsString(entry));
    }

    NetworkTableUtilities.getNetworkTableInstance().addEntryListener("", notification -> {
      if (state.get().equals(State.RUNNABLE)) {
        if ((notification.flags & kNew) != 0 || (notification.flags & kUpdate) != 0) {
          saveEntry(notification.getEntry().getName(),
              notification.getEntry().getType(),
              getValueAsString(notification.getEntry()));
        } else if ((notification.flags & kDelete) != 0) {
          saveEntry(notification.getEntry().getName(),
              notification.getEntry().getType(),
              "",
              true);
        }
      }
    },  kNew | kUpdate | kDelete);

    //Even though this loop doesn't do much, we need it to stop us from freezing
    while (keepRunning) {
      //Paused means stop recording and wait
      if (isPaused) {
        if (playbackThread != null && playbackThread.getState().equals(State.TERMINATED)) {
          unpause(); //Playback is done so go back to recording
        }
        state.set(State.WAITING);
        waitTimestep();
        continue;
      }

      //Else we aren't paused so keep recording
      state.set(State.RUNNABLE);

      waitTimestep();
    }

    System.out.println("Terminated!");
    state.set(State.TERMINATED);
  }

  private void saveEntry(String key, NetworkTableType type, String newValue) {
    saveEntry(key, type, newValue, false);
  }

  private void saveEntry(String key, NetworkTableType type, String newValue, boolean wasDeleted) {
    if (wasDeleted) {
      values.put(System.nanoTime(), new EntryChange(key, type, "[[DELETED]]"));
    } else {
      values.put(System.nanoTime(), new EntryChange(key, type, newValue));
    }
  }

  /**
   * Stop recording, save the recorded data to a file, and join the thread. Shows the user a
   * FileChooser dialog to get the file path.
   *
   * @throws InterruptedException Waiting for the data recorder to stop could be interrupted
   * @throws IOException Writing to the file could error
   */
  @SuppressWarnings("PMD")
  public void saveAndJoin(Window window) throws IOException, InterruptedException {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Save NetworkTables Recording");
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("NetworkTables Recording",
        "*" + NTR_EXTENSION)); // "*.ntr"
    File result = chooser.showSaveDialog(window);
    if (result != null) {
      //Null means the user hit cancel or didn't select a file and therefore doesn't want to save
      //so only save if the file is not null
      saveAndJoin(result.toPath());
    }
  }

  /**
   * Stop recording, save the recorded data to a file, and join the thread.
   *
   * @throws InterruptedException Waiting for the data recorder to stop could be interrupted
   * @throws IOException Writing to the file could error
   */
  @SuppressWarnings("PMD")
  public void saveAndJoin(String fileName) throws IOException, InterruptedException {
    saveAndJoin(Paths.get(fileName));
  }

  /**
   * Stop recording, save the recorded data to a file, and join the thread.
   *
   * @throws InterruptedException Waiting for the data recorder to stop could be interrupted
   * @throws IOException Writing to the file could error
   */
  @SuppressWarnings("PMD")
  public void saveAndJoin(Path path) throws InterruptedException, IOException {
    //Add extension if it isn't there
    if (!path.endsWith(NTR_EXTENSION)) {
      path.resolveSibling(path.getFileName() + NTR_EXTENSION);
    }

    //Stop running and wait for data to stop
    keepRunning = false;
    while (!state.get().equals(State.TERMINATED)) {
      try {
        sleep(1);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    //Get lock on file
    File file = path.toFile();
    if (!file.exists()) {
      Files.createParentDirs(file);
      Files.touch(file);
    }

    //Write to file
    try {
      CharSink sink = Files.asCharSink(file, Charsets.UTF_8, FileWriteMode.APPEND);

      //Header
      sink.write("[[NETWORKTABLES RECORDING]]\n");
      sink.write("[[DATE: " + LocalDateTime.now() + "]]\n");

      //Data
      values.keySet()
          .parallelStream()
          .sorted()
          .forEachOrdered(key -> {
            try {
              sink.write(StringEscapeUtils.escapeXSI(key.toString())
                  + ";"
                  + StringEscapeUtils.escapeXSI(values.get(key).getName())
                  + ";"
                  + StringEscapeUtils.escapeXSI(String.valueOf(values.get(key).getTypeValue()))
                  + ";"
                  + StringEscapeUtils.escapeXSI(values.get(key).getNewValue())
                  + "\n");
            } catch (IOException e) {
              e.printStackTrace();
            }
          });
    } finally {
      join();
    }
  }

  /**
   * Load a NetworkTables recording from a file and play it back.
   *
   * @param file Recording file
   * @throws IOException Loading the file could produce an IOException
   */
  public void load(File file) throws IOException {
    try (BufferedReader br = new BufferedReader(new FileReader(file))) {
      String line;
      int lineCount = 0;
      while ((line = br.readLine()) != null) {
        if (lineCount++ <= 1) {
          continue;
        }

        String time;
        String key;
        String type;
        String value;

        time = findSubstring(line,
            0,
            ';',
            true);

        key = findSubstring(line,
            time.length() + 1,
            ';',
            true);

        type = findSubstring(line,
            time.length() + key.length() + 2,
            ';',
            true);

        value = findSubstring(line,
            time.length() + key.length() + type.length() + 3,
            ';',
            true);

        time = StringEscapeUtils.unescapeXSI(time);
        key = StringEscapeUtils.unescapeXSI(key);
        type = StringEscapeUtils.unescapeXSI(type);
        value = StringEscapeUtils.unescapeXSI(value);

        playback.put(Long.parseLong(time),
            new EntryChange(key,
                NetworkTableType.getFromInt(Integer.parseInt(type)),
                value));
      }
    }

    pause();
    startPlayback();
  }

  private void startPlayback() {
    playbackThread = new Thread(() ->
        playback.keySet()
            .stream()
            .sorted()
            .forEachOrdered(time -> {
              EntryChange change = playback.get(time);
              NetworkTableEntry entry = NetworkTableUtilities.getNetworkTableInstance()
                  .getEntry(change.getName());

              switch (change.getType()) {
                case kDouble:
                  if (change.getNewValue().equals("[[DELETED]]")) {
                    entry.delete();
                  } else {
                    entry.setDouble(Double.parseDouble(change.getNewValue()));
                  }
                  break;

                case kString:
                  if (change.getNewValue().equals("[[DELETED]]")) {
                    entry.delete();
                  } else {
                    entry.setString(change.getNewValue());
                  }
                  break;

                case kBoolean:
                  if (change.getNewValue().equals("[[DELETED]]")) {
                    entry.delete();
                  } else {
                    entry.setBoolean(Boolean.parseBoolean(change.getNewValue()));
                  }
                  break;

                case kDoubleArray:
                  if (change.getNewValue().equals("[[DELETED]]")) {
                    entry.delete();
                  } else {
                    entry.setDoubleArray(parseDoubleArray(change.getNewValue()));
                  }
                  break;

                case kStringArray:
                  if (change.getNewValue().equals("[[DELETED]]")) {
                    entry.delete();
                  } else {
                    entry.setStringArray(parseStringArray(change.getNewValue()));
                  }
                  break;

                case kBooleanArray:
                  if (change.getNewValue().equals("[[DELETED]]")) {
                    entry.delete();
                  } else {
                    entry.setBooleanArray(parseBooleanArray(change.getNewValue()));
                  }
                  break;

                case kRaw:
                  if (change.getNewValue().equals("[[DELETED]]")) {
                    entry.delete();
                  } else {
                    entry.setRaw(parseByteArray(change.getNewValue()));
                  }
                  break;

                case kUnassigned:
                  if (change.getNewValue().equals("[[DELETED]]")) {
                    entry.delete();
                  }

                default:
                  break;
              }
            }));
    playbackThread.start();
  }

  /**
   * Parses a comma delimited double array from a String.
   *
   * @param source double array String
   * @return double array version of string
   */
  private double[] parseDoubleArray(String source) {
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
  private String[] parseStringArray(String source) {
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
  private boolean[] parseBooleanArray(String source) {
    List<Boolean> booleans = new ArrayList<>();

    for (int i = 0; i < source.length(); ) {
      String sub = findSubstring(source, i, ',', false);
      String val = sub;

      try {
        if (Integer.parseInt(sub) == 1) {
          val = "true";
        }
      } catch (NumberFormatException ignored) {
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
  private byte[] parseByteArray(String source) {
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
  private String findSubstring(String source, int start, char delimiter, boolean checkForEscape) {
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
   * Pause recording and stay ready to start recording.
   */
  public void pause() {
    isPaused = true;
    state.set(State.WAITING);
  }

  /**
   * Resume recording after being paused.
   */
  public void unpause() {
    isPaused = false;
    state.set(State.RUNNABLE);
  }

  @Override
  public State getState() {
    return state.get();
  }

  public SimpleObjectProperty<State> stateProperty() {
    return state;
  }

  private void waitTimestep() {
    try {
      sleep(1000);
    } catch (InterruptedException ignored) {
    }
  }

  /**
   * Returns a NetworkTableEntry's value as a string.
   *
   * @param entry Entry to string-ify
   * @return String representation of the entry's value
   */
  private static String getValueAsString(NetworkTableEntry entry) {
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

}
