package edu.wpi.first.outlineviewer.controller;

import static edu.wpi.first.networktables.EntryListenerFlags.kUpdate;

import edu.wpi.first.outlineviewer.NetworkTableUtilities;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.control.TextField;

public class NetworkTableGraphController implements Initializable {
  private static final int XAXIS_LENGTH = 1000, XAXIS_DIV = 20;
  private static final int xAxisWindowLength = XAXIS_LENGTH / XAXIS_DIV;

  @FXML
  private LineChart<Number, Number> lineChart;
  @FXML
  private NumberAxis xAxis;
  @FXML
  private NumberAxis yAxis;
  @FXML
  private TextField textFieldGraphViewWidth;

  private final List<LineChart.Series<Number, Number>> seriesList = new ArrayList<>();
  private long startTime;
  private boolean updateFromTextField;

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
      xAxis.setLowerBound(xAxis.getLowerBound() + event.getDeltaY());
      textFieldGraphViewWidth.setText(
          String.valueOf(xAxis.getUpperBound() - xAxis.getLowerBound()));
    });

    yAxis.setAutoRanging(false);
    yAxis.setLowerBound(0);
    yAxis.setUpperBound(30);
    yAxis.setTickUnit(10);
    yAxis.setMinorTickVisible(false);

    lineChart.setCreateSymbols(false);

    startTime = (long) (System.currentTimeMillis() / 50.0);
  }

  public void graphEntry(String key) {
    LineChart.Series<Number, Number> series = new LineChart.Series<>();
    series.setName(key);
    lineChart.getData().add(series);
    NetworkTableUtilities.getNetworkTableInstance().getEntry(key).addListener(entryNotification ->
        addToSeries(series, (long) (System.currentTimeMillis() / 50.0),
            entryNotification.value.getDouble()), kUpdate);
  }

  private void addToSeries(LineChart.Series<Number, Number> series, Long time, Number value) {
    Long diff = time - startTime;
    Platform.runLater(() -> series.getData().add(new LineChart.Data<>(diff, value)));

    //Trim the end of the plot
    if (series.getData().size() > xAxisWindowLength) {
      xAxis.setLowerBound(xAxis.getLowerBound() + 1);
      xAxis.setUpperBound(diff);

      if (updateFromTextField) {
        updateFromTextField = false;
        try {
          if ((int) (diff - xAxis.getLowerBound()) != Integer.valueOf(textFieldGraphViewWidth.getText())) {
            xAxis.setLowerBound(diff - Double.valueOf(textFieldGraphViewWidth.getText()));
          }
        } catch (NumberFormatException ignored) {
        }
      }
    }
  }

}
