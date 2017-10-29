package edu.wpi.first.outlineviewer;

import static edu.wpi.first.networktables.EntryListenerFlags.kDelete;
import static edu.wpi.first.networktables.EntryListenerFlags.kNew;
import static edu.wpi.first.networktables.EntryListenerFlags.kUpdate;

import com.google.common.base.Charsets;
import com.google.common.escape.ArrayBasedUnicodeEscaper;
import com.google.common.escape.Escaper;
import com.google.common.escape.UnicodeEscaper;
import com.google.common.io.CharSink;
import com.google.common.io.FileWriteMode;
import com.google.common.io.Files;
import com.google.common.net.PercentEscaper;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.outlineviewer.model.EntryChange;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javafx.beans.property.SimpleObjectProperty;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.commons.text.translate.AggregateTranslator;
import org.apache.commons.text.translate.CharSequenceTranslator;
import org.apache.commons.text.translate.EntityArrays;
import org.apache.commons.text.translate.JavaUnicodeEscaper;
import org.apache.commons.text.translate.LookupTranslator;
import org.apache.commons.text.translate.OctalUnescaper;
import org.apache.commons.text.translate.UnicodeUnescaper;

public class NetworkTableRecorder extends Thread {

  public static final String NTR_EXTENSION = ".ntr";

  //Keep running the thread, false means kill and exit
  private boolean keepRunning;
  //Whether the thread should pause recording and wait
  private boolean isPaused;
  //State of the overall recorder (only written to in this class, not read from)
  private final SimpleObjectProperty<Thread.State> state;

  private final ConcurrentHashMap<LocalDateTime, EntryChange> values;

  public NetworkTableRecorder() {
    super();
    setDaemon(true); //So we don't stop the application from closing
    keepRunning = true;
    isPaused = false;
    state = new SimpleObjectProperty<>(State.NEW);
    values = new ConcurrentHashMap<>();
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
    NetworkTableUtilities.getNetworkTableInstance().addEntryListener("", notification -> {
      saveEntry(notification.getEntry().getName(), getValueAsString(notification.getEntry()));

      notification.getEntry().addListener(entryNotification -> {
        if ((entryNotification.flags & kNew) != 0 || (entryNotification.flags & kUpdate) != 0) {
          saveEntry(entryNotification.getEntry().getName(),
              getValueAsString(entryNotification.getEntry()));
        } else if ((entryNotification.flags & kDelete) != 0) {
          saveEntry(entryNotification.getEntry().getName(), "", true);
        }
      }, kUpdate | kDelete);
    }, kNew);

    //Even though this loop doesn't do much, we need it to stop us from freezing
    while (keepRunning) {
      //Paused means stop recording and wait
      if (isPaused) {
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

  private void saveEntry(String key, String newValue) {
    saveEntry(key, newValue, false);
  }

  private void saveEntry(String key, String newValue, boolean wasDeleted) {
    if (wasDeleted) {
      values.put(LocalDateTime.now(), new EntryChange(key, "[[DELETED]]"));
    } else {
      values.put(LocalDateTime.now(), new EntryChange(key, newValue));
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
              sink.write(StringEscapeUtils.escapeXSI(key.toString()) + ";"
                  + StringEscapeUtils.escapeXSI(values.get(key).getName()) + ";"
                  + StringEscapeUtils.escapeXSI(values.get(key).getNewValue()) + "\n");
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
        String value;

        time = findSubstring(line, 0, ';');
        key = findSubstring(line, time.length() + 1, ';');
        value = findSubstring(line, time.length() + key.length() + 2, ';');

        time = StringEscapeUtils.unescapeXSI(time);
        key = StringEscapeUtils.unescapeXSI(key);
        value = StringEscapeUtils.unescapeXSI(value);
      }
    }
  }

  /**
   * Find a substring inside a delimited string.
   * @param source String containing substring
   * @param start Start index inside string
   * @param delimiter Delimiter delimiting substrings
   * @return The substring, or empty if nothing was found
   */
  private String findSubstring(String source, int start, char delimiter) {
    char[] chars = source.toCharArray();

    for (int i = start; i < chars.length; i++) {
      if (chars[i] == delimiter) {
        //Found a delimiter
        if (i == 0) {
          //If we are on the first index, we don't need to check the previous character for an
          //escape
          return source.substring(start, i);
        } else if (chars[i - 1] != '\\') {
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
  }

  /**
   * Resume recording after being paused.
   */
  public void unpause() {
    isPaused = false;
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
