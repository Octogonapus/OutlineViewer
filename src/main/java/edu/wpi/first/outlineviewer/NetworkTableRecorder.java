package edu.wpi.first.outlineviewer;

import static edu.wpi.first.networktables.EntryListenerFlags.kDelete;
import static edu.wpi.first.networktables.EntryListenerFlags.kNew;
import static edu.wpi.first.networktables.EntryListenerFlags.kUpdate;

import com.google.common.base.Stopwatch;
import com.google.common.io.Files;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.networktables.NetworkTableType;
import edu.wpi.first.outlineviewer.model.EntryChange;
import edu.wpi.first.outlineviewer.model.NetworkTableRecord;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.apache.commons.text.StringEscapeUtils;

public class NetworkTableRecorder extends Thread {

  public static final String NTR_EXTENSION = ".ntr";
  private final NetworkTableRecord recording = new NetworkTableRecord();

  //Keep running the thread, false means kill and exit
  private boolean keepRunning;
  //Whether the thread should pause recording and wait
  private boolean isPaused;
  //State of the overall recorder (only written to in this class, not read from)
  private final SimpleObjectProperty<Thread.State> state;

  private long startTime;

  private final NetworkTableRecord playback = new NetworkTableRecord();
  private Thread playbackThread = null;
  private AtomicBoolean playbackIsPaused;
  private DoubleProperty playbackPercentage;
  private AtomicLong playbackEndTime;

  public NetworkTableRecorder() {
    super();
    setDaemon(true); //So we don't stop the application from closing
    keepRunning = true;
    isPaused = false;
    state = new SimpleObjectProperty<>(State.NEW);
    playbackIsPaused = new AtomicBoolean(false);
    playbackPercentage = new SimpleDoubleProperty(0);
    playbackEndTime = new AtomicLong(0);
  }

  @Override
  public synchronized void start() {
    try {
      super.start();
    } catch (IllegalThreadStateException ignored) {
      //TODO: Suppress checkstyle?
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
          NetworkTableRecorderUtilities.getValueAsString(entry));
    }

