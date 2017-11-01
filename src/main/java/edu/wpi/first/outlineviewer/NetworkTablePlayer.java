package edu.wpi.first.outlineviewer;

import com.google.common.base.Stopwatch;
import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.networktables.NetworkTableType;
import edu.wpi.first.outlineviewer.model.EntryChange;
import edu.wpi.first.outlineviewer.model.NetworkTableRecord;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.StackPane;
import javafx.stage.Modality;
import javafx.stage.Window;
import org.apache.commons.text.StringEscapeUtils;

public class NetworkTablePlayer {
  private final NetworkTableRecord playback;
  private Thread playbackThread;
  private AtomicReference<BooleanProperty> playbackIsPaused;
  private AtomicReference<DoubleProperty> playbackPercentage;
  private AtomicLong playbackEndTime;
  private AtomicReference<BooleanProperty> playbackDone;

  private File file;
  private Window window;

  public NetworkTablePlayer() {
    playback = new NetworkTableRecord();
    playbackThread = null;
    playbackIsPaused = new AtomicReference<>(new SimpleBooleanProperty(false));
    playbackPercentage = new AtomicReference<>(new SimpleDoubleProperty(0));
    playbackEndTime = new AtomicLong(0);
    playbackDone = new AtomicReference<>(new SimpleBooleanProperty(true));

    file = null;
    window = null;
  }

  /**
   * Load a NetworkTables recording from a file into memory so it's ready to be played.
   *
   * @param file Recording file
   * @param window Window to show loading dialog in
   */
  @SuppressWarnings("checkstyle:VariableDeclarationUsageDistance")
  public void loadRecording(File file, Window window) {
    this.file = file;
    this.window = window;

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
          value = StringEscapeUtils.unescapeXSI(value);

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
  }

  public void startPlayback() {
    startPlayback(0);
  }

  /**
   * Start the playback of the loaded recording. If no recording has been loaded, or the loaded
   * recording empty, this won't play anything.
   *
   * @param startTime Starting time to play from
   */
  public void startPlayback(long startTime) {
    //Don't play an empty recording
    if (playback.size() == 0) {
      return;
    }

    playbackDone.get().set(false);

    List<Long> times = playback.keySet()
        .parallelStream()
        .filter(time -> time >= startTime) //If we aren't starting at 0, we need to drop the past
        .sorted()
        .collect(Collectors.toList());

    playbackThread = new Thread(() -> {
      //StopWatch to control publishing timings
      Stopwatch stopwatch = Stopwatch.createStarted();
      //final long endTime = playbackEndTime.get();
      final long lastTime = times.get(times.size() - 1);

      times.forEach(time -> {
        //Wait until we should publish the new value
        //startTime is used as an offset in case we are not starting at 0
        while (stopwatch.elapsed(TimeUnit.NANOSECONDS) + startTime < time) {
          playbackPercentage.get().set(stopwatch.elapsed(TimeUnit.NANOSECONDS) / (double) lastTime);

          //Pause the stopwatch when playback is paused
          if (playbackIsPaused.get().get() && stopwatch.isRunning()) {
            stopwatch.stop();
          } else if (!playbackIsPaused.get().get() && !stopwatch.isRunning()) {
            stopwatch.start();
          }

          try {
            Thread.sleep(1);
          } catch (InterruptedException e) {
            break;
          }
        }

        //Get the change and entry
        EntryChange change = playback.get(time);
        NetworkTableEntry entry
            = NetworkTableUtilities.getNetworkTableInstance().getEntry(change.getName());

        //Set the value of the change
        setEntryValue(change, entry);
      });

      playbackDone.get().set(true);
    });

    //Playback should not prevent closing
    playbackThread.setDaemon(true);
    playbackThread.start();
  }

  private void setEntryValue(EntryChange change, NetworkTableEntry entry) {
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
  }

  public void pause() {
    playbackIsPaused.get().set(true);
  }

  public void unpause() {
    playbackIsPaused.get().set(false);
  }

  public void rewind() {
    if (playbackThread != null) { //Null thread means we haven't started playback yet
      playbackThread.interrupt(); //Stop the playback thread
      Arrays.stream(NetworkTableUtilities
          .getNetworkTableInstance()
          .getEntries("", 0xFF))
          .forEach(NetworkTableEntry::delete);
      startPlayback();
    }
  }

  public void skipToTime(long time) {
    if (playbackThread != null) { //Null thread means we haven't started playback yet
      playbackThread.interrupt(); //Stop the playback thread
    }
    Arrays.stream(NetworkTableUtilities
        .getNetworkTableInstance()
        .getEntries("", 0xFF))
        .forEach(NetworkTableEntry::delete);
    Map<String, EntryChange> state = NetworkTableRecorderUtilities
        .computeNetworkTableState(playback, time);
    state.forEach((key, val) ->
        setEntryValue(val, NetworkTableUtilities.getNetworkTableInstance().getEntry(key)));
    startPlayback(time);
  }

  public boolean isDone() {
    return playbackDone.get().get();
  }

  public boolean isRunning() {
    return playbackThread != null && playbackThread.isAlive();
  }

  public boolean isPaused() {
    return playbackIsPaused.get().get();
  }

  public Double getPlaybackPercentage() {
    return playbackPercentage.get().get();
  }

  public AtomicReference<BooleanProperty> playbackDoneProperty() {
    return playbackDone;
  }

  public AtomicReference<BooleanProperty> playbackPausedProperty() {
    return playbackIsPaused;
  }

  public AtomicReference<DoubleProperty> playbackProgressProperty() {
    return playbackPercentage;
  }

}
