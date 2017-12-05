package edu.wpi.first.outlineviewer.controller;

import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import edu.wpi.first.outlineviewer.LoggerUtilities;
import edu.wpi.first.outlineviewer.NetworkTableUtilities;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.control.TextField;

public class NetworkTableGraphController implements Initializable {

  private static final int XAXIS_LENGTH = 1000;
  private static final int XAXIS_DIV = 20;
  private static final int xAxisWindowLength = XAXIS_LENGTH / XAXIS_DIV;

  @FXML
  private LineChart<Number, Number> lineChart;
  @FXML
  private NumberAxis xAxis;
  @FXML
  private NumberAxis yAxis;
  @FXML
  private TextField textFieldGraphViewWidth;

  private final List<String> entries = new CopyOnWriteArrayList<>();
  private final Map<String, LineChart.Series<Number, Number>> seriesMap = new ConcurrentHashMap<>();
  private long startTime;
  private boolean updateFromTextField;
  private boolean graphingThreadShouldStop;

  @Override
  public void initialize(URL location, ResourceBundle resources) {
    textFieldGraphViewWidth.setText(String.valueOf(XAXIS_LENGTH));
    textFieldGraphViewWidth.setOnAction(event -> updateFromTextField = true);

    xAxis.setAutoRanging(false);
    xAxis.setLowerBound(0);
    xAxis.setUpperBound(XAXIS_LENGTH);
    xAxis.setTickUnit(XAXIS_DIV);
    xAxis.setMinorTickVisible(false);
    xAxis.setOnScroll(event -> {
      xAxis.setUpperBound(xAxis.getUpperBound() - event.getDeltaY());
      textFieldGraphViewWidth.setText(
          String.valueOf((int) (xAxis.getUpperBound() - xAxis.getLowerBound())));
    });

    yAxis.setAutoRanging(true);
    yAxis.setLowerBound(0);
    yAxis.setUpperBound(100);
    yAxis.setTickUnit(10);
    yAxis.setMinorTickVisible(false);

    lineChart.setCreateSymbols(false);

    startTime = (long) (System.currentTimeMillis() / 50.0);

    graphingThreadShouldStop = false;
    Thread graphingThread = new Thread(() -> {
      Stopwatch stopwatch = Stopwatch.createStarted();

      while (!graphingThreadShouldStop) {
        step(stopwatch);
        stopwatch.reset().start();
      }
    });
    graphingThread.setDaemon(true);
    graphingThread.start();
  }

  /**
   * Add an entry to the graph.
   *
   * @param key Entry name to graph
   */
  public void graphEntry(String key) {
    entries.add(key);
  }

  /**
   * Stop graphing permanently.
   */
  public void stop() {
    graphingThreadShouldStop = true;
  }

  /**
   * Do one step of graping. Wait for dt and graph entry values.
   *
   * @param stopwatch Stopwatch to track dt
   */
  private void step(Stopwatch stopwatch) {
    while (stopwatch.elapsed(TimeUnit.NANOSECONDS) < 1000000000L) {
      if (graphingThreadShouldStop) {
        return;
      }
      try {
        Thread.sleep(1);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }

    doGraph();
  }

  /**
   * Graph the current values of all the entries. If the entry does not have a series, make a new
   * one.
   */
  private void doGraph() {
    entries.forEach(entry -> {
      if (!seriesMap.containsKey(entry)) {
        LineChart.Series<Number, Number> series = new LineChart.Series<>();
        Platform.runLater(() -> lineChart.getData().add(series));
        seriesMap.put(entry, series);
      }

      addToSeries(seriesMap.get(entry),
          (long) (System.currentTimeMillis() / 50.0),
          NetworkTableUtilities.getNetworkTableInstance().getEntry(entry).getDouble(0));
    });
  }

  /**
   * Add data to a series and scroll the view.
   *
   * @param series Series to add data to
   * @param time Time of data
   * @param value Value of data
   */
  private void addToSeries(LineChart.Series<Number, Number> series, Long time, Number value) {
    Long newTime = time - startTime;
    Platform.runLater(() -> series.getData().add(new LineChart.Data<>(newTime, value)));

    //ObservableList<XYChart.Data<Number, Number>> data = series.getData();
    //for (int i = 0; i < data.size(); i++) {
    //  if (data.get(i).getXValue().doubleValue() < xAxis.getLowerBound()) {
    //    series.getData().remove(i);
    //  }
    //}

    //Trim the end of the plot
    if (newTime > xAxis.getUpperBound() || updateFromTextField) {
      xAxis.setLowerBound(xAxis.getLowerBound() + XAXIS_DIV);
      xAxis.setUpperBound(newTime);

      //If the view size update came from the text field, not from the scroll wheel
      if (updateFromTextField) {
        updateFromTextField = false;
        try {
          if ((int) (xAxis.getUpperBound() - xAxis.getLowerBound()) //NOPMD
              != (int) Double.parseDouble(textFieldGraphViewWidth.getText())) {
            xAxis.setLowerBound(newTime - Double.parseDouble(textFieldGraphViewWidth.getText()));
          }
        } catch (NumberFormatException e) {
          LoggerUtilities.getLogger().log(Level.INFO,
              "Can't graph non-numbers.\n" + Throwables.getStackTraceAsString(e));
        }
      }
    }
  }

}
