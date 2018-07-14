package main.java.operations.logger;

import java.io.IOException;
import java.util.LinkedHashMap;

import main.java.operations.arduino.Arduino;
import main.java.operations.arduino.Command;
import main.java.operations.sensors.Measurable;
import main.java.operations.sensors.Sensor;
import main.java.operations.sensors.SensorFactory;
import main.java.operations.sensors.TimeStamp;
import main.java.operations.sensors.combination.SensorCombination;
import main.java.operations.sensors.combination.SensorCombinationFactory;
import main.java.userInterface.main.ChartData;

/**
 * Class that is created as seperate thread. <br>
 * It's main method {@link ReadingsLogger#run()} is responsible of setting
 * variables <br>
 * and continuous pulling the port, reading it if a packet arrives. <br>
 * The process of constructing the main method:<br>
 * -array of all sensors is returned, which order is the same as order of
 * variables in Arduino's packet<br>
 * (this is controlled by program's list {@link SensorFactory#typePrecedence}.
 * <br>
 * -the hashmap for storing sensor and corresponding measurement parameter's are
 * initialized <br>
 * -the quantity of each type's sensors is written to Arduino, reading begins.
 * 
 * @author Piotr Duzniak
 *
 */
public class ReadingsLogger implements Runnable {
	private Boolean stop = false;
	private Boolean save = false;
	private final Arduino serial = Arduino.getInstance();
	private final float LOAD_FACTOR = (float) 0.7;

	public ReadingsLogger(boolean save) {
		this.save = save;
	}

	/**
	 * Main thread for reading data from AVR and saving to chart's array or USD
	 * drive
	 */
	public void run() {
		// holds read arduino's byte stream
		byte[] array = null;

		// if save is selected, methods connected with csv printing activated
		CsvCreator csvCreator = null;
		if (save) {
			csvCreator = new CsvCreator();
		}

		// set array of sensor, in correct reading order. Get number of sensors of each
		Sensor[] sensorArray = SensorFactory.getOrderedArray();
		int[] orderedQuantity = SensorFactory.getOrderedQuantity();

		// calculate how many measurements will be read in one packet. Each is 4 bytes.
		int intsToRead = 0;
		for (int i = 0; i < orderedQuantity.length; i++) {
			intsToRead = intsToRead + orderedQuantity[i];
		}
		intsToRead++; // +1 because of time

		// total amount of buckets needed in hashmap (+1 for safety)
		int buckets = intsToRead + SensorCombinationFactory.combinationMap.size() + 1;
		Float bucketsNeeded = buckets / LOAD_FACTOR;
		buckets = bucketsNeeded.intValue();

		// open serial and initialize number of sensors of each type to read
		serial.open();
		serial.write(Command.SEND_SENSORS_QUANTITY.get(), 1);
		// write in proper order, how many sensors of given type.
		for (int i = 0; i < orderedQuantity.length; i++) {
			serial.write(orderedQuantity[i], 1);
		}

		serial.write(Command.START_MEASURING.get(), 1);

		// loop for constant reading
		while (stop == false) {
			// create each time new map holding new measurements
			LinkedHashMap<Measurable, Double> measureMap = new LinkedHashMap<>(buckets, LOAD_FACTOR, false);

			// each number is sent as arduino's long (4 bytes)
			try {
				array = serial.read(intsToRead * 4);
			} catch (IOException exc) {
				System.out.println(exc);
			}

			// last value is a time
			measureMap.put(TimeStamp.getInstance(),
					TimeStamp.getInstance().getMeasurement(Arduino.byteToInt(array, (intsToRead - 1) * 4)));
			// Convert read byte array to values array
			for (int i = 0; i < sensorArray.length; i++) {
				measureMap.put(sensorArray[i], sensorArray[i].getMeasurement(Arduino.byteToInt(array, i * 4)));
			}
			// Add any combination
			for (SensorCombination sensComb : SensorCombinationFactory.combinationMap.values()) {
				measureMap.put(sensComb, sensComb.getMeasurement(measureMap));
			}

			// add new values to corresponding chart's series
			ChartData.getInstance().appendSeries(measureMap);

			if (save) {
				csvCreator.saveCsv(measureMap);
			}
		}

		serial.write(Command.STOP_MEASURING.get(), 1);
		serial.delay(1000);
		serial.close();

		if (save) {
			csvCreator.close();
		}

	}

	/**
	 * Call this method to stop reading loop, close the port, and exit thread.
	 */
	public synchronized void stop() {
		stop = true;
	}

}