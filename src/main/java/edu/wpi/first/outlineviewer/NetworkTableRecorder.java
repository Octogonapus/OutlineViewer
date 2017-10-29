package edu.wpi.first.outlineviewer;

import static edu.wpi.first.networktables.EntryListenerFlags.kDelete;
import static edu.wpi.first.networktables.EntryListenerFlags.kNew;
import static edu.wpi.first.networktables.EntryListenerFlags.kUpdate;

import com.google.common.base.Charsets;
import com.google.common.io.CharSink;
import com.google.common.io.FileWriteMode;
import com.google.common.io.Files;
import edu.wpi.first.outlineviewer.model.NTRecord;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.logging.Logger;
import javafx.beans.property.SimpleObjectProperty;
import javafx.stage.FileChooser;
import javafx.stage.Window;

public class NetworkTableRecorder extends Thread {
  private static final String NTR_EXTENSION = ".ntr";

  //Keep running the thread, false means kill and exit
  private boolean keepRunning;
  //Whether the thread should pause recording and wait
  private boolean isPaused;
  //State of the overall recorder (only written to in this class, not read from)
  private final SimpleObjectProperty<Thread.State> state;
  private final NTRecord values;

  public NetworkTableRecorder() {
    super();
    setDaemon(true);
    keepRunning = true;
    isPaused = false;
    state = new SimpleObjectProperty<>(State.NEW);
    values = new NTRecord();
  }

  @Override
  @SuppressWarnings("PMD")
  public void run() {
    NetworkTableUtilities.getNetworkTableInstance().addEntryListener("", notification -> {
      System.out.println("New Entry");
      String entry = notification.getEntry().getString("");
      if (!entry.equals("")) {
        values.put(notification.getEntry().getName(), entry);
      }

      notification.getEntry().addListener(entryNotification -> {
        if ((entryNotification.flags & kNew) != 0 || (entryNotification.flags & kUpdate) != 0) {
          String val = entryNotification.getEntry().getString("");
          if (!val.equals("")) {
            values.put(entryNotification.getEntry().getName(), val);
          }
        } else if ((entryNotification.flags & kDelete) != 0) {
          values.put(entryNotification.getEntry().getName(), "[[DELETED]]");
        }
      }, kUpdate | kDelete);
    }, kNew);

    while (keepRunning) {
      //Paused means stop recording and wait
      if (isPaused) {
        state.set(State.WAITING);
        waitTimestep();
        continue;
      }

      //Else we aren't paused so keep recording
      state.set(State.RUNNABLE);
      //values.add("Hello, World!");
      waitTimestep();
    }

    System.out.println("Terminated!");
    state.set(State.TERMINATED);
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
      values.forEach((key, valList) -> valList.forEach(elem -> {
        try {
          sink.write(key + " : " + elem + "\n");
        } catch (IOException e) {
          e.printStackTrace();
        }
      }));
    } finally {
      join();
    }
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
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }
}
