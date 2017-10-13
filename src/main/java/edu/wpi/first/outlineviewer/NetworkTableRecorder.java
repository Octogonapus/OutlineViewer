package edu.wpi.first.outlineviewer;

import com.google.common.base.Charsets;
import com.google.common.io.CharSink;
import com.google.common.io.FileWriteMode;
import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.Paths;
import javafx.beans.property.SimpleObjectProperty;
import javafx.stage.FileChooser;
import javafx.stage.Window;

public class NetworkTableRecorder extends Thread {
  //Keep running the thread, false means kill and exit
  private boolean keepRunning;
  //Whether the thread should pause recording and wait
  private boolean isPaused;
  //State of the overall recorder (only written to in this class, not read from)
  private final SimpleObjectProperty<Thread.State> state;

  public NetworkTableRecorder() {
    super();
    keepRunning = true;
    isPaused = false;
    state = new SimpleObjectProperty<>(State.NEW);
  }

  @Override
  @SuppressWarnings("PMD")
  public void run() {
    System.out.println("RUN!");
//    while (keepRunning) {
//      //Paused means stop recording and wait
//      if (isPaused) {
//        state.set(State.WAITING);
//        waitTimestep();
//        continue;
//      }
//
//      //Else we aren't paused so keep recording
//      state.set(State.RUNNABLE);
//      System.out.println("Foo!");
//      waitTimestep();
//    }
//
//    state.set(State.TERMINATED);
  }

  /**
   * Stop recording, save the recorded data to a file, and join the thread. Shows the user a
   * FileChooser dialog to get the file path.
   * @throws InterruptedException Waiting for the data recorder to stop could be interrupted
   * @throws IOException          Writing to the file could error
   */
  @SuppressWarnings("PMD")
  public void saveAndJoin(Window window) throws IOException, InterruptedException {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Save NetworkTables Recording");
    File result = chooser.showSaveDialog(window);
    if (result != null) {
      //Null means the user hit cancel or didn't select a file and therefore doesn't want to save
      //so only save if the file is not null
      saveAndJoin(result.toPath());
    }
  }

  /**
   * Stop recording, save the recorded data to a file, and join the thread.
   * @throws InterruptedException Waiting for the data recorder to stop could be interrupted
   * @throws IOException          Writing to the file could error
   */
  @SuppressWarnings("PMD")
  public void saveAndJoin(String fileName) throws IOException, InterruptedException {
    saveAndJoin(Paths.get(fileName));
  }

  /**
   * Stop recording, save the recorded data to a file, and join the thread.
   * @throws InterruptedException Waiting for the data recorder to stop could be interrupted
   * @throws IOException          Writing to the file could error
   */
  @SuppressWarnings("PMD")
  public void saveAndJoin(Path path) throws InterruptedException, IOException {
    //Stop running and wait for data to stop
    keepRunning = false;
    //    while (!state.get().equals(State.TERMINATED)) {
    //      try {
    //        sleep(1);
    //      } catch (InterruptedException e) {
    //        e.printStackTrace();
    //      }
    //    }

    //Get lock on file
    File file = path.toFile();
    if (!file.exists()) {
      Files.createParentDirs(file);
      Files.touch(file);
    }
//    FileChannel channel = new RandomAccessFile(file, "rw").getChannel();
//    FileLock lock = channel.tryLock();

    //Write to file
    try {
      CharSink sink = Files.asCharSink(file, Charsets.UTF_8, FileWriteMode.APPEND);
      //TODO: Write data to sink
      sink.write("Hello, world!");
    } catch (OverlappingFileLockException e) {
      //TODO: Tell the user the file is in use
      e.printStackTrace();
    } finally {
//      if (lock != null) {
//        lock.release();
//      }
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
      sleep(1);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }
}