    NetworkTableUtilities.getNetworkTableInstance().addEntryListener("", notification -> {
      if (state.get().equals(State.RUNNABLE)) {
        if ((notification.flags & kNew) != 0 || (notification.flags & kUpdate) != 0) {
          saveEntry(notification.getEntry().getName(),
              notification.getEntry().getType(),
              NetworkTableRecorderUtilities.getValueAsString(notification.getEntry()));
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

  /**
   * Save a change to an entry into the record
   *
   * @param key Name of entry
   * @param type Type of entry
   * @param newValue New value of entry
   */
  private void saveEntry(String key, NetworkTableType type, String newValue) {
    saveEntry(key, type, newValue, false);
  }

  /**
   * Save a change to an entry into the record
   *
   * @param key Name of entry
   * @param type Type of entry
   * @param newValue New value of entry
   * @param wasDeleted Whether the entry was deleted
   */
  private void saveEntry(String key, NetworkTableType type, String newValue, boolean wasDeleted) {
    //Save with time relative to the start of the recording
    if (wasDeleted) {
      recording.put(System.nanoTime() - startTime,
          new EntryChange(key, type, "[[DELETED]]"));
    } else {
      recording.put(System.nanoTime() - startTime,
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
    chooser.getExtensionFilters().add(
        new FileChooser.ExtensionFilter("NetworkTables Recording",
            "*" + NTR_EXTENSION)); // "*.ntr"
    File result = chooser.showSaveDialog(window);
    if (result != null) {
      //Null means the user hit cancel or didn't select a file and therefore doesn't want to save,
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

    //Ending time of the recording -- just after the main thread exits
    final long endTime = System.nanoTime();

    //Get lock on file
    File file = path.toFile();
    if (!file.exists()) {
      Files.createParentDirs(file);
      Files.touch(file);
    }

    //ProgressIndicator while the recording is saved
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

    //This is not a daemon because we want to make sure we save before exiting
    new Thread(() -> {
      try {
        FileWriter writer = new FileWriter(file);

        //Header
        writer.write("[[NETWORKTABLES RECORDING]]\n");
        writer.write("[[DATE: " + LocalDateTime.now() + "]]\n");

        //Data
        recording.keySet()
            .parallelStream()
            .sorted()
            .forEachOrdered(key -> {
              try {
                writer.write(StringEscapeUtils.escapeXSI(key.toString())
                    + ";"
                    + StringEscapeUtils.escapeXSI(recording.get(key).getName())
                    + ";"
                    + StringEscapeUtils.escapeXSI(String.valueOf(recording.get(key).getTypeValue()))
                    + ";"
                    + StringEscapeUtils.escapeXSI(recording.get(key).getNewValue())
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
        //TODO: Log this
      } finally {
        //In order to close the dialog, we cheekily add a Button before the user notices and then
        //close it
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
    //ProgressIndicator for loading a recording
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

    //CountDownLatch to wait for the thread to finish so the dialog and thread run at the same time
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

          String time = NetworkTableRecorderUtilities.findSubstring(line,
              0,
              ';',
              true);

          String key = NetworkTableRecorderUtilities.findSubstring(line,
              time.length() + 1,
              ';',
              true);

          String type = NetworkTableRecorderUtilities.findSubstring(line,
              time.length() + key.length() + 2,
              ';',
              true);

          String value = NetworkTableRecorderUtilities.findSubstring(line,
              time.length() + key.length() + type.length() + 3,
              ';',
              true);

          time = StringEscapeUtils.unescapeXSI(time);
          key = StringEscapeUtils.unescapeXSI(key);
          type = StringEscapeUtils.unescapeXSI(type);
          value
              = StringEscapeUtils.unescapeXSI(value); //TODO: Can we suppress checkstyle distance
          // check here?

          //Construct a new entry in the playback record
          playback.put(
              Long.parseLong(time),
              new EntryChange(key,
                  NetworkTableType.getFromInt(Integer.parseInt(type)),
                  value));
        }
      } catch (IOException e) {
        //TODO: Log this
      } finally {
        Platform.runLater(() -> {
          dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
          dialog.close();
        });
        latch.countDown();
      }
    }).start();

    //Wait for loading to finish
    try {
      latch.await();
    } catch (InterruptedException e) {
      //TODO: This should be ignored, and we should just move onto playing the recording
    }

    //Pause recording and start playing the recording
    pause();
    startPlayback();
  }

  private void startPlayback() {
    playbackThread = new Thread(() -> {
      List<Long> times = playback.keySet()
          .parallelStream()
          .sorted()
          .collect(Collectors.toList());

      //StopWatch to control publishing timings
      Stopwatch stopwatch = Stopwatch.createStarted();
      //final long endTime = playbackEndTime.get();
      final long lastTime = times.get(times.size() - 1);

      times.forEach(time -> {
        //Wait until we should publish the new value
        while (stopwatch.elapsed(TimeUnit.NANOSECONDS) < time) {
          playbackPercentage.set(stopwatch.elapsed(TimeUnit.NANOSECONDS) / (double) lastTime);

          //Pause the stopwatch when playback is paused
          if (playbackIsPaused.get() && stopwatch.isRunning()) {
            stopwatch.stop();
          } else if (!playbackIsPaused.get() && !stopwatch.isRunning()) {
            stopwatch.start();
          }

          try {
            Thread.sleep(1);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }

        //Get the change and entry
        EntryChange change = playback.get(time);
        NetworkTableEntry entry
            = NetworkTableUtilities.getNetworkTableInstance().getEntry(change.getName());

        //Set the value of the change
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
              entry.setDoubleArray(
                  NetworkTableRecorderUtilities.parseDoubleArray(change.getNewValue()));
            }
            break;

          case kStringArray:
            if (change.getNewValue().equals("[[DELETED]]")) {
              entry.delete();
            } else {
              entry.setStringArray(
                  NetworkTableRecorderUtilities.parseStringArray(change.getNewValue()));
            }
            break;

          case kBooleanArray:
            if (change.getNewValue().equals("[[DELETED]]")) {
              entry.delete();
            } else {
              entry.setBooleanArray(
                  NetworkTableRecorderUtilities.parseBooleanArray(change.getNewValue()));
            }
            break;

          case kRaw:
            if (change.getNewValue().equals("[[DELETED]]")) {
              entry.delete();
            } else {
              entry.setRaw(NetworkTableRecorderUtilities.parseByteArray(change.getNewValue()));
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
    });

    //Playback should not prevent closing
    playbackThread.setDaemon(true);
    playbackThread.start();
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
    playbackIsPaused.set(true);
  }

  /**
   * Resume playback of a recording.
   */
  public void unpausePlayback() {
    playbackIsPaused.set(false);
  }

  /**
   * Rewinds playback of a recording to the start.
   */
  public void rewindPlayback() {
    //TODO: rewind to start
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

  public AtomicBoolean getPlaybackIsPaused() {
    return playbackIsPaused;
  }

  /**
   * Wait one timestep in the main loop.
   */
  private void waitTimestep() {
    try {
      sleep(1000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

}
