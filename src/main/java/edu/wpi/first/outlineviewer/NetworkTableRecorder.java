package edu.wpi.first.outlineviewer;

import static edu.wpi.first.networktables.EntryListenerFlags.kDelete;
import static edu.wpi.first.networktables.EntryListenerFlags.kNew;
import static edu.wpi.first.networktables.EntryListenerFlags.kUpdate;
import static edu.wpi.first.networktables.NetworkTableType.kBoolean;
import static edu.wpi.first.networktables.NetworkTableType.kBooleanArray;
import static edu.wpi.first.networktables.NetworkTableType.kDouble;
import static edu.wpi.first.networktables.NetworkTableType.kDoubleArray;
import static edu.wpi.first.networktables.NetworkTableType.kRaw;
import static edu.wpi.first.networktables.NetworkTableType.kString;
import static edu.wpi.first.networktables.NetworkTableType.kStringArray;
import static edu.wpi.first.networktables.NetworkTableType.kUnassigned;

import com.google.common.base.Stopwatch;
import com.google.common.io.Files;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.networktables.NetworkTableType;
import edu.wpi.first.networktables.NetworkTableValue;
import edu.wpi.first.outlineviewer.model.EntryChange;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
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
  private long startTime;

  private final ConcurrentHashMap<Long, EntryChange> playback;
  private Thread playbackThread = null;
  private AtomicBoolean playbackShouldPause;
  private DoubleProperty playbackPercentage;
  private AtomicLong playbackEndTime;

  public NetworkTableRecorder() {
    super();
    setDaemon(true); //So we don't stop the application from closing
    keepRunning = true;
    isPaused = false;
    state = new SimpleObjectProperty<>(State.NEW);
    values = new ConcurrentHashMap<>();
    playback = new ConcurrentHashMap<>();
    playbackShouldPause = new AtomicBoolean(false);
    playbackPercentage = new SimpleDoubleProperty(0);
    playbackEndTime = new AtomicLong(0);
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
    startTime = System.nanoTime();

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
    }, kNew | kUpdate | kDelete);

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

    state.set(State.TERMINATED);
  }

  private void saveEntry(String key, NetworkTableType type, String newValue) {
    saveEntry(key, type, newValue, false);
  }

  private void saveEntry(String key, NetworkTableType type, String newValue, boolean wasDeleted) {
    if (wasDeleted) {
      values.put(System.nanoTime() - startTime,
          new EntryChange(key, type, "[[DELETED]]"));
    } else {
      values.put(System.nanoTime() - startTime,
          new EntryChange(key, type, newValue));
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
      saveAndJoin(result.toPath(), window);
    }
  }

  /**
   * Stop recording, save the recorded data to a file, and join the thread.
   *
   * @throws InterruptedException Waiting for the data recorder to stop could be interrupted
   * @throws IOException Writing to the file could error
   */
  @SuppressWarnings("PMD")
  public void saveAndJoin(String fileName, Window window) throws IOException, InterruptedException {
    saveAndJoin(Paths.get(fileName), window);
  }

  /**
   * Stop recording, save the recorded data to a file, and join the thread.
   *
   * @throws InterruptedException Waiting for the data recorder to stop could be interrupted
   * @throws IOException Writing to the file could error
   */
  @SuppressWarnings("PMD")
  public void saveAndJoin(Path path, Window window) throws InterruptedException, IOException {
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

    final long endTime = System.nanoTime();

    //Get lock on file
    File file = path.toFile();
    if (!file.exists()) {
      Files.createParentDirs(file);
      Files.touch(file);
    }

    ProgressIndicator progressIndicator = new ProgressIndicator();
    Dialog<String> dialog = new Dialog<>();
    StackPane pane = new StackPane(progressIndicator);
    pane.setPrefSize(200, 100);
    pane.setAlignment(Pos.CENTER);
    dialog.getDialogPane().setContent(pane);
    dialog.setTitle("Saving NetworkTables Recording...");
    dialog.initOwner(window);
    dialog.initModality(Modality.WINDOW_MODAL);
    dialog.show();

    new Thread(() -> {
      try {
        FileWriter writer = new FileWriter(file);

        //Header
        writer.write("[[NETWORKTABLES RECORDING]]\n");
        writer.write("[[DATE: " + LocalDateTime.now() + "]]\n");

        //Data
        values.keySet()
            .parallelStream()
            .sorted()
            .forEachOrdered(key -> {
              try {
                writer.write(StringEscapeUtils.escapeXSI(key.toString())
                    + ";"
                    + StringEscapeUtils.escapeXSI(values.get(key).getName())
                    + ";"
                    + StringEscapeUtils.escapeXSI(String.valueOf(values.get(key).getTypeValue()))
                    + ";"
                    + StringEscapeUtils.escapeXSI(values.get(key).getNewValue())
                    + "\n");
              } catch (IOException ignored) {
                //TODO: Log this
              }
            });

        //Footer
        writer.write("[[END TIME: " + endTime + "]]\n");

        writer.flush();
        writer.close();
      } catch (IOException e) {
        e.printStackTrace();
      } finally {
        Platform.runLater(() -> {
          dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
          dialog.close();
        });
      }
    }).start();
  }

  /**
   * Load a NetworkTables recording from a file and play it back.
   *
   * @param file Recording file
   * @throws IOException Loading the file could produce an IOException
   */
  public void load(File file, Window window) throws IOException {
    ProgressIndicator progressIndicator = new ProgressIndicator();
    Dialog<String> dialog = new Dialog<>();
    StackPane pane = new StackPane(progressIndicator);
    pane.setPrefSize(200, 100);
    pane.setAlignment(Pos.CENTER);
    dialog.getDialogPane().setContent(pane);
    dialog.setTitle("Loading NetworkTables Recording...");
    dialog.initOwner(window);
    dialog.initModality(Modality.WINDOW_MODAL);
    dialog.show();

    CountDownLatch latch = new CountDownLatch(1);

    new Thread(() -> {
      try (BufferedReader br = new BufferedReader(new FileReader(file))) {
        String line;
        int lineCount = 0;
        while ((line = br.readLine()) != null) {
          //Handle header
          if (lineCount++ <= 1) {
            continue;
          }

          //Handle footer
          if (line.startsWith("[[END TIME: ")) {
            playbackEndTime.set(Long.parseLong(line.substring(13, line.length() - 2)));
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
      } catch (IOException e) {
        e.printStackTrace();
      } finally {
        Platform.runLater(() -> {
          dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
          dialog.close();
        });
        latch.countDown();
      }
    }).start();

    try {
      latch.await();
    } catch (InterruptedException e) {
      //TODO: This should be ignored, and we should just move onto playing the recording
    }

    pause();
    startPlayback();
  }

  private void startPlayback() {
    playbackThread = new Thread(() -> {
      List<Long> times = playback.keySet()
          .parallelStream()
          .sorted()
          .collect(Collectors.toList());

      final int max = playback.size();
      final int[] completed = new int[]{0};
      //StopWatch to control publishing timings
      Stopwatch stopwatch = Stopwatch.createStarted();
      final long endTime = playbackEndTime.get();
      final long lastTime = times.get(times.size() - 1);

      times.forEach(time -> {
        //Wait until we should publish the new value
        while (stopwatch.elapsed(TimeUnit.NANOSECONDS) < time) {
          playbackPercentage.set(stopwatch.elapsed(TimeUnit.NANOSECONDS) / (double) lastTime);

          if (playbackShouldPause.get()) {
            stopwatch.stop();
          } else if (!stopwatch.isRunning()) {
            stopwatch.start();
          }

          try {
            Thread.sleep(1);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }

        EntryChange change = playback.get(time);
        NetworkTableEntry entry
            = NetworkTableUtilities.getNetworkTableInstance().getEntry(change.getName());

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
            break;

          default:
            break;
        }
      });

      computeNetworkTableState(times.get(times.size() - 1));
    });
    playbackThread.setDaemon(true);
    playbackThread.start();
  }

  /**
   * Computes the NetworkTable state at a given time by running through the loaded recording and
   * simulating publishing those values.
   *
   * @param time Time in recording
   */
  private Map<String, String> computeNetworkTableState(long time) {
    Map<String, String> state = new HashMap<>();
    playback.keySet()
        .parallelStream()
        .sorted()
        .forEachOrdered(val -> {
          if (val < time) {
            EntryChange change = playback.get(time);
            if (change.getNewValue().equals("[[DELETED]]")) {
              state.remove(change.getName());
            } else {
              state.put(change.getName(), change.getNewValue());
            }
          }
        });
    return state;
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
  private boolean[] parseBooleanArray(String source) throws NumberFormatException {
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

  /**
   * Pause playback of a recording.
   */
  public void pausePlayback() {

  }

  /**
   * Resume playback of a recording.
   */
  public void unpausePlayback() {

  }

  @Override
  public State getState() {
    return state.get();
  }

  public SimpleObjectProperty<State> stateProperty() {
    return state;
  }

  public double getPlaybackPercentage() {
    return playbackPercentage.get();
  }

  public DoubleProperty playbackPercentageProperty() {
    return playbackPercentage;
  }

  private void waitTimestep() {
    try {
      sleep(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
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
